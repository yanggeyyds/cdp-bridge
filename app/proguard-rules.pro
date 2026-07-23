# Keep Shizuku UserService 与 AIDL Stub 类（Shizuku 通过反射实例化）
-keep class com.devtools.cdp.CdpBridgeService { *; }
-keep class com.devtools.cdp.ICdpBridge { *; }
-keep class com.devtools.cdp.ICdpBridge$* { *; }

# Keep native 方法
-keepclasseswithmembernames class * {
    native <methods>;
}

# Gson 反序列化 POJO
-keep class com.devtools.cdp.data.** { *; }
