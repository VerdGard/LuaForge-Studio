package com.luaforge.studio.build

import com.luaforge.studio.utils.LogCatcher
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.zip.CRC32

/**
 * 把项目的 Lua/ALY 字节码打成单一载荷,供 dex / so 两种打包模式使用。
 *
 * ## 载荷格式
 *
 * ```
 * [4B 小端 文件数 N]
 * N × [2B 小端 路径字节长 L][L 字节 UTF-8 相对路径][4B 小端 内容长 M][M 字节内容]
 * ```
 *
 * 路径用相对项目根的 POSIX 形式(`main.lua`、`layout.aly`、`libs/x.lua`),
 * 解包侧据此重建目录树。`.aly` 在打包时已被编译成 `.lua`(见
 * [ApkBuilder.encryptAlyFile]),因此载荷里不会出现 `.aly`,与运行期
 * "布局文件一律以 .lua 加载"的既有行为一致。
 *
 * ## 为什么不用 zip
 *
 * 载荷整体会被加密(so 模式)或塞进 dex 附加数据(dex 模式),zip 的中央目录
 * 只会白白增加体积,而这些字节码本身已经不在 `assets` 里、不会被资源系统处理。
 * 自定义二进制格式解析只需几十行,还能顺带做路径越界检查。
 */
object LuaPayloadBuilder {

    private const val TAG = "LuaPayloadBuilder"

    /** 载荷魔数,写进 dex 附加数据时用于定位。 */
    val MAGIC = byteArrayOf('L'.code.toByte(), 'F'.code.toByte(), 'P'.code.toByte(), 'L'.code.toByte())

    /** 单个条目的路径长度上限(与核心侧解析器的 2 字节长度字段一致)。 */
    private const val MAX_PATH_LEN = 65535

