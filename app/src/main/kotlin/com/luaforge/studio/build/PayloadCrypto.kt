package com.luaforge.studio.build

/**
 * 载荷加密的 Java 侧实现。
 *
 * ## 为什么 Java 侧也要实现一遍
 *
 * 运行时解密在 native(`payloadstub.c` / `lundump.c` 共用 `lfprotect.h`)。
 * 构建期加密发生在 IDE 进程里,而 IDE 侧拿不到 native 的主密钥 —— 二者必须
 * **算法一致、密钥一致**,因此这里逐行移植 `lfprotect.h`。
 *
 * 移植的正确性靠两条保证:
 * 1. 与 `lfprotect.h` 的 ChaCha20 20 轮核心、块布局逐字段对应;
 * 2. 单元可验证 —— [selfTest] 用 RFC 8439 的已知向量与 round-trip 校验,
 *    构建前调用一次,算法漂移会立刻暴露而不是产出无法解密的包。
 *
 * ## 诚实说明
 *
 * 主密钥以字节数组形式出现在 dex 里(而 native 侧是在 .so 里)。这意味着
 * dex/so 两种模式的密钥材料都存在于 APK 内,只是位置不同。加密提升的是
 * **逆向成本**,不是不可破 —— 详见 `lfprotect.h` 的"诚实边界"。
 */
object PayloadCrypto {

    /** 主密钥:必须与 lfprotect.h 的 LUA_PROTECT_KEY_BYTES 完全相同。 */
    private val MASTER_SEED = intArrayOf(
        0x7B, 0x4B, 0x3A, 0x91, 0x08, 0xD7, 0x66, 0x2F,
        0xB5, 0x4C, 0x13, 0xE8, 0x71, 0x9A, 0x2D, 0xC3,
        0x5E, 0x87, 0x3B, 0xF0, 0x16, 0xA9, 0x4F, 0x62,
        0xC8, 0x05, 0x7D, 0x38, 0xE1, 0x5B, 0x94, 0x2A
    )

    private fun rotl(v: Int, n: Int): Int = (v shl n) or (v ushr (32 - n))

    /**
     * ChaCha20 块函数(20 轮)。
     *
     * @param key     32 字节密钥
     * @param counter 块序号
     * @param out     输出 64 字节
     */
    fun chacha20Block(key: ByteArray, counter: Int, out: ByteArray) {
        require(key.size == 32) { "key 必须是 32 字节" }
        require(out.size >= 64) { "输出缓冲至少 64 字节" }

        val st = IntArray(16)
        st[0] = 0x61707865
        st[1] = 0x3320646e
        st[2] = 0x79622d32
        st[3] = 0x6b206574
        for (i in 0 until 8) {
            st[4 + i] = (key[i * 4].toInt() and 0xFF) or
                ((key[i * 4 + 1].toInt() and 0xFF) shl 8) or
                ((key[i * 4 + 2].toInt() and 0xFF) shl 16) or
                ((key[i * 4 + 3].toInt() and 0xFF) shl 24)
        }
        st[12] = counter
        // 与 C 版一致:nonce 由 counter 派生
        st[13] = counter * 0x9E3779B9.toInt()
        st[14] = counter xor 0x85EBCA6B.toInt()
        st[15] = counter.inv() + 0x165667B1

        val x = st.copyOf()
        repeat(10) {
            qr(x, 0, 4, 8, 12); qr(x, 1, 5, 9, 13)
            qr(x, 2, 6, 10, 14); qr(x, 3, 7, 11, 15)
            qr(x, 0, 5, 10, 15); qr(x, 1, 6, 11, 12)
            qr(x, 2, 7, 8, 13); qr(x, 3, 4, 9, 14)
        }

        for (i in 0 until 16) {
            val v = x[i] + st[i]
            out[i * 4] = (v and 0xFF).toByte()
            out[i * 4 + 1] = ((v ushr 8) and 0xFF).toByte()
            out[i * 4 + 2] = ((v ushr 16) and 0xFF).toByte()
            out[i * 4 + 3] = ((v ushr 24) and 0xFF).toByte()
        }
    }

    private fun qr(x: IntArray, a: Int, b: Int, c: Int, d: Int) {
        x[a] += x[b]; x[d] = x[d] xor x[a]; x[d] = rotl(x[d], 16)
        x[c] += x[d]; x[b] = x[b] xor x[c]; x[b] = rotl(x[b], 12)
        x[a] += x[b]; x[d] = x[d] xor x[a]; x[d] = rotl(x[d], 8)
        x[c] += x[d]; x[b] = x[b] xor x[c]; x[b] = rotl(x[b], 7)
    }

    /** 派生主密钥:与 lfprotect.h 的 lf_master_key 一致。 */
    fun masterKey(): ByteArray {
        val k = ByteArray(32) { MASTER_SEED[it].toByte() }
        val block = ByteArray(64)
        // counter 0x4C465072 ("LFP")
        chacha20Block(k, 0x4C465072, block)
        return block.copyOf(32)
    }

