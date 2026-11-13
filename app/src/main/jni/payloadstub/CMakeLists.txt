cmake_minimum_required(VERSION 3.31.6)

# so 打包模式的载荷容器。
#
# 与 fastio 同类:纯 JNI 库,不走 add_and_optimize_library() 的 Lua 模块约定。
# 目标名带 `luaforge_` 前缀,产物为 libluaforge_payload.so ——
# 打包时 ApkBuilder 把加密后的项目载荷追加到这个文件尾部。
add_library(luaforge_payload SHARED
        payloadstub.c
)

# 需要 lfprotect.h(与 ldump/lundump 共享的混淆实现)—— 在 ../lua 下
target_include_directories(luaforge_payload PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/../lua
)

if(ANDROID)
    find_library(LOG_LIB log)
    if(LOG_LIB)
        target_link_libraries(luaforge_payload ${LOG_LIB})
    endif()
    # dladdr 需要 libdl
    target_link_libraries(luaforge_payload dl)
endif()

target_compile_options(luaforge_payload PRIVATE
        -std=c17
        -Wall
        -fvisibility=hidden
)

set_target_properties(luaforge_payload PROPERTIES
        C_VISIBILITY_PRESET hidden
        POSITION_INDEPENDENT_CODE ON
)
