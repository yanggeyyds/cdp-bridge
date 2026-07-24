// ICdpBridge.aidl —— Shizuku UserService 暴露的桥接接口
//
// 研究：UserService 由 Shizuku 服务端（shell/root UID）实例化，AIDL 方法直接跨进程调用。
// destroy() 方法的 transaction code 在 Shizuku 服务端约定为 16777114（aidl 写法 = 16777114），
// Shizuku 会在销毁进程时调用它；方法名必须叫 destroy。
// 来源: https://github.com/RikkaApps/Shizuku-API/blob/master/README.md
// 来源: https://github.com/RikkaApps/Shizuku-API/blob/master/demo/src/main/aidl/rikka/shizuku/demo/IUserService.aidl
package com.devtools.cdp;

interface ICdpBridge {

    // 启动桥接：在 UserService（shell UID 2000）内开 ServerSocket 监听 127.0.0.1:tcpPort，
    // 每个 accept 的连接通过 JNI 连到 abstract socket（abstractName 不带 '@' 前缀），
    // 双向 byte 拷贝（socat 语义）。返回成功与否。
    boolean startBridge(int tcpPort, String abstractName) = 1;

    // 停止桥接：关闭 ServerSocket 与所有活动连接。
    void stopBridge() = 2;

    // 枚举当前可调试 target：读 /proc/net/unix 过滤 @<name>_devtools_remote。
    // 返回不带 '@' 的 abstract socket 名列表（如 chrome_devtools_remote、webview_devtools_remote_8985）。
    List<String> listTargets() = 3;

    // 探测 abstract socket 是否真的可连（不传输数据）。
    // 返回 0=可连；-1=connect 失败（socket 不存在/进程未运行/权限不足）；
    // -2=异常。用于在 startBridge 后立即判断 Chrome 是否真的在监听，
    // 区别于 startBridge 仅绑定 TCP 监听、未验证对端。
    int probeAbstract(String abstractName) = 4;

    // 枚举 target 并附带所属应用信息。
    // 返回 "abstractName\t进程名\t包名" 列表（tab 分隔），用于在 UI 显示是哪个 App。
    // 进程名/包名通过 /proc/net/unix 找到 inode，再匹配 /proc/<pid>/net/unix 反查 pid，
    // 最后读 /proc/<pid>/cmdline。Root/Shizuku shell UID 有权限读其他进程的 /proc。
    List<String> listTargetsWithInfo() = 5;

    // Shizuku 约定的销毁方法，transaction code 固定 16777114。
    // 在此方法内 System.exit(0) 让 UserService 进程退出。
    // 注意：aidl 要求"要么所有方法都赋 id，要么都不赋"，故其余方法也赋了小整数 id。
    void destroy() = 16777114;
}
