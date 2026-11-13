/*
 * libluaforge_payload.so —— so 打包模式的载荷容器。
 *
 * ## 思路
 *
 * 打包时把项目的 Lua 字节码载荷**追加到本库文件尾部**,运行时由本库自己
 * 把尾部读回来解密。这样构建产物里既没有 `.lua` 文件、也没有独立的数据文件,
 * 载荷与原生库是同一条 ELF 记录,APK 签名同时覆盖两者 —— 单独替换其中一半
 * 会导致签名校验失败。
 *
 * 文件布局:
 *
 *     [ ELF 内容(正常 .so) ][ 加密载荷 ][ 尾部索引 32 字节 ]
 *
 * 尾部索引(小端):
 *
 *     0..7   魔数 "LFPKPAY\0"
 *     8..11  载荷长度(不含索引)
 *     12..15 载荷 CRC32(装载前校验完整性)
 *     16..19 seed(载荷密钥流用)
 *     20..23 格式版本
 *     24..31 保留(置 0)
 *
 * ## 为什么追加在尾部而不是塞进某个 section
 *
 * 追加不改变 ELF 结构,加载器按 program header 装载时会忽略文件尾的多余字节,
 * 因此 `.so` 仍然可以直接 `dlopen`。若改成往 section 里塞数据,就必须改
 * section header / program header,那会把"打包"变成"重写 ELF",风险与工作量
 * 都不成比例。
 *
 * ## 密钥
 *
 * 与字节码保护共用 `lfprotect.h` 的主密钥(在 native 内派生),Java 侧
 * 只拿到解密后的字节,拿不到密钥。
 */

#include <jni.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <dlfcn.h>
#include <link.h>

#include "lfprotect.h"

/* 尾部索引 */
#define PAYLOAD_MAGIC       "LFPKPAY"
#define PAYLOAD_MAGIC_LEN   7
#define PAYLOAD_TRAILER_LEN 32

/* 载荷段专用 counter 基,与构建侧保持一致 */
#define PAYLOAD_SEGMENT     0x7000u

/* 尾部索引字段偏移 */
#define OFF_MAGIC    0
#define OFF_LENGTH   8
#define OFF_CRC      12
#define OFF_SEED     16
#define OFF_VERSION  20

/* ------------------------------------------------------------------ */
/* CRC32                                                               */
/* ------------------------------------------------------------------ */

/*
 * 逐位实现,不用查表:载荷只在启动时校验一次,几十 KB 的逐位 CRC 开销
 * 完全可以忽略,而省下的 1KB 表对 `.so` 体积是净收益。
 */
static uint32_t payload_crc32(const uint8_t *data, size_t len) {
    uint32_t crc = 0xFFFFFFFFu;
    size_t i;
    int b;
    for (i = 0; i < len; i++) {
        crc ^= data[i];
        for (b = 0; b < 8; b++) {
            uint32_t mask = (uint32_t) (-(int32_t) (crc & 1u));
            crc = (crc >> 1) ^ (0xEDB88320u & mask);
        }
    }
    return ~crc;
}

/* ------------------------------------------------------------------ */
/* 定位自身 .so 文件                                                   */
/* ------------------------------------------------------------------ */

/* JNI_OnLoad 的地址被 dladdr 用来反查自身路径,但它的定义在文件末尾。
 * C 语言要求先声明后使用,否则在 C99 下是隐式声明(返回值类型错)或被
 * 当成未定义标识符 —— 两者都是编译期错误。 */
JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved);

/*
 * 取本库在磁盘上的真实路径。
 *
 * 用 `dladdr` 拿 `JNI_OnLoad` 的地址再反查文件名,而不是读
 * `/proc/self/maps` 或拼 `nativeLibraryDir`:前者在部分 ROM 上 maps 格式
 * 不稳,后者在 `extractNativeLibs=false` 时拿到的是不存在的路径。
 * `dladdr` 是 POSIX 标准手段,且天然拿到的是加载器实际打开的那个文件。
 */
static int payload_self_path(char *out, size_t out_size) {
    Dl_info info;
    if (dladdr((void *) (intptr_t) JNI_OnLoad, &info) == 0 || info.dli_fname == NULL) {
        return -1;
    }
    if (strlen(info.dli_fname) >= out_size) return -2;
    strncpy(out, info.dli_fname, out_size - 1);
    out[out_size - 1] = '\0';
    return 0;
}

/* ------------------------------------------------------------------ */
/* 读取并解密载荷                                                      */
/* ------------------------------------------------------------------ */

/*
 * @return 解密后的载荷(malloc 分配,调用方 free);失败返回 NULL 并写 *err
 */
