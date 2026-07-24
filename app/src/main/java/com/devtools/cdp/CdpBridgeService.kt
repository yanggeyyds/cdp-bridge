package com.devtools.cdp

import android.util.Log
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CdpBridgeService —— Shizuku UserService。
 *
 * 研究（关键修正）：
 *   - 客户端 API 模块没有 `Shizuku.UserService` 基类；UserService 直接继承 AIDL 生成的 Stub。
 *   - UserService 由 Shizuku 服务端（shell/root UID，Android shell UID=2000）通过反射实例化，
 *     无参构造必需；带 Context 的构造需 @Keep（本项目只用无参构造）。
 *   - destroy() 方法名固定、AIDL transaction code 固定 16777114，方法内 System.exit(0)
 *     让 UserService 进程退出（Shizuku 不会自动杀进程）。
 *   - UserService 不需要在 manifest 声明 <service>；进程名由 UserServiceArgs.processNameSuffix 控制。
 *   来源: https://github.com/RikkaApps/Shizuku-API/blob/master/demo/src/main/java/rikka/shizuku/demo/service/UserService.java
 *   来源: https://github.com/RikkaApps/Shizuku-API/blob/master/README.md
 *
 * 职责：在 shell UID 进程内
 *   1) startBridge：起 ServerSocket(127.0.0.1:tcpPort)，每 accept 一个连接，
 *      通过 [NativeBridge.connectAbstract] 拿 abstract socket fd，用 [BridgeThread] 双向拷贝；
 *   2) listTargets：读 /proc/net/unix 枚举 @*_devtools_remote；
 *   3) stopBridge / destroy：清理并 System.exit(0)。
 *
 * 硬约束：UserService 内不弹 UI、不拿普通 Context 缓存。
 */
class CdpBridgeService : ICdpBridge.Stub() {

    companion object {
        private const val TAG = "CdpBridgeService"
    }

    private val running = AtomicBoolean(false)
    private var server: ServerSocket? = null
    private val clientThreads = ConcurrentHashMap.newKeySet<Thread>()
    @Volatile private var currentAbstractName: String? = null

    /**
     * 无参主构造：Shizuku 反射实例化必需。
     * 注意：继承 AIDL Stub 时只能用主构造（不能用 secondary constructor + super()），
     * 否则报 "Supertype initialization is impossible without primary constructor"。
     */
    init {
        Log.i(TAG, "CdpBridgeService constructed in pid=${android.os.Process.myPid()} uid=${android.os.Process.myUid()}")
    }

    override fun startBridge(tcpPort: Int, abstractName: String?): Boolean {
        if (abstractName.isNullOrEmpty()) {
            Log.e(TAG, "startBridge: abstractName empty")
            return false
        }
        stopBridge()
        currentAbstractName = abstractName
        running.set(true)
        try {
            // 仅绑定 127.0.0.1，绝不上局域网
            val s = ServerSocket()
            s.bind(InetSocketAddress("127.0.0.1", tcpPort))
            server = s
            Log.i(TAG, "bridge listening 127.0.0.1:$tcpPort -> @$abstractName")
        } catch (e: Throwable) {
            Log.e(TAG, "bind 127.0.0.1:$tcpPort failed: ${e.message}")
            running.set(false)
            return false
        }

        val acceptThread = Thread({
            // 持有本地引用避免在 lambda 内直接读可变 server 字段
            val srv = server
            while (running.get() && srv != null && !srv.isClosed) {
                val client: Socket = try {
                    srv.accept()
                } catch (_: Throwable) {
                    break
                }
                // 每个 TCP 连接对应一条独立的 abstract socket fd（CDP 一次会话一条 WS）
                val t = Thread({
                    var fd: java.io.FileDescriptor? = null
                    try {
                        fd = NativeBridge.connectAbstract(abstractName)
                        if (fd == null) {
                            Log.e(TAG, "connectAbstract returned null for $abstractName")
                            try { client.close() } catch (_: Throwable) {}
                            return@Thread
                        }
                        BridgeThread.bridge(client, fd)
                    } catch (t: Throwable) {
                        Log.e(TAG, "bridge thread error: ${t.message}")
                    } finally {
                        try { client.close() } catch (_: Throwable) {}
                        if (fd != null) {
                            try {
                                // 通过 FileInputStream close 间接关 fd
                                java.io.FileInputStream(fd).close()
                            } catch (_: Throwable) {}
                        }
                        clientThreads.remove(Thread.currentThread())
                    }
                }, "cdp-bridge-accept-${client.remoteSocketAddress}")
                clientThreads.add(t)
                t.start()
            }
        }, "cdp-bridge-accept")
        clientThreads.add(acceptThread)
        acceptThread.isDaemon = true
        acceptThread.start()
        return true
    }

