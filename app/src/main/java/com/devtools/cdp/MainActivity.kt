package com.devtools.cdp

import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import rikka.shizuku.Shizuku
import com.devtools.cdp.ui.AppRoot
import com.devtools.cdp.ui.CdpViewModel

/**
 * MainActivity —— Shizuku 绑定 + Compose 入口。
 *
 * 研究（关键修正）：
 *   - `Shizuku.isRunning()` 不存在；改用 `Shizuku.pingBinder()` + `addBinderReceivedListener`
 *     / `addBinderDeadListener` 监听 binder 生命周期。
 *   - `Shizuku.checkSelfPermission()` 返回 PERMISSION_GRANTED / -1；pre-v11 旧服务不支持权限，
 *     需先 `Shizuku.isPreV11()` 判断。
 *   - `Shizuku.requestPermission(int requestCode)` 异步，结果经
 *     `addRequestPermissionResultListener` 回调。
 *   - `Shizuku.bindUserService(UserServiceArgs, ServiceConnection)`：
 *       UserServiceArgs(ComponentName(this, CdpBridgeService::class.java))
 *           .daemon(false)
 *           .processNameSuffix("shizuku")
 *           .version(BuildConfig.VERSION_CODE)
 *   - 解绑：`Shizuku.unbindUserService(args, conn, remove)`；销毁走 `ICdpBridge.destroy()`。
 *   - UserService 不在 manifest 声明 <service>，进程名由 processNameSuffix 决定。
 *   来源: https://github.com/RikkaApps/Shizuku-API/blob/master/api/src/main/java/rikka/shizuku/Shizuku.java
 *   来源: https://github.com/RikkaApps/Shizuku-API/blob/master/demo/src/main/java/rikka/shizuku/demo/DemoActivity.java
 */
class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_CODE = 0
        /** 桥接监听的本地 TCP 端口（所有 CDP 流量走 127.0.0.1:9222）。 */
        private const val BRIDGE_PORT = 9222
    }

    private val viewModel: CdpViewModel by viewModels()

    @Volatile private var bound: Boolean = false

    private val userServiceArgs: Shizuku.UserServiceArgs by lazy {
        Shizuku.UserServiceArgs(
            ComponentName(this, CdpBridgeService::class.java)
        )
            .daemon(false)
            .processNameSuffix("shizuku")
            .version(BuildConfig.VERSION_CODE)
    }

    private val serviceConnection = object : android.content.ServiceConnection {
        override fun onServiceConnected(componentName: ComponentName?, binder: android.os.IBinder?) {
            val bridge = ICdpBridge.Stub.asInterface(binder)
            Log.i(TAG, "UserService connected: $bridge")
            viewModel.onBridgeConnected(bridge, BRIDGE_PORT)
        }

        override fun onServiceDisconnected(componentName: ComponentName?) {
            Log.w(TAG, "UserService disconnected")
            bound = false
            viewModel.onBridgeDisconnected()
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        Log.i(TAG, "Shizuku binder received")
        checkAndBind()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        Log.w(TAG, "Shizuku binder dead")
        bound = false
        viewModel.onBinderDead()
    }

    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            Log.i(TAG, "permission result: code=$requestCode grant=$grantResult")
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                bindUserService()
            } else {
                viewModel.onPermissionDenied()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Shizuku.addBinderReceivedListener(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionResultListener)

        // 若 Shizuku 已就绪，立即尝试一次
        if (Shizuku.pingBinder()) {
            checkAndBind()
        }

        setContent {
            AppRoot(viewModel = viewModel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { Shizuku.removeBinderReceivedListener(binderReceivedListener) } catch (_: Throwable) {}
        try { Shizuku.removeBinderDeadListener(binderDeadListener) } catch (_: Throwable) {}
        try { Shizuku.removeRequestPermissionResultListener(permissionResultListener) } catch (_: Throwable) {}
        unbindUserService()
    }

    private fun checkAndBind() {
        if (!Shizuku.pingBinder()) {
            viewModel.onShizukuNotRunning()
            return
        }
        if (Shizuku.isPreV11()) {
            viewModel.onShizukuTooOld()
            return
        }
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            bindUserService()
        } else if (!Shizuku.shouldShowRequestPermissionRationale()) {
            Shizuku.requestPermission(REQUEST_CODE)
        } else {
            viewModel.onPermissionDenied()
        }
    }

    private fun bindUserService() {
        if (bound) return
        try {
            Shizuku.bindUserService(userServiceArgs, serviceConnection)
            bound = true
        } catch (t: Throwable) {
            Log.e(TAG, "bindUserService failed: ${t.message}")
            viewModel.onBindFailed(t.message ?: "bind failed")
        }
    }

    private fun unbindUserService() {
        if (!bound) return
        try {
            viewModel.onBridgeDisconnected()
        } catch (_: Throwable) {}
        try {
            Shizuku.unbindUserService(userServiceArgs, serviceConnection, true)
        } catch (_: Throwable) {}
        bound = false
    }
}