static uint8_t *payload_load(size_t *out_len, const char **err) {
    char path[4096];
    if (payload_self_path(path, sizeof(path)) != 0) {
        *err = "cannot locate own .so";
        return NULL;
    }

    FILE *fp = fopen(path, "rb");
    if (fp == NULL) {
        *err = "cannot open own .so";
        return NULL;
    }

    if (fseek(fp, 0, SEEK_END) != 0) {
        fclose(fp);
        *err = "seek failed";
        return NULL;
    }
    long file_size = ftell(fp);
    if (file_size <= PAYLOAD_TRAILER_LEN) {
        fclose(fp);
        *err = "no payload appended";
        return NULL;
    }

    /* 读尾部索引 */
    uint8_t trailer[PAYLOAD_TRAILER_LEN];
    if (fseek(fp, file_size - PAYLOAD_TRAILER_LEN, SEEK_SET) != 0 ||
        fread(trailer, 1, PAYLOAD_TRAILER_LEN, fp) != PAYLOAD_TRAILER_LEN) {
        fclose(fp);
        *err = "cannot read trailer";
        return NULL;
    }

    if (memcmp(trailer + OFF_MAGIC, PAYLOAD_MAGIC, PAYLOAD_MAGIC_LEN) != 0) {
        fclose(fp);
        *err = "payload magic missing";
        return NULL;
    }

    uint32_t payload_len =
            (uint32_t) trailer[OFF_LENGTH]
            | ((uint32_t) trailer[OFF_LENGTH + 1] << 8)
            | ((uint32_t) trailer[OFF_LENGTH + 2] << 16)
            | ((uint32_t) trailer[OFF_LENGTH + 3] << 24);
    uint32_t expect_crc =
            (uint32_t) trailer[OFF_CRC]
            | ((uint32_t) trailer[OFF_CRC + 1] << 8)
            | ((uint32_t) trailer[OFF_CRC + 2] << 16)
            | ((uint32_t) trailer[OFF_CRC + 3] << 24);
    uint32_t seed =
            (uint32_t) trailer[OFF_SEED]
            | ((uint32_t) trailer[OFF_SEED + 1] << 8)
            | ((uint32_t) trailer[OFF_SEED + 2] << 16)
            | ((uint32_t) trailer[OFF_SEED + 3] << 24);

    if (payload_len == 0 ||
        (long) payload_len > file_size - PAYLOAD_TRAILER_LEN) {
        fclose(fp);
        *err = "payload length invalid";
        return NULL;
    }

    uint8_t *cipher = (uint8_t *) malloc(payload_len);
    uint8_t *plain = (uint8_t *) malloc(payload_len + 1);
    if (cipher == NULL || plain == NULL) {
        free(cipher);
        free(plain);
        fclose(fp);
        *err = "out of memory";
        return NULL;
    }

    if (fseek(fp, file_size - PAYLOAD_TRAILER_LEN - (long) payload_len, SEEK_SET) != 0 ||
        fread(cipher, 1, payload_len, fp) != payload_len) {
        free(cipher);
        free(plain);
        fclose(fp);
        *err = "cannot read payload";
        return NULL;
    }
    fclose(fp);

    /* 解密:与构建侧 luaforge_payload_appender 的块滚动严格对称 */
    {
        uint8_t master[32];
        uint8_t ks[64];
        uint32_t i;
        lf_master_key(master);
        for (i = 0; i < payload_len; i++) {
            if ((i & 63u) == 0) {
                /* 块序号 = 段基 + 块下标;seed 参与 counter 使不同构建互不相同 */
                uint32_t block = PAYLOAD_SEGMENT + (i >> 6);
                lf_chacha20_block(master, block ^ (seed * 0x9E3779B9u), ks);
            }
            plain[i] = lf_unprotect_byte(cipher[i], i & 63u, ks);
        }
        {
            volatile uint8_t *vp = master;
            int k;
            for (k = 0; k < 32; k++) vp[k] = 0;
            vp = ks;
            for (k = 0; k < 64; k++) vp[k] = 0;
        }
    }
    free(cipher);

    if (payload_crc32(plain, payload_len) != expect_crc) {
        free(plain);
        *err = "payload checksum mismatch";
        return NULL;
    }

    plain[payload_len] = '\0';
    *out_len = payload_len;
    return plain;
}

/* ------------------------------------------------------------------ */
/* JNI 导出                                                            */
/* ------------------------------------------------------------------ */

/**
 * 读取本库尾部内嵌的载荷。
 *
 * @return 解密后的字节数组;本库没有被追加载荷时返回 null
 */
JNIEXPORT jbyteArray JNICALL
Java_com_luaforge_studio_payload_PayloadContainer_nativeReadPayload(
        JNIEnv *env, jclass clazz) {
    (void) clazz;

    size_t len = 0;
    const char *err = NULL;
    uint8_t *data = payload_load(&len, &err);
    if (data == NULL) {
        return NULL;
    }

    jbyteArray result = (*env)->NewByteArray(env, (jsize) len);
    if (result != NULL) {
        (*env)->SetByteArrayRegion(env, result, 0, (jsize) len, (const jbyte *) data);
    }
    /* 明文不在 native 侧留存 */
    {
        volatile uint8_t *p = data;
        size_t i;
        for (i = 0; i < len; i++) p[i] = 0;
    }
    free(data);
    return result;
}

/** 本库是否内嵌了载荷(用于运行时诊断与模式判定)。 */
JNIEXPORT jboolean JNICALL
Java_com_luaforge_studio_payload_PayloadContainer_nativeHasPayload(
        JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    size_t len = 0;
    const char *err = NULL;
    uint8_t *data = payload_load(&len, &err);
    if (data == NULL) return JNI_FALSE;
    {
        volatile uint8_t *p = data;
        size_t i;
        for (i = 0; i < len; i++) p[i] = 0;
    }
    free(data);
    return JNI_TRUE;
}

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void) vm;
    (void) reserved;
    return JNI_VERSION_1_6;
}
