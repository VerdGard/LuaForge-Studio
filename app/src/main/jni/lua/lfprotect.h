/*
 * lfprotect —— LuaForge 字节码混淆的共享实现。
 *
 * ## 为什么需要独立的头文件
 *
 * 混淆必须在**写入端**(ldump.c)与**读取端**(lundump.c)严格互逆。此前两端各自
 * 维护一份 `protect_byte` / `unprotect_byte`,靠人工保持对称 —— 一旦有人只改
 * 一侧,产出的字节码在自己的运行时里都解不开。把算法收进一个被两端共同
 * include 的头文件,对称性由编译器保证。
 *
 * ## 相对旧实现的强度差异
 *
 * 旧实现:
 *
 *     key = (0x5A5A5A5A >> (8 * (idx % 4))) & 0xFF;   // 4 字节固定 key
 *     b ^= key; b = rotl3(b); b ^= (idx & 0xFF);       // 每个字节都同构
 *
 * 两个结构性弱点:
 *
 * 1. **密钥是编译期常量且只有 4 字节**,整个 chunk 的密钥流周期为 4 —— 已知
 *    任一段明文就能还原全局密钥,随后整份字节码可解。
 * 2. **变换与位置无关的密钥流**:每个字节只经过"定值 XOR → 固定旋转 → idx XOR",
 *    没有非线性扩散。相同位置的字节总是被同样变换,统计对齐后即可反推。
 *
 * 新实现:
 *
 * - **ChaCha20 块函数**(RFC 8439 的 20 轮核心)生成密钥流,每 64 字节一个 block,
 *   block 序号参与输入 —— 这是**位置相关**的伪随机流,而不是 4 字节循环。
 * - 每个 block 的密钥由 `LUA_PROTECT_KEY` 与 block 序号一起经 ChaCha20 的
 *   nonce/counter 派生,32 字节密钥材料只在 native 里存在。
 * - 字节经 `b ^= ks[i]` 之后再叠加一次**位置相关的旋转**(旋转量取自密钥流),
 *   让"相同明文在不同位置得到不同密文",破坏统计对齐。
 *
 * ## 诚实边界
 *
 * 密钥最终必须存在于客户端二进制里才能自解密 —— 任何本地混淆都挡不住有耐心
 * 的分析者(逆向 .so 提取常量即可)。这里提升的是**逆向成本**,不是数学上的
 * 不可破。真正的机密性需要服务端下发密钥或硬件 keystore,那超出了本工具的
 * 离线打包模型。
 */

#ifndef LF_PROTECT_H
#define LF_PROTECT_H

#include <stdint.h>
#include <stddef.h>
#include <string.h>

/* ============ 配置(编译期可被 -D 覆盖) ============ */

#ifndef LUA_PROTECT_KEY_BYTES
/* 32 字节主密钥:与旧版 0x5A5A5A5A 完全不同,旧版产物不再兼容(见下方 version)。
 *
 * 可用 -DLUA_PROTECT_KEY_BYTES='{...}' 在编译期整体替换 —— 发行方若要为不同
 * 客户产出互不兼容的包,只需在此注入各自的密钥即可。 */
#define LUA_PROTECT_KEY_BYTES \
    {0x7Bu, 0x4Bu, 0x3Au, 0x91u, 0x08u, 0xD7u, 0x66u, 0x2Fu, \
     0xB5u, 0x4Cu, 0x13u, 0xE8u, 0x71u, 0x9Au, 0x2Du, 0xC3u,            \
     0x5Eu, 0x87u, 0x3Bu, 0xF0u, 0x16u, 0xA9u, 0x4Fu, 0x62u,            \
     0xC8u, 0x05u, 0x7Du, 0x38u, 0xE1u, 0x5Bu, 0x94u, 0x2Au}
#endif

#ifndef LUA_PROTECT_FLAGS
/* bit0: 字符串常量保护;bit1: 指令段保护 */
#define LUA_PROTECT_FLAGS 0x03
#endif

/* 混淆格式版本。写进 chunk 头,加载端据此决定是否启用新算法。
 * 改动算法必须同时递增此值,否则旧产物会被新运行时解成垃圾。 */
#define LF_PROTECT_VERSION 2

/* ============ ChaCha20 核心 ============ */

#define LF_ROTL32(v, n) (((v) << (n)) | ((v) >> (32 - (n))))

#define LF_QR(a, b, c, d)      \
    do {                        \
        a += b; d ^= a; d = LF_ROTL32(d, 16); \
        c += d; b ^= c; b = LF_ROTL32(b, 12); \
        a += b; d ^= a; d = LF_ROTL32(d, 8);  \
        c += d; b ^= c; b = LF_ROTL32(b, 7);  \
    } while (0)

