package com.devtools.cdp

import android.content.Intent
import android.os.IBinder
import com.topjohnwu.superuser.ipc.RootService

/**
 * RootCdpBridgeService —— Root 模式下的服务承载。
 *
 * 设计：复用 [CdpBridgeService] 的全部 JNI 桥接逻辑（连 abstract socket + TCP 双向拷贝），
 * 仅由 libsu 的 [RootService] 以 root 进程拉起。这样 root UID 进程能直接连接任意
 * abstract socket（Chrome / WebView 的 _devtools_remote），无需 Shizuku。
 *
 * 与 Shizuku 路径共用同一个 [ICdpBridge] 接口，客户端（MainActivity）拿到 binder 后
 * 调用方式完全一致，ViewModel 无感知来源差异。
 *
 * 来源: https://github.com/topjohnwu/libsu/wiki/RootService
 *   - RootService 继承自 android.app.Service，必须在 AndroidManifest 声明 <service>。
 *   - RootService.bind(Intent, ServiceConnection) 内部以 root 拉起独立进程并绑定。
 *   - onBind 返回的 IBinder 跨进程传回 app，用 ICdpBridge.Stub.asInterface 还原。
 */
class RootCdpBridgeService : RootService() {

    override fun onBind(intent: Intent?): IBinder {
        // 复用 Shizuku 路径的同一个 Stub 实现
        return CdpBridgeService()
    }
}
