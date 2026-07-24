package com.devtools.cdp

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.SharedPreferences
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import com.devtools.cdp.ui.AppRoot
import com.devtools.cdp.ui.BridgeMode
import com.devtools.cdp.ui.CdpTheme
import com.devtools.cdp.ui.CdpViewModel
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ipc.RootService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

/**
 * MainActivity —— 桥接服务绑定（Root / Shizuku 双模式）+ Compose 入口。
 *
 * 双模式设计：
 *   - Root 模式（默认）：用 libsu [RootService] 以 root 进程拉起 [RootCdpBridgeService]，
 *     其 onBind 返回 [CdpBridgeService]（复用全部 JNI 桥接逻辑）。root UID 可直连任意 abstract socket。
 *   - Shizuku 模式：通过 [Shizuku.bindUserService] 在 shell UID 进程内拉起 [CdpBridgeService]，
 *     无需 root，需安装 Shizuku App。
 *
 * 两种模式共用同一个 [ICdpBridge] 接口，[CdpViewModel] 无感知来源差异。
 *
 * Shizuku 研究（关键修正）：
 *   - `Shizuku.isRunning()` 不存在；用 `Shizuku.pingBinder()` + listener 监听 binder 生命周期。
 *   - pre-v11 旧服务不支持权限，需先 `Shizuku.isPreV11()` 判断。
 *   - `Shizuku.requestPermission(int)` 异步，结果经 `addRequestPermissionResultListener` 回调。
 *   来源: https://github.com/RikkaApps/Shizuku-API/blob/master/api/src/main/java/rikka/shizuku/Shizuku.java
 */
