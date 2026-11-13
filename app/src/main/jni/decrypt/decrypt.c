/*
 * libdecrypt.so - Lua 加密脚本加载器
 * 与 ldump.c / lundump.c 使用相同的保护配置
 * 提供: decrypt.loadfile(path) -> string, is_bytecode
 *       decrypt.dofile(path)   -> 加载并执行
 *       decrypt.load(path)     -> 加载为函数
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>

#include "lua.h"
#include "lauxlib.h"
#include "lualib.h"

/*
 * 与 ldump.c / lundump.c 共享同一份混淆实现。
 *
 * 早期这里各自维护一份 XOR 派生,只靠注释声明"与 lundump.c 一致" ——
 * 一旦字节码保护算法升级,本文件就会悄悄落后,产出与运行时对不上的密文。
 * 现在直接 include 同一个头文件,算法唯一。
 */
#include "lfprotect.h"

/* 加密文件魔数 */
#define DECRYPT_MAGIC       "\x1BLuaE"
#define DECRYPT_MAGIC_LEN   5

/* 加密文件头（小端序） */
typedef struct {
    char magic[DECRYPT_MAGIC_LEN];      /* 魔数 */
    uint8_t version;                     /* 版本号 */
    uint8_t flags;                       /* 加密标志 */
    uint32_t key_seed;                   /* 密钥种子 */
    uint32_t orig_size;                  /* 原始数据大小 */
    uint32_t padded_size;                /* 填充后大小 */
    uint8_t reserved[8];                 /* 保留 */
} __attribute__((packed)) EncryptHeader;

/*
 * 容器级加密的密钥流。
 *
 * 与字节码保护的差别:这里用**文件头里的 seed** 参与 counter,使同一份
 * 明文在不同构建里得到不同密文(字节码保护是"编译期固定密钥 + 位置",
 * 因为 dump 端必须能在无外部输入的情况下自解)。
 *
 * @param seed     文件头中的随机 seed
 * @param segment  段序号(数据段固定 0x3000)
 * @param out      输出 64 字节密钥流
 */
static void decrypt_prepare_keystream(uint32_t seed, uint32_t segment, uint8_t out[64]) {
    uint8_t master[32];
    lf_master_key(master);
    /* 把 seed 折进 counter:同 seed 的两次构建得到相同密钥流(可复现),
     * 不同 seed 完全不同。 */
    lf_chacha20_block(master, segment ^ (seed * 0x9E3779B9u), out);
    {
        volatile uint8_t *p = master;
        int i;
        for (i = 0; i < 32; i++) p[i] = 0;
    }
}

/* ============ 文件读取 ============ */

static uint8_t *read_file(const char *path, size_t *out_size) {
    FILE *fp = fopen(path, "rb");
    if (!fp) return NULL;

    fseek(fp, 0, SEEK_END);
    long size = ftell(fp);
    if (size < 0) {
        fclose(fp);
        return NULL;
    }
    fseek(fp, 0, SEEK_SET);

    uint8_t *data = (uint8_t *)malloc((size_t)size);
    if (!data) {
        fclose(fp);
        return NULL;
    }

    if (fread(data, 1, (size_t)size, fp) != (size_t)size) {
        free(data);
        fclose(fp);
        return NULL;
    }

    fclose(fp);
    *out_size = (size_t)size;
    return data;
}

/* ============ 解密核心 ============ */

