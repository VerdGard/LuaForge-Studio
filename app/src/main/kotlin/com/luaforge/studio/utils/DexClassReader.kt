package com.luaforge.studio.utils

import java.io.File
import java.util.zip.ZipFile

/**
 * 从 dex / jar 中读出类名,不构造 [dalvik.system.DexFile]。
 *
 * `DexFile(String)` 与 `entries()` 在 API 34+ 已弃用(且对只读类名这种场景
 * 会触发 dexopt 等不必要的副作用)。这里直接解析 dex 的字符串池,
 * 取出形如 `Lcom/foo/Bar;` 的类描述符转成 `com.foo.Bar`。
 *
 * 只做字符串池扫描,因此**不保证类真实存在于 class_defs**(字符串池里
 * 也可能出现方法签名中的类型描述符)。对补全/包结构构建这类用途足够,
 * 若需要严格的"已定义类"列表则应改用 class_defs 解析。
 */
object DexClassReader {

    /** dex 魔数前三字节: 'd' 'e' 'x'。 */
    private const val MAGIC_D = 'd'
    private const val MAGIC_E = 'e'
    private const val MAGIC_X = 'x'

    /** 单个字符串长度上限,防止畸形文件导致超大分配。 */
    private const val MAX_STRING_LEN = 512

    /**
     * 从文件读取类名。`.dex` 直接解析;`.jar`/`.zip`/`.apk` 展开后逐个解析内层 dex。
     *
     * @return 类名集合(全限定名,`.` 分隔);读取失败返回空集合
     */
    @JvmStatic
    fun readClasses(file: File): List<String> {
        return try {
            when {
                file.name.endsWith(".dex") -> dexClassNames(file.readBytes())
                // .apk 同样是 zip 容器,内含 classes*.dex(即 packageCodePath 的形态)
                file.name.endsWith(".jar") || file.name.endsWith(".zip") ||
                    file.name.endsWith(".apk") -> {
                    val out = LinkedHashSet<String>()
                    ZipFile(file).use { zip ->
                        val entries = zip.entries()
                        while (entries.hasMoreElements()) {
                            val e = entries.nextElement()
                            if (!e.isDirectory && e.name.endsWith(".dex")) {
                                out.addAll(dexClassNames(zip.getInputStream(e).readBytes()))
                            }
                        }
                    }
                    out.toList()
                }

                else -> emptyList()
            }
        } catch (t: Throwable) {
            emptyList()
        }
    }

    /** 解析单份 dex 字节里的类描述符。 */
    @JvmStatic
    fun dexClassNames(bytes: ByteArray): List<String> {
        // dex header: magic(8) + checksum(4) + signature(20) + file_size(4) + ...
        if (bytes.size < 0x70) return emptyList()
        if (bytes[0].toInt().toChar() != MAGIC_D ||
            bytes[1].toInt().toChar() != MAGIC_E ||
            bytes[2].toInt().toChar() != MAGIC_X
        ) {
            return emptyList()
        }
        val stringIdsSize = u32le(bytes, 0x38)
        val stringIdsOff = u32le(bytes, 0x3C)
        if (stringIdsSize <= 0 || stringIdsOff <= 0) return emptyList()

        val out = LinkedHashSet<String>()
        var i = 0
        while (i < stringIdsSize) {
            val idOff = stringIdsOff + i * 4
            if (idOff + 4 > bytes.size) break
            val dataOff = u32le(bytes, idOff)
            if (dataOff in 1 until bytes.size) {
                var p = skipUleb128(bytes, dataOff)
                val sb = StringBuilder()
                while (p < bytes.size && bytes[p].toInt() != 0 && sb.length < MAX_STRING_LEN) {
                    sb.append((bytes[p].toInt() and 0xFF).toChar())
                    p++
                }
                val str = sb.toString()
                if (str.length >= 3 && str[0] == 'L' && str.endsWith(";") && str.indexOf('/') > 0) {
                    out.add(str.substring(1, str.length - 1).replace('/', '.'))
                }
            }
            i++
        }
        return out.toList()
    }

    private fun u32le(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or ((b[off + 3].toInt() and 0xFF) shl 24)

    /** 跳过 uleb128,返回其后的位置。 */
    private fun skipUleb128(b: ByteArray, pos: Int): Int {
        var p = pos
        while (p < b.size) {
            val cur = b[p].toInt() and 0xFF
            p++
            if (cur and 0x80 == 0) break
        }
        return p
    }
}