    override fun stopBridge() {
        running.set(false)
        try { server?.close() } catch (_: Throwable) {}
        server = null
        for (t in clientThreads) { t.interrupt() }
        clientThreads.clear()
        currentAbstractName = null
        Log.i(TAG, "bridge stopped")
    }

    /**
     * 读 /proc/net/unix，过滤 abstract（行末名字以 '@' 开头）且含 "_devtools_remote" 的条目。
     * 返回不带 '@' 的 socket 名列表。
     *
     * 研究：chrome-remote-interface 官方用 `adb shell grep _devtools_remote /proc/net/unix`，
     * '@' 前缀表示该行属于 abstract namespace，连接时去掉 '@'。
     * 来源: https://github.com/cyrus-and/chrome-remote-interface
     * 来源: https://blog.csdn.net/revivedsun/article/details/103831635
     */
    override fun listTargets(): MutableList<String> {
        val out = mutableListOf<String>()
        try {
            File("/proc/net/unix").useLines { lines ->
                lines.forEach { raw ->
                    val line = raw.trim()
                    if (line.isEmpty()) return@forEach
                    // /proc/net/unix 最后一列是路径/名字；取最后一个 token
                    val lastSpace = line.lastIndexOf(' ')
                    val name = if (lastSpace >= 0) line.substring(lastSpace + 1) else ""
                    if (name.startsWith("@") && name.contains("_devtools_remote")) {
                        out.add(name.substring(1)) // 去掉 '@'
                    }
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "read /proc/net/unix failed: ${e.message}")
        }
        // 去重保持顺序
        return LinkedHashSet(out).toMutableList()
    }

    /**
     * 探测 abstract socket 是否真的可连：调用 [NativeBridge.connectAbstract] 拿 fd，
     * 成功即说明对端进程在监听；失败说明 socket 名虽在 /proc/net/unix 里（可能是残留条目），
     * 但实际无进程 accept，或本进程无权限连。
     *
     * 这是关键诊断：startBridge 只绑定了本地 TCP 监听，并不验证 Chrome 端可达；
     * 有了 probeAbstract，UI 能在桥接"启动成功"后立刻区分"Chrome 真的活着" vs "socket 是死条目"。
     */
    override fun probeAbstract(abstractName: String?): Int {
        if (abstractName.isNullOrEmpty()) return -1
        var fd: java.io.FileDescriptor? = null
        return try {
            fd = NativeBridge.connectAbstract(abstractName)
            if (fd == null) {
                Log.e(TAG, "probeAbstract($abstractName): connectAbstract returned null")
                -1
            } else {
                Log.i(TAG, "probeAbstract($abstractName): connectable")
                0
            }
        } catch (t: Throwable) {
            Log.e(TAG, "probeAbstract($abstractName) exception: ${t.message}")
            -2
        } finally {
            if (fd != null) {
                try { java.io.FileInputStream(fd).close() } catch (_: Throwable) {}
            }
        }
    }

    /**
     * Shizuku 约定的销毁方法（AIDL transaction code 16777114）。
     * 必须在此 System.exit(0) 让 UserService 进程退出。
     */
    override fun destroy() {
        Log.i(TAG, "destroy() called, exiting process")
        stopBridge()
        System.exit(0)
    }
}