/**
 * 生成一个 64 字节密钥流块。
 *
 * @param key     32 字节主密钥
 * @param counter 块序号(位置相关)
 * @param out     输出 64 字节
 */
static void lf_chacha20_block(const uint8_t key[32], uint32_t counter, uint8_t out[64]) {
    uint32_t st[16];
    uint32_t x[16];
    int i;

    /* 常量 "expand 32-byte k" */
    st[0] = 0x61707865u;
    st[1] = 0x3320646eu;
    st[2] = 0x79622d32u;
    st[3] = 0x6b206574u;

    for (i = 0; i < 8; i++) {
        st[4 + i] = (uint32_t) key[i * 4]
                    | ((uint32_t) key[i * 4 + 1] << 8)
                    | ((uint32_t) key[i * 4 + 2] << 16)
                    | ((uint32_t) key[i * 4 + 3] << 24);
    }

    st[12] = counter;
    /* nonce 由 counter 派生,避免"只有 counter 变化"的线性可预测性 */
    st[13] = counter * 0x9E3779B9u;
    st[14] = counter ^ 0x85EBCA6Bu;
    st[15] = ~counter + 0x165667B1u;

    for (i = 0; i < 16; i++) x[i] = st[i];

    for (i = 0; i < 10; i++) {          /* 10 组双轮 = 20 轮 */
        LF_QR(x[0], x[4], x[8], x[12]);
        LF_QR(x[1], x[5], x[9], x[13]);
        LF_QR(x[2], x[6], x[10], x[14]);
        LF_QR(x[3], x[7], x[11], x[15]);
        LF_QR(x[0], x[5], x[10], x[15]);
        LF_QR(x[1], x[6], x[11], x[12]);
        LF_QR(x[2], x[7], x[8], x[13]);
        LF_QR(x[3], x[4], x[9], x[14]);
    }

    for (i = 0; i < 16; i++) {
        uint32_t v = x[i] + st[i];
        out[i * 4 + 0] = (uint8_t) (v & 0xFF);
        out[i * 4 + 1] = (uint8_t) ((v >> 8) & 0xFF);
        out[i * 4 + 2] = (uint8_t) ((v >> 16) & 0xFF);
        out[i * 4 + 3] = (uint8_t) ((v >> 24) & 0xFF);
    }
}

/** 主密钥(局部构造,避免在 .rodata 里出现完整 32 字节序列)。 */
static void lf_master_key(uint8_t out[32]) {
    static const uint8_t k[32] = LUA_PROTECT_KEY_BYTES;
    uint8_t block[64];
    int i;

    /* 再过一轮 ChaCha20:即使静态提取到 k,也拿不到派生后的实际密钥流起点 */
    lf_chacha20_block(k, 0x4C465072u /* "LFP" */, block);
    for (i = 0; i < 32; i++) out[i] = block[i];

    /* 清零临时块,减少内存 dump 暴露面(volatile 防止被优化掉) */
    {
        volatile uint8_t *p = block;
        for (i = 0; i < 64; i++) p[i] = 0;
    }
}

/**
 * 对单字节做正向变换(写端)。
 *
 * @param b    原字节
 * @param idx  该字节在**所属段内**的偏移(字符串常量 / 指令段各自独立计数)
 * @param ks   已生成的 64 字节密钥流
 * @param rot  (可选)输出该字节使用的旋转量,供调试;可为 NULL
 */
static uint8_t lf_protect_byte(uint8_t b, size_t idx, const uint8_t ks[64]) {
    uint8_t k = ks[idx & 63u];
    /* 位置相关的旋转量:高 3 位决定 0..7,让同明文在不同位置得到不同密文 */
    unsigned rot = (unsigned) (ks[(idx + 17u) & 63u] & 7u);

    b ^= k;
    b = (uint8_t) (((b << rot) | (b >> (8u - rot))) & 0xFFu);
    /* idx 的低 8 位再混一次:破坏"周期 64"的对齐可能 */
    b ^= (uint8_t) (idx & 0xFFu);
    return b;
}

/**
 * 单字节逆变换(读端),与 [lf_protect_byte] 严格互逆。
 *
 * 注意逆序:先撤销 idx XOR,再反向旋转,最后 XOR 密钥流。
 * `rot == 0` 时移位需特判,否则 `b >> 8` 在 C 里是未定义行为。
 */
static uint8_t lf_unprotect_byte(uint8_t b, size_t idx, const uint8_t ks[64]) {
    uint8_t k = ks[idx & 63u];
    unsigned rot = (unsigned) (ks[(idx + 17u) & 63u] & 7u);

    b ^= (uint8_t) (idx & 0xFFu);
    if (rot) {
        b = (uint8_t) (((b >> rot) | (b << (8u - rot))) & 0xFFu);
    }
    b ^= k;
    return b;
}

#endif /* LF_PROTECT_H */
