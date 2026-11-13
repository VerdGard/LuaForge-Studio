package com.luaforge.studio.payload

import androidx.annotation.Keep

/**
 * 载荷解密的 core 侧实现(与构建侧 `PayloadCrypto` 逐行对称)。
 *
 * ## 为什么 core 里也要有一份
 *
 * so 模式的解密在 native(`payloadstub.c`),dex 模式没有原生库可用 ——
 * 载荷就藏在 dex 的字符串常量里,只能由 Java 读出来解密。而 app 模块的
 * `PayloadCrypto` 不能出现在用户 APK 里(那是 IDE 自己的类),所以 core 侧
 * 必须自带一份。
 *
 * 两份实现对同一算法的漂移风险由两端的自检兜住:
 * - 构建期:`PayloadCrypto.selfTest()` 做加解密往返;
 * - 运行期:载荷带 CRC32(so 模式)与结构校验(dex 模式),解错会立刻失败而不是静默产出垃圾。
 *
 * ## 诚实边界
 *
 * 主密钥以字节常量形式存在于 dex 中,反编译即可提取 —— 这与 native 里的
 * 密钥强度等价(都要逆向才能拿到)。加密在此处的价值是防止"直接把 APK 当
 * zip 解开就拿到明文脚本"这类最低成本的窃取,而非抵御刻意逆向。
 */
@Keep
object PayloadDecryptor {

    /** 与 lfprotect.h 的 LUA_PROTECT_KEY_BYTES 完全相同。 */
    private val MASTER_SEED = intArrayOf(
        0x7B, 0x4B, 0x3A, 0x91, 0x08, 0xD7, 0x66, 0x2F,
        0xB5, 0x4C, 0x13, 0xE8, 0x71, 0x9A, 0x2D, 0xC3,
        0x5E, 0x87, 0x3B, 0xF0, 0x16, 0xA9, 0x4F, 0x62,
        0xC8, 0x05, 0x7D, 0x38, 0xE1, 0x5B, 0x94, 0x2A
    )

    private fun rotl(v: Int, n: Int): Int = (v shl n) or (v ushr (32 - n))

    /** ChaCha20 块函数;与 `lfprotect.h` 的 `lf_chacha20_block` 一致。 */
    private fun chacha20Block(key: ByteArray, counter: Int, out: ByteArray) {
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

    /** 与 `lf_master_key` 一致。 */
    private fun masterKey(): ByteArray {
        val k = ByteArray(32) { MASTER_SEED[it].toByte() }
        val block = ByteArray(64)
        chacha20Block(k, 0x4C465072, block)
        return block.copyOf(32)
    }

    /** 与 `lf_unprotect_byte` 一致。 */
    private fun unprotectByte(b: Byte, idx: Int, ks: ByteArray): Byte {
        val k = ks[idx and 63]
        val rot = ks[(idx + 17) and 63].toInt() and 7
        var v = (b.toInt() xor (idx and 0xFF)) and 0xFF
        if (rot != 0) {
            v = ((v ushr rot) or (v shl (8 - rot))) and 0xFF
        }
        v = v xor k.toInt()
        return v.toByte()
    }

    /**
     * 解密载荷。
     *
     * @param cipher  加密载荷
     * @param seed    构建期写入的种子
     * @param segment 段基(与构建侧一致)
     */
    @JvmStatic
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
}
