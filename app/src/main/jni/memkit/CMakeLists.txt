cmake_minimum_required(VERSION 3.31.6)

set(MEMKIT_SRCS
        memkit.c
)

add_library(memkit SHARED ${MEMKIT_SRCS})

# lua: 复用仓库内 Lua 静态库(提供 luaopen_memkit 所需的 lua_* 符号)
target_link_libraries(memkit
        lua
        log
        dl
)

# jni.h: 与 luaparser 保持同一套查找方式
find_package(JNI REQUIRED)
if (JNI_FOUND)
    include_directories(${JNI_INCLUDE_DIRS})
endif ()

target_include_directories(memkit PRIVATE
        ${CMAKE_SOURCE_DIR}/lua
        ${CMAKE_CURRENT_SOURCE_DIR}
)

target_compile_options(memkit PRIVATE
        -fPIC
        -Wall
        -fvisibility=default
)

target_compile_definitions(memkit PRIVATE
        ANDROID
)
