# CMakeLists.txt —— 编译 libcdpbridge.so
# 来源: https://developer.android.com/ndk/guides/cmake
cmake_minimum_required(VERSION 3.10.2)
project(cdpbridge C)

# 编译 .c 为 SHARED 库 libcdpbridge.so
add_library(cdpbridge SHARED bridge.c)

# 链接 Android log 库（socket/unix API 在 bionic libc，默认链接，无需显式声明）
find_library(log-lib log)

target_link_libraries(cdpbridge ${log-lib})