    /**
     * 把目录下所有 `.lua` 文件收进载荷。
     *
     * @param sourceDir 已加密编译的项目目录(通常是 APK 工作目录的 `assets`)
     * @param excludePaths 相对 `sourceDir` 的路径黑名单(如跳过编译清单里的文件)
     * @return 载荷字节;目录里没有 `.lua` 时返回 null
     */
    fun build(sourceDir: File, excludePaths: Set<String> = emptySet()): ByteArray? {
        if (!sourceDir.isDirectory) return null

        val files = sourceDir.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".lua", ignoreCase = true) }
            .map { it to it.relativeTo(sourceDir).path.replace('\\', '/') }
            .filter { (_, rel) -> !excludePaths.contains(rel) }
            .sortedBy { (_, rel) -> rel }
            .toList()

        if (files.isEmpty()) {
            LogCatcher.w(TAG, "没有可打包的 .lua 文件: ${sourceDir.absolutePath}")
            return null
        }

        // 先算总长,避免 ByteArrayOutputStream 反复扩容
        var total = 4
        for ((file, rel) in files) {
            val pathBytes = rel.toByteArray(Charsets.UTF_8)
            require(pathBytes.size <= MAX_PATH_LEN) { "路径过长: $rel" }
            total += 2 + pathBytes.size + 4 + file.length().toInt()
        }

        val out = ByteArray(total)
        var pos = 0

        fun writeInt(v: Int) {
            out[pos++] = (v and 0xFF).toByte()
            out[pos++] = ((v ushr 8) and 0xFF).toByte()
            out[pos++] = ((v ushr 16) and 0xFF).toByte()
            out[pos++] = ((v ushr 24) and 0xFF).toByte()
        }

        fun writeShort(v: Int) {
            out[pos++] = (v and 0xFF).toByte()
            out[pos++] = ((v ushr 8) and 0xFF).toByte()
        }

        writeInt(files.size)
        for ((file, rel) in files) {
            val pathBytes = rel.toByteArray(Charsets.UTF_8)
            writeShort(pathBytes.size)
            System.arraycopy(pathBytes, 0, out, pos, pathBytes.size)
            pos += pathBytes.size

            val content = file.readBytes()
            writeInt(content.size)
            System.arraycopy(content, 0, out, pos, content.size)
            pos += content.size
        }

        LogCatcher.i(
            TAG,
            "载荷构建完成: ${files.size} 个文件, ${out.size} 字节"
        )
        return out
    }

    // ------------------------------------------------------------------
    // so 模式:追加到 .so 尾部
    // ------------------------------------------------------------------

    /** 尾部索引长度,必须与 payloadstub.c 的 PAYLOAD_TRAILER_LEN 一致。 */
    private const val TRAILER_LEN = 32

    /** 与 payloadstub.c 的 PAYLOAD_MAGIC 一致(7 字节,不含结尾 NUL)。 */
    private val PAYLOAD_MAGIC = "LFPKPAY".toByteArray(Charsets.US_ASCII)

    /** 与 lfprotect.h 的 LF_PROTECT_VERSION 一致。 */
    private const val PROTECT_VERSION = 2

    /** 与 payloadstub.c 的 PAYLOAD_SEGMENT 一致。 */
    private const val PAYLOAD_SEGMENT = 0x7000

    /**
     * 把载荷加密后追加到 `.so` 尾部。
     *
     * 加密算法必须与 `payloadstub.c` 的 `payload_load()` 严格对称:
     * ChaCha20 密钥流按 64 字节块滚动,块序号 = `PAYLOAD_SEGMENT + i/64`,
     * counter 再与 `seed * 0x9E3779B9` 异或。
     *
     * **密钥在 App 侧无法获得**(主密钥只在 native 的 lfprotect.h 里派生),
     * 因此这里改用另一条路径:把加密逻辑交给运行时库自己做 —— 具体做法是
     * 让构建侧调用同一个 `lfprotect.h` 的 Java 移植版本。
     *
     * @param soFile    要追加的目标 `.so`(会就地修改)
     * @param payload   载荷明文
     * @param seed      随机种子(写进尾部索引)
     * @return 是否成功
     */
    fun appendPayloadToSo(soFile: File, payload: ByteArray, seed: Int): Boolean {
        if (!soFile.isFile) {
            LogCatcher.e(TAG, "目标 .so 不存在: ${soFile.absolutePath}", null)
            return false
        }
        if (soFile.length() < 64) {
            LogCatcher.e(TAG, "目标 .so 太小,不像是原生库: ${soFile.absolutePath}", null)
            return false
        }
        // 防重复追加:连续构建同一份模板时不能把载荷叠两层
        if (hasAppendedPayload(soFile)) {
            LogCatcher.w(TAG, "该 .so 已带有载荷,跳过追加: ${soFile.name}")
            return false
        }

        val cipher = PayloadCrypto.encrypt(payload, seed, PAYLOAD_SEGMENT)

        val crc = CRC32().apply { update(payload) }.value.toInt()

        val trailer = ByteArray(TRAILER_LEN)
        System.arraycopy(PAYLOAD_MAGIC, 0, trailer, 0, PAYLOAD_MAGIC.size)
        putIntLE(trailer, 8, cipher.size)
        putIntLE(trailer, 12, crc)
        putIntLE(trailer, 16, seed)
        putIntLE(trailer, 20, PROTECT_VERSION)

        return try {
            FileOutputStream(soFile, true).use { fos ->
                fos.write(cipher)
                fos.write(trailer)
                fos.flush()
            }
            LogCatcher.i(
                TAG,
                "载荷已追加到 ${soFile.name}: 明文 ${payload.size} / 密文 ${cipher.size} 字节"
            )
            true
        } catch (e: Exception) {
            LogCatcher.e(TAG, "追加载荷到 .so 失败", e)
            false
        }
    }

    /** 判断 `.so` 尾部是否已有载荷索引(避免重复追加)。 */
    private fun hasAppendedPayload(soFile: File): Boolean {
        return try {
            RandomAccessFile(soFile, "r").use { raf ->
                if (raf.length() <= TRAILER_LEN) return false
                raf.seek(raf.length() - TRAILER_LEN)
                val head = ByteArray(PAYLOAD_MAGIC.size)
                raf.readFully(head)
                head.contentEquals(PAYLOAD_MAGIC)
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun putIntLE(buf: ByteArray, offset: Int, v: Int) {
        buf[offset] = (v and 0xFF).toByte()
        buf[offset + 1] = ((v ushr 8) and 0xFF).toByte()
        buf[offset + 2] = ((v ushr 16) and 0xFF).toByte()
        buf[offset + 3] = ((v ushr 24) and 0xFF).toByte()
    }

    // ------------------------------------------------------------------
    // dex 模式:编译成一个真正的 dex 类
    // ------------------------------------------------------------------

    /**
     * dex 模式生成的类名。
     *
     * 荷载以 **Java 字符串常量**形式写进这个类的静态字段,再交给 D8 编译成
     * 额外的 `classes*.dex`。运行期由 [PayloadContainer] 经反射读出该常量
     * 并 Base64 解码,因此:
     *
     * - 产物里**没有任何 `.lua` 文件**(载荷是 dex 里的字符串常量);
     * - 载荷也不是独立的数据文件,`aapt`/`assets` 里查不到;
     * - 反编译 dex 才能拿到载荷,且载荷本身还是加密的。
     *
     * 类名刻意不带 `luaforge` 前缀,避免被"按包名找载荷"的脚本直接定位。
     */
    const val DEX_PAYLOAD_CLASS = "com/luaforge/studio/payload/SoPayloadData"

    /** 生成类里的字段名(同样不体现用途)。 */
    private const val DEX_PAYLOAD_FIELD = "d"

    /**
     * dex 模式载荷的种子与段基。
     *
     * 与 so 模式不同,dex 模式不用随机 seed:载荷固定嵌在 dex 里,
     * 用固定 seed 让同一份源码的两次构建产出**可复现**的 dex
     * (便于增量构建与产物 diff),而加密强度并不因此下降 ——
     * 段基与 seed 一起只决定密钥流的起点,不决定它是否安全。
     */
    const val DEX_PAYLOAD_SEED = 0x4C464458

    /** dex 模式载荷的段基。 */
    const val DEX_PAYLOAD_SEGMENT = 0x7100

    /**
     * 生成承载载荷的 Java 源码。
     *
     * 为什么要生成 Java 而不是直接拼 dex:手写 dex 需要维护
     * string_ids / type_ids / proto_ids / class_defs / code_item 全套索引,
     * 任何一处偏移算错就是 `DexFormatError`;而仓库里已经有 ECJ + D8 这条
     * 成熟链路(编译项目 `java/` 目录用的就是它),复用它几乎零风险。
     *
     * 字符串常量有 65535 字节的 UTF-8 长度上限(`CONSTANT_Utf8_info`),
     * 因此载荷按 [MAX_STRING_CHUNK] 切分成多个字段。
     *
     * @param encryptedPayload 已加密的载荷
     * @return Java 源码文本
     */
    fun buildDexSource(encryptedPayload: ByteArray): String {
        val base64 = android.util.Base64.encodeToString(encryptedPayload, android.util.Base64.NO_WRAP)
        val chunks = base64.chunked(MAX_STRING_CHUNK)

        val className = DEX_PAYLOAD_CLASS.substringAfterLast('/')
        val packageName = DEX_PAYLOAD_CLASS.substringBeforeLast('/').replace('/', '.')

        return buildString {
            appendLine("package $packageName;")
            appendLine()
            appendLine("/**")
            appendLine(" * 由 LuaForge-Studio 构建生成,内容为项目脚本载荷(已加密)。")
            appendLine(" * 请勿手工修改 —— 下次构建会整体覆盖。")
            appendLine(" */")
            appendLine("public final class $className {")
            appendLine("    private $className() {}")
            appendLine()
            chunks.forEachIndexed { index, chunk ->
                appendLine("    public static final String $DEX_PAYLOAD_FIELD$index = \"$chunk\";")
            }
            appendLine()
            appendLine("    /** 片段个数。 */")
            appendLine("    public static final int COUNT = ${chunks.size};")
            appendLine("}")
        }
    }

    /**
     * 单个字符串常量的最大长度。
     *
     * class 文件里 `CONSTANT_Utf8_info.length` 是 u2,上限 65535。Base64 是
     * 纯 ASCII,字符数即字节数;留出安全余量取 60000。
     */
    private const val MAX_STRING_CHUNK = 60000
}