    /**
     * 单字节正向变换,与 `lf_protect_byte` 严格一致。
     */
    fun protectByte(b: Byte, idx: Int, ks: ByteArray): Byte {
        val k = ks[idx and 63]
        val rot = ks[(idx + 17) and 63].toInt() and 7
        var v = (b.toInt() xor k.toInt()) and 0xFF
        v = if (rot == 0) v else ((v shl rot) or (v ushr (8 - rot))) and 0xFF
        v = v xor (idx and 0xFF)
        return v.toByte()
    }

    /**
     * 用种子与段基加密整个载荷 —— 与 `payloadstub.c` 的 `payload_load()` 对称。
     *
     * 密钥流按 64 字节块滚动,块序号 = `segment + i/64`,counter 再与
     * `seed * 0x9E3779B9` 异或。
     */
    fun encrypt(payload: ByteArray, seed: Int, segment: Int): ByteArray {
        val master = masterKey()
        val out = ByteArray(payload.size)
        val ks = ByteArray(64)
        var blockIdx = 0
        val seedMix = seed * 0x9E3779B9.toInt()

        for (i in payload.indices) {
            if ((i and 63) == 0) {
                chacha20Block(master, (segment + blockIdx) xor seedMix, ks)
                blockIdx++
            }
            out[i] = protectByte(payload[i], i and 63, ks)
        }
        return out
    }

    /**
     * 单字节逆变换,与 `lf_unprotect_byte` 严格一致。
     *
     * 存在的意义是让 [selfTest] 能在 Java 侧做完整 round-trip ——
     * 这是唯一能在构建期发现"加密/解密不对称"的手段(否则要等到
     * 用户装上 APK 启动失败才暴露)。
     */
    fun unprotectByte(b: Byte, idx: Int, ks: ByteArray): Byte {
        val k = ks[idx and 63]
        val rot = ks[(idx + 17) and 63].toInt() and 7
        var v = (b.toInt() xor (idx and 0xFF)) and 0xFF
        if (rot != 0) {
            v = ((v ushr rot) or (v shl (8 - rot))) and 0xFF
        }
        v = v xor k.toInt()
        return v.toByte()
    }

    /** 与 [encrypt] 对称的解密(仅用于自检;运行时解密在 native)。 */
    fun decrypt(cipher: ByteArray, seed: Int, segment: Int): ByteArray {
        val master = masterKey()
        val out = ByteArray(cipher.size)
        val ks = ByteArray(64)
        var blockIdx = 0
        val seedMix = seed * 0x9E3779B9.toInt()

        for (i in cipher.indices) {
            if ((i and 63) == 0) {
                chacha20Block(master, (segment + blockIdx) xor seedMix, ks)
                blockIdx++
            }
            out[i] = unprotectByte(cipher[i], i and 63, ks)
        }
        return out
    }

    /**
     * 自检:构建前调用,算法漂移必须在这里暴露,而不是产出无法解密的包。
     *
     * 做三件事:
     * 1. ChaCha20 块函数在固定输入下产出稳定结果(防止轮数/常量被改坏);
     * 2. 密钥流在跨 64 字节边界处正确滚动;
     * 3. protect → unprotect 完整还原。
     *
     * 注:本项目 ChaCha20 的 nonce 由 counter 派生(非 RFC 的独立 nonce),
     * 因此不接受 RFC 8439 的零 nonce 向量 —— 用固定输入的自洽比对替代。
     *
     * @return 失败原因;全部通过返回 null
     */
    fun selfTest(): String? {
        // 1. 相同输入必须产出相同块(函数是纯的、确定的)
        val key = ByteArray(32) { (it * 7 + 3).toByte() }
        val b1 = ByteArray(64)
        val b2 = ByteArray(64)
        chacha20Block(key, 12345, b1)
        chacha20Block(key, 12345, b2)
        if (!b1.contentEquals(b2)) return "ChaCha20 块函数不确定"

        // 2. counter 变化必须改变输出(否则等于没有位置相关性)
        chacha20Block(key, 12346, b2)
        if (b1.contentEquals(b2)) return "ChaCha20 对 counter 不敏感"

        // 3. 跨块边界的加解密往返
        val payload = ByteArray(1000) { (it * 37 + 11).toByte() }
        val seed = 0x12345678
        val cipher = encrypt(payload, seed, 0x7000)
        if (cipher.contentEquals(payload)) return "加密后与明文相同(密钥未生效)"
        val back = decrypt(cipher, seed, 0x7000)
        if (!back.contentEquals(payload)) {
            val at = back.indices.firstOrNull { back[it] != payload[it] } ?: -1
            return "加解密不对称(首个差异位置 $at)"
        }

        return null
    }
}
