/*
 * bridge.c —— JNI 桥接：连接 Android abstract namespace UNIX socket，返回 FileDescriptor。
 *
 * 仅做一件事：socket(AF_UNIX, SOCK_STREAM, 0) 连 abstract socket，返回持有的 fd。
 *
 * 研究依据（abstract socket 长度计算）：
 *   AOSP libcutils socket_local_client.c 原码：
 *     p_addr->sun_path[0] = 0;
 *     memcpy(p_addr->sun_path + 1, name, namelen);
 *     *alen = namelen + offsetof(struct sockaddr_un, sun_path) + 1;
 *   即 alen == offsetof(sockaddr_un, sun_path) + 1 + strlen(name)，与本实现一致。
 *   关键：abstract socket 绝不能用 SUN_LEN（它对 sun_path[0]=='\0' 会得 0）。
 *   来源: https://salsa.debian.org/android-tools-team/android-tools/-/blob/master/system/core/libcutils/socket_local_client.c
 *   来源: https://man7.org/linux/man-pages/man7/unix.7.html
 *
 * JNI FileDescriptor 包装：
 *   系统构建可用 jniCreateFileDescriptor(env, fd)（libnativehelper，非 NDK 公开 API）；
 *   App 工程通用做法是反射构造 java.io.FileDescriptor 并设私有 int 字段。
 *   Android 多数版本字段名为 "descriptor"，个别版本为 "fd"，这里回退兼容。
 *   来源: https://sqlite.org/android/doc/e782e01fbe7dd7b3/sqlite3/src/main/jni/sqlite/nativehelper/JNIHelp.h
 *   来源: https://blog.csdn.net/shell812/article/details/49763195
 */

#include <jni.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <stddef.h>
#include <string.h>
#include <unistd.h>
#include <errno.h>
#include <android/log.h>

#define LOG_TAG "cdpbridge_native"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/*
 * 把 int fd 包装成 java.io.FileDescriptor 返回给 Kotlin。
 * 字段名 Android 多为 "descriptor"，回退 "fd"。
 */
static jobject fd_from_int(JNIEnv *env, int fd) {
    jclass cFD = (*env)->FindClass(env, "java/io/FileDescriptor");
    if (!cFD) {
        LOGE("FindClass java/io/FileDescriptor failed");
        return NULL;
    }
    jmethodID ctor = (*env)->GetMethodID(env, cFD, "<init>", "()V");
    if (!ctor) {
        LOGE("GetMethodID <init> failed");
        return NULL;
    }

    jfieldID fid = (*env)->GetFieldID(env, cFD, "descriptor", "I");
    if (!fid) {
        (*env)->ExceptionClear(env);
        fid = (*env)->GetFieldID(env, cFD, "fd", "I");
        if (!fid) {
            LOGE("GetFieldID descriptor/fd failed");
            return NULL;
        }
    }

    jobject fdObj = (*env)->NewObject(env, cFD, ctor);
    if (fdObj) {
        (*env)->SetIntField(env, fdObj, fid, (jint)fd);
    }
    return fdObj;
}

/*
 * Java 侧声明:
 *   external fun connectAbstract(name: String): FileDescriptor?
 *
 * name 为不带 '@' 的 abstract socket 名（如 "chrome_devtools_remote"）。
 * 返回已 connect 成功的 FileDescriptor；失败返回 NULL。
 */
JNIEXPORT jobject JNICALL
Java_com_devtools_cdp_NativeBridge_connectAbstract(JNIEnv *env, jclass clazz, jstring name) {
    (void)clazz;
    if (!name) {
        LOGE("connectAbstract: name is null");
        return NULL;
    }
    const char *cname = (*env)->GetStringUTFChars(env, name, NULL);
    if (!cname) return NULL;

    int fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0) {
        LOGE("socket() failed: %s", strerror(errno));
        (*env)->ReleaseStringUTFChars(env, name, cname);
        return NULL;
    }

    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    addr.sun_path[0] = '\0';                                   /* abstract 标志 */
    strncpy(addr.sun_path + 1, cname, sizeof(addr.sun_path) - 2);

    /* 关键：abstract socket 长度 = offsetof(sun_path) + 1 + strlen(name)，绝不用 SUN_LEN */
    size_t name_len = strlen(cname);
    socklen_t len = (socklen_t)(offsetof(struct sockaddr_un, sun_path) + 1 + name_len);

    (*env)->ReleaseStringUTFChars(env, name, cname);

    if (connect(fd, (struct sockaddr *)&addr, len) < 0) {
        LOGE("connect(%s) failed: %s", addr.sun_path + 1, strerror(errno));
        close(fd);
        return NULL;
    }
    LOGI("connect abstract socket ok, fd=%d", fd);
    return fd_from_int(env, fd);
}
