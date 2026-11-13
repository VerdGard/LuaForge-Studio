cmake_minimum_required(VERSION 3.31.6)

# 极速文件读写:纯 JNI,不链接 Lua。
#
# 与 jni 下其他模块不同,这个库不导出 luaopen_xxx —— 它面向 Java 侧
# FastIOUtil,由 System.loadLibrary("fastio") 加载。因此不参与
# CMakeLists.txt 里 add_and_optimize_library() 的 "自动导出 luaopen_<dir>"
# 约定,而是自行定义目标。
add_library(fastio SHARED
        fastio.c
)

if(ANDROID)
    find_library(LOG_LIB log)
    if(LOG_LIB)
        target_link_libraries(fastio ${LOG_LIB})
    endif()
endif()

target_compile_options(fastio PRIVATE
        -std=c17
        -Wall
        -fvisibility=hidden
)

# 只导出 JNI 入口点:其余符号(cmake 全局已加 -Wl,--gc-sections)一律隐藏,
# 避免与其他模块的同名静态符号冲突。
set_target_properties(fastio PROPERTIES
        C_VISIBILITY_PRESET hidden
        POSITION_INDEPENDENT_CODE ON
)