class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_CODE = 0
        /** 桥接监听的本地 TCP 端口（所有 CDP 流量走 127.0.0.1:9222）。 */
        private const val BRIDGE_PORT = 9222
        private const val PREFS_NAME = "cdp_bridge_prefs"
        private const val KEY_MODE = "bridge_mode"
        private const val MODE_ROOT = "root"
        private const val MODE_SHIZUKU = "shizuku"
    }

    private val viewModel: CdpViewModel by viewModels()
    private val prefs: SharedPreferences by lazy {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
    }

    @Volatile private var shizukuBound = false
    @Volatile private var rootBound = false
    private var rootServiceConn: ServiceConnection? = null

    // ---------- Shizuku 绑定 ----------

    private val userServiceArgs: Shizuku.UserServiceArgs by lazy {
        Shizuku.UserServiceArgs(
            ComponentName(this, CdpBridgeService::class.java)
        )
            .daemon(false)
            .processNameSuffix("shizuku")
            .version(BuildConfig.VERSION_CODE)
    }

    private val shizukuServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(componentName: ComponentName?, binder: IBinder?) {
            val bridge = ICdpBridge.Stub.asInterface(binder)
            Log.i(TAG, "Shizuku UserService connected: $bridge")
            viewModel.onBridgeConnected(bridge, BRIDGE_PORT)
        }

        override fun onServiceDisconnected(componentName: ComponentName?) {
            Log.w(TAG, "Shizuku UserService disconnected")
            shizukuBound = false
            viewModel.onBridgeDisconnected()
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        Log.i(TAG, "Shizuku binder received")
        // 仅在 Shizuku 模式下响应，避免 Root 模式被 Shizuku 状态干扰
        if (currentMode() == BridgeMode.SHIZUKU) checkShizukuAndBind()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        Log.w(TAG, "Shizuku binder dead")
        shizukuBound = false
        if (currentMode() == BridgeMode.SHIZUKU) viewModel.onBinderDead()
    }

    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            Log.i(TAG, "permission result: code=$requestCode grant=$grantResult")
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                bindShizukuUserService()
            } else {
                viewModel.onPermissionDenied()
            }
        }

    // ---------- 生命周期 ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // libsu Shell 配置：超时 10s（探测 root 用）
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setFlags(Shell.FLAG_REDIRECT_STDERR)
                .setTimeout(10)
        )

        Shizuku.addBinderReceivedListener(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionResultListener)

        // 初始化 ViewModel 的 mode（同步，不触发重置），再按模式绑定
        viewModel.initMode(currentMode())
        rebind()

        setContent {
            CdpTheme {
                AppRoot(viewModel = viewModel, onModeChange = ::setModeAndRebind)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { Shizuku.removeBinderReceivedListener(binderReceivedListener) } catch (_: Throwable) {}
        try { Shizuku.removeBinderDeadListener(binderDeadListener) } catch (_: Throwable) {}
        try { Shizuku.removeRequestPermissionResultListener(permissionResultListener) } catch (_: Throwable) {}
        unbindAll()
    }

    // ---------- 模式切换 ----------

    /** 当前持久化的桥接模式。 */
    fun currentMode(): BridgeMode =
        if (prefs.getString(KEY_MODE, MODE_ROOT) == MODE_SHIZUKU) BridgeMode.SHIZUKU
        else BridgeMode.ROOT

    /** UI 触发：切换模式并重新绑定。 */
    fun setModeAndRebind(mode: BridgeMode) {
        prefs.edit()
            .putString(KEY_MODE, if (mode == BridgeMode.SHIZUKU) MODE_SHIZUKU else MODE_ROOT)
            .apply()
        viewModel.setMode(mode)
        rebind()
    }

    /** 按当前模式重新绑定服务。 */
    fun rebind() {
        unbindAll()
        when (currentMode()) {
            BridgeMode.ROOT -> bindRootService()
            BridgeMode.SHIZUKU -> checkShizukuAndBind()
        }
    }

    private fun unbindAll() {
        unbindRootService()
        unbindShizukuUserService()
    }

    // ---------- Root 绑定 ----------

    private fun bindRootService() {
        lifecycleScope.launch {
            // Shell.getShell() 阻塞，需在 IO 线程；失败说明无 root
            val hasRoot = withContext(Dispatchers.IO) {
                runCatching { Shell.getShell() }.isSuccess
            }
            if (!hasRoot) {
                viewModel.onRootNoShell()
                return@launch
            }
            if (rootBound) return@launch
            val conn = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    val bridge = ICdpBridge.Stub.asInterface(binder)
                    Log.i(TAG, "RootService connected: $bridge")
                    viewModel.onBridgeConnected(bridge, BRIDGE_PORT)
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    Log.w(TAG, "RootService disconnected")
                    rootBound = false
                    viewModel.onBridgeDisconnected()
                }
            }
            rootServiceConn = conn
            try {
                // RootService.bind 必须在主线程
                RootService.bind(
                    Intent(this@MainActivity, RootCdpBridgeService::class.java),
                    conn
                )
                rootBound = true
            } catch (t: Throwable) {
                Log.e(TAG, "RootService.bind failed: ${t.message}")
                viewModel.onBindFailed("Root 绑定失败：${t.message}")
            }
        }
    }

    private fun unbindRootService() {
        if (!rootBound && rootServiceConn == null) return
        try { viewModel.onBridgeDisconnected() } catch (_: Throwable) {}
        rootServiceConn?.let { conn ->
            runCatching { unbindService(conn) }
        }
        rootServiceConn = null
        rootBound = false
    }

    // ---------- Shizuku 绑定 ----------

    private fun checkShizukuAndBind() {
        if (!Shizuku.pingBinder()) {
            viewModel.onShizukuNotRunning()
            return
        }
        if (Shizuku.isPreV11()) {
            viewModel.onShizukuTooOld()
            return
        }
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            bindShizukuUserService()
        } else if (!Shizuku.shouldShowRequestPermissionRationale()) {
            viewModel.onWaitingPermission()
            Shizuku.requestPermission(REQUEST_CODE)
        } else {
            viewModel.onPermissionDenied()
        }
    }

    private fun bindShizukuUserService() {
        if (shizukuBound) return
        try {
            Shizuku.bindUserService(userServiceArgs, shizukuServiceConnection)
            shizukuBound = true
        } catch (t: Throwable) {
            Log.e(TAG, "bindUserService failed: ${t.message}")
            viewModel.onBindFailed("Shizuku 绑定失败：${t.message}")
        }
    }

    private fun unbindShizukuUserService() {
        if (!shizukuBound) return
        try { viewModel.onBridgeDisconnected() } catch (_: Throwable) {}
        try {
            Shizuku.unbindUserService(userServiceArgs, shizukuServiceConnection, true)
        } catch (_: Throwable) {}
        shizukuBound = false
    }
}