static uint8_t *decrypt_buffer(const uint8_t *input, size_t input_size,
                               size_t *out_size, int *is_bytecode) {
    *out_size = 0;
    *is_bytecode = 0;

    /* 检查是否是加密文件 */
    if (input_size < sizeof(EncryptHeader) ||
        memcmp(input, DECRYPT_MAGIC, DECRYPT_MAGIC_LEN) != 0) {
        /* 不是加密文件，直接返回原始内容 */
        uint8_t *plain = (uint8_t *)malloc(input_size + 1);
        if (!plain) return NULL;
        memcpy(plain, input, input_size);
        plain[input_size] = '\0';
        *out_size = input_size;

        /* 检测是否是 Lua 字节码 */
        if (input_size >= 4 && plain[0] == '\x1b' &&
            plain[1] == 'L' && plain[2] == 'u' && plain[3] == 'a') {
            *is_bytecode = 1;
        }
        return plain;
    }

    /* 是加密文件 */
    const EncryptHeader *hdr = (const EncryptHeader *)input;

    /* 版本校验:算法升级后,旧密文用新代码解只会得到随机字节 ——
     * 若不在这里拦下,调用方看到的将是"加载字节码报 bad binary format",
     * 完全看不出真正原因是版本不匹配。 */
    if (hdr->version != LF_PROTECT_VERSION) {
        return NULL;
    }

    size_t data_offset = sizeof(EncryptHeader);
    size_t data_size = input_size - data_offset;

    if (data_size < hdr->orig_size) {
        return NULL; /* 文件损坏 */
    }
    /* orig_size 来自文件头(不可信输入)。orig_size == UINT32_MAX 时
     * `orig_size + 1` 在 size_t 上不会溢出(64 位),但在 32 位设备上会回绕成 0,
     * malloc(0) 返回的小块随后会被写穿 —— 必须显式拒绝。 */
    if (hdr->orig_size == (uint32_t) -1) {
        return NULL;
    }

    uint8_t *plain = (uint8_t *)malloc((size_t) hdr->orig_size + 1);
    if (!plain) return NULL;

    {
        uint8_t ks[64];
        size_t i;
        decrypt_prepare_keystream(hdr->key_seed, 0x3000u, ks);
        for (i = 0; i < hdr->orig_size; i++) {
            /* 密钥流按 64 字节块滚动:块内位置用 i & 63,
             * 块序号用 i / 64 —— 与写端完全对称。 */
            if (i > 0 && (i & 63u) == 0) {
                decrypt_prepare_keystream(hdr->key_seed, 0x3000u + (uint32_t) (i >> 6), ks);
            }
            plain[i] = lf_unprotect_byte(input[data_offset + i], i & 63u, ks);
        }
        {
            volatile uint8_t *vp = ks;
            int k;
            for (k = 0; k < 64; k++) vp[k] = 0;
        }
    }
    plain[hdr->orig_size] = '\0';
    *out_size = hdr->orig_size;

    /* 检测是否是 Lua 字节码 */
    if (hdr->orig_size >= 4 && plain[0] == '\x1b' &&
        plain[1] == 'L' && plain[2] == 'u' && plain[3] == 'a') {
        *is_bytecode = 1;
    }

    return plain;
}

/* ============ Lua 接口函数 ============ */

/*
 * decrypt.loadfile(path)
 * 读取文件，自动解密，返回 (content, is_bytecode)
 */
static int decrypt_loadfile(lua_State *L) {
    const char *path = luaL_checkstring(L, 1);

    size_t file_size = 0;
    uint8_t *file_data = read_file(path, &file_size);
    if (!file_data) {
        return luaL_error(L, "cannot open file: %s", path);
    }

    size_t plain_size = 0;
    int is_bytecode = 0;
    uint8_t *plain = decrypt_buffer(file_data, file_size, &plain_size, &is_bytecode);
    free(file_data);

    if (!plain) {
        return luaL_error(L, "decrypt failed: %s", path);
    }

    lua_pushlstring(L, (const char *)plain, plain_size);
    lua_pushboolean(L, is_bytecode);
    free(plain);
    return 2;
}

/*
 * decrypt.dofile(path)
 * 读取文件，解密，编译并执行
 */
static int decrypt_dofile(lua_State *L) {
    const char *path = luaL_checkstring(L, 1);

    /* 获取文件内容 */
    lua_pushcfunction(L, decrypt_loadfile);
    lua_pushstring(L, path);
    if (lua_pcall(L, 1, 2, 0) != LUA_OK) {
        return lua_error(L);
    }

    size_t len = 0;
    const char *data = lua_tolstring(L, -2, &len);
    int is_bytecode = lua_toboolean(L, -1);

    /* 编译 */
    const char *chunkname = lua_pushfstring(L, "@%s", path);
    int status;
    if (is_bytecode) {
        status = luaL_loadbuffer(L, data, len, chunkname);
    } else {
        status = luaL_loadstring(L, data);
    }
    lua_remove(L, -2); /* 移除 chunkname */
    lua_remove(L, -2); /* 移除 is_bytecode */
    lua_remove(L, -2); /* 移除 data */

    if (status != LUA_OK) {
        return lua_error(L);
    }

    /* 执行 */
    lua_call(L, 0, LUA_MULTRET);
    return lua_gettop(L);
}

