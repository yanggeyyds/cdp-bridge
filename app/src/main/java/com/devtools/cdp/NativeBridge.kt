package com.devtools.cdp

import androidx.annotation.Keep
import java.io.FileDescriptor

/**
 * NativeBridge：JNI 桥接的 Kotlin 声明。
 *
 * 唯一职责：通过 native 方法 [connectAbstract] 拿到已 connect 成功的
 * [FileDescriptor]，该 fd 持有一条连到 Chrome/WebView abstract namespace
 * UNIX socket（如 "chrome_devtools_remote"）的连接。
 *
 * 研究：Java 层无法直接 `new Socket("chrome_devtools_remote")` 连 abstract socket，
 * 必须 C 层 socket(AF_UNIX)+connect。此 native 实现见 src/main/cpp/bridge.c。
 * 来源: https://salsa.debian.org/android-tools-team/android-tools/-/blob/master/system/core/libcutils/socket_local_client.c
 * 来源: https://man7.org/linux/man-pages/man7/unix.7.html
 *
 * 注意：在 Shizuku UserService 进程（shell UID 2000）内调用，
 * 因为只有 shell UID 能 connect 到 Chrome 暴露的 abstract socket（普通 App UID 无权限）。
 */
object NativeBridge {

    init {
        System.loadLibrary("cdpbridge")
    }

    /**
     * 连接 abstract namespace socket。
     *
     * @param name 不带 '@' 前缀的 socket 名，如 "chrome_devtools_remote"
     * @return 已 connect 的 [FileDescriptor]；失败返回 null
     */
    @Keep
    external fun connectAbstract(name: String): FileDescriptor?
}
