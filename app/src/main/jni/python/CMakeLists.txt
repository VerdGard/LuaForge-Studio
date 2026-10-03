cmake_minimum_required(VERSION 3.31.6)

# python: Lua <-> CPython 嵌入桥(单文件 C)
#
# 目录名即 Lua 模块名: require "python" 由 luaCpath 的 lib?.so 解析,
# 落到 nativeLibraryDir/libpython.so,并调用 luaopen_python。
# 顶层 add_and_optimize_library(python) 会自动导出该符号。
#
# 与其它模块的关键差异: **不在链接期依赖 libpython**
#   - libpython3.14.so 由 APK 运行时投放到 nativeLibraryDir,这里只记 soname,
#     启动时 dlopen。好处:
#       1) 运行时只提供 arm64-v8a(3.12+ 无 32 位产物),链接期依赖会让
#          "Python 不可用"变成加载 .so 失败并拖垮整个 app;dlopen 则退化为
#          一次可预期的可用性检查(armv7 上 pythonAvailable() = false)
#       2) 免依赖对方头文件:CPython 3.13 起已把 Py_SetPath 等移出公开头文件
#          (符号仍在),自声明最小 ABI 更稳
#   - lib-dynload 下的扩展自身 NEEDED 该 soname,由 nativeLibraryDir 解析

set(PYTHON_SRCS
        python.c
)

add_library(python SHARED ${PYTHON_SRCS})

# lua: 复用仓库内 Lua 静态库(提供 luaopen_python 所需的 lua_* 符号)
# dl : 运行时 dlopen libpython3.14.so
target_link_libraries(python
        lua
        log
        dl
        z
)

find_package(JNI REQUIRED)
if (JNI_FOUND)
    include_directories(${JNI_INCLUDE_DIRS})
endif ()

target_include_directories(python PRIVATE
        ${CMAKE_SOURCE_DIR}/lua
        ${CMAKE_CURRENT_SOURCE_DIR}
)

target_compile_options(python PRIVATE
        -fPIC
        -Wall
        -Wextra
        -Wno-unused-parameter
        -fvisibility=default
)

target_compile_definitions(python PRIVATE
        ANDROID
)