/*
 * decrypt.load(path)
 * 读取文件，解密，编译为函数但不执行
 */
static int decrypt_load(lua_State *L) {
    const char *path = luaL_checkstring(L, 1);

    lua_pushcfunction(L, decrypt_loadfile);
    lua_pushstring(L, path);
    if (lua_pcall(L, 1, 2, 0) != LUA_OK) {
        return lua_error(L);
    }

    size_t len = 0;
    const char *data = lua_tolstring(L, -2, &len);
    int is_bytecode = lua_toboolean(L, -1);

    const char *chunkname = lua_pushfstring(L, "@%s", path);
    int status;
    if (is_bytecode) {
        status = luaL_loadbuffer(L, data, len, chunkname);
    } else {
        status = luaL_loadstring(L, data);
    }
    lua_remove(L, -2); /* chunkname */
    lua_remove(L, -2); /* is_bytecode */
    lua_remove(L, -2); /* data */

    if (status != LUA_OK) {
        return lua_error(L);
    }

    return 1; /* 返回函数 */
}

/*
 * decrypt.encrypt(data, seed)
 * 加密字符串，返回加密后的二进制数据
 */
static int decrypt_encrypt(lua_State *L) {
    size_t len = 0;
    const char *data = luaL_checklstring(L, 1, &len);
    uint32_t seed = (uint32_t)luaL_optinteger(L, 2, 0x12345678);

    EncryptHeader hdr;
    memset(&hdr, 0, sizeof(hdr));
    memcpy(hdr.magic, DECRYPT_MAGIC, DECRYPT_MAGIC_LEN);
    hdr.version = LF_PROTECT_VERSION;
    hdr.flags = LUA_PROTECT_FLAGS;
    hdr.key_seed = seed;
    hdr.orig_size = (uint32_t)len;
    hdr.padded_size = (uint32_t)len;

    size_t total = sizeof(hdr) + len;
    uint8_t *output = (uint8_t *)malloc(total);
    if (!output) {
        return luaL_error(L, "out of memory");
    }

    memcpy(output, &hdr, sizeof(hdr));
    {
        uint8_t ks[64];
        size_t i;
        decrypt_prepare_keystream(seed, 0x3000u, ks);
        for (i = 0; i < len; i++) {
            /* 块滚动与 decrypt_buffer 严格对称 */
            if (i > 0 && (i & 63u) == 0) {
                decrypt_prepare_keystream(seed, 0x3000u + (uint32_t) (i >> 6), ks);
            }
            output[sizeof(hdr) + i] = lf_protect_byte((uint8_t) data[i], i & 63u, ks);
        }
        {
            volatile uint8_t *vp = ks;
            int k;
            for (k = 0; k < 64; k++) vp[k] = 0;
        }
    }

    lua_pushlstring(L, (const char *)output, total);
    free(output);
    return 1;
}

/*
 * decrypt.decrypt(data)
 * 解密字符串，返回 (plain, is_bytecode)
 */
static int decrypt_decrypt(lua_State *L) {
    size_t len = 0;
    const char *data = luaL_checklstring(L, 1, &len);

    size_t out_size = 0;
    int is_bytecode = 0;
    uint8_t *plain = decrypt_buffer((const uint8_t *)data, len, &out_size, &is_bytecode);

    if (!plain) {
        return luaL_error(L, "decrypt failed");
    }

    lua_pushlstring(L, (const char *)plain, out_size);
    lua_pushboolean(L, is_bytecode);
    free(plain);
    return 2;
}

/* ============ 模块注册 ============ */

static const luaL_Reg decrypt_funcs[] = {
    {"loadfile", decrypt_loadfile},
    {"dofile",   decrypt_dofile},
    {"load",     decrypt_load},
    {"encrypt",  decrypt_encrypt},
    {"decrypt",  decrypt_decrypt},
    {NULL, NULL}
};

__attribute__((visibility("default"))) int luaopen_decrypt(lua_State *L) {
    luaL_newlib(L, decrypt_funcs);
    return 1;
}
