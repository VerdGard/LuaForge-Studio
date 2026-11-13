package com.luaforge.studio.payload

import androidx.annotation.Keep
import java.io.File
import java.io.FileOutputStream

/**
 * so 打包模式的载荷容器(运行时侧)。
 *
 * ## 打包形态
 *
 * 构建时 `ApkBuilder` 把项目的全部 Lua/ALY 字节码打成一份载荷,加密后
 * **追加到 `libluaforge_payload.so` 尾部**。产物 APK 里因此没有任何
 * `assets/*.lua`,只有这一条原生库记录。
 *
 * ## 运行形态
 *
 * 原生侧从自身 `.so` 尾部读回并解密([nativeReadPayload]),本类把它解包到
 * 应用私有目录,交给既有的 Lua 文件加载链(`LuaActivity` 的 `luaDir` /
 * `package.path`)使用。
 *
 * **为什么解包到磁盘而不是拦截 `loadfile`**:现有运行时是"整条文件路径链"
 * 驱动的 —— `main.lua` 由 `doFile` 按路径加载,`require` 走 `package.path`,
 * 布局助手按路径找 `.aly`,`settings.json` 也按路径读。要改成"全部从内存
 * 加载"就得重写这套链的每一环,回归面覆盖编辑器全部功能。解包到私有目录
 * 只改"启动前"这一步,其余零改动。
 *
 * 私有目录是 `getFilesDir()`(应用沙箱),其他应用不可读,且随卸载清除;
 * 与"APK 里没有明文源文件"的目标一致。
 *
 * ## 失败行为
 *
 * 未追加载荷(`libluaforge_payload.so` 是空壳)时 [hasPayload] 返回 false,
 * 调用方应按"普通加密模式"继续 —— 同一个 APK 模板因此能承载三种打包模式。
 */
@Keep
object PayloadContainer {

    private const val TAG = "PayloadContainer"

    /** 载荷解包后的目录名(位于应用私有 files 目录下)。 */
    const val PAYLOAD_DIR = "luaforge_payload"

    /** 原生库是否可用。 */
    private val available: Boolean = try {
        System.loadLibrary("luaforge_payload")
        true
    } catch (t: Throwable) {
        false
    }

    private external fun nativeReadPayload(): ByteArray?
    private external fun nativeHasPayload(): Boolean

    /**
     * 本 APK 是否以 so 打包模式构建(即 `.so` 尾部确实带了载荷)。
     *
     * 这是**判定打包模式**的唯一依据,不依赖任何配置字段 ——
     * 用户把 APK 装到别的设备上同样能正确判定。
     */
    @JvmStatic
    fun hasPayload(): Boolean = available && try {
        nativeHasPayload()
    } catch (t: Throwable) {
        false
    }

    /**
     * 把内嵌载荷解包到 [targetDir]。
     *
     * 载荷格式(由构建侧写入,这里解析):
     *
     *     [4B 文件数][每条: 2B 路径长 + UTF-8 路径 + 4B 内容长 + 内容]...
     *
     * @return 解包出的文件数;无载荷或解析失败返回 -1
     */
    @JvmStatic
    fun unpack(targetDir: File): Int {
        if (!available) return -1
        val payload = try {
            nativeReadPayload()
        } catch (t: Throwable) {
            return -1
        } ?: return -1

        return try {
            unpackPayload(payload, targetDir)
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "解包载荷失败: ${t.message}", t)
            -1
        }
    }

    /**
     * 手工解析载荷并写盘。
     *
     * 用 `targetDir.canonicalFile` 做前缀校验,拒绝 `../` 之类的越界路径 ——
     * 载荷虽然由本工具产出,但 APK 可能被第三方重新打包,不能假定内容可信。
     */
    private fun unpackPayload(payload: ByteArray, targetDir: File): Int {
        targetDir.mkdirs()
        val root = targetDir.canonicalFile
        val rootPath = root.absolutePath + File.separator

        var pos = 0

        fun readInt(): Int {
            require(payload.size - pos >= 4) { "载荷截断(读取 int)" }
            val v = (payload[pos].toInt() and 0xFF) or
                ((payload[pos + 1].toInt() and 0xFF) shl 8) or
                ((payload[pos + 2].toInt() and 0xFF) shl 16) or
                ((payload[pos + 3].toInt() and 0xFF) shl 24)
            pos += 4
            return v
        }

        fun readShort(): Int {
            require(payload.size - pos >= 2) { "载荷截断(读取 short)" }
            val v = (payload[pos].toInt() and 0xFF) or
                ((payload[pos + 1].toInt() and 0xFF) shl 8)
            pos += 2
            return v
        }

        val count = readInt()
        require(count in 0..100_000) { "文件数异常: $count" }

        var written = 0
        repeat(count) {
            val pathLen = readShort()
            require(pathLen in 1..4096) { "路径长度异常: $pathLen" }
            require(payload.size - pos >= pathLen) { "载荷截断(路径)" }
            val path = String(payload, pos, pathLen, Charsets.UTF_8)
            pos += pathLen

            val contentLen = readInt()
            require(contentLen >= 0 && payload.size - pos >= contentLen) { "载荷截断(内容)" }

            val out = File(root, path).canonicalFile
            // 目录穿越防护:规范化后必须仍在根目录内
            require(out.absolutePath.startsWith(rootPath) || out == root) {
                "载荷路径越界: $path"
            }

            out.parentFile?.mkdirs()
            FileOutputStream(out).use { fos ->
                fos.write(payload, pos, contentLen)
            }
            pos += contentLen
            written++
        }
        return written
    }

    // ------------------------------------------------------------------
    // dex 模式
    // ------------------------------------------------------------------

    /** dex 模式生成的类全名(与构建侧 LuaPayloadBuilder.DEX_PAYLOAD_CLASS 对应)。 */
    private const val DEX_PAYLOAD_CLASS = "com.luaforge.studio.payload.SoPayloadData"

    /** dex 模式字段名前缀。 */
    private const val DEX_PAYLOAD_FIELD = "d"

    /**
     * 本 APK 是否是 dex 打包模式(载荷在额外 dex 的字符串常量里)。
     *
     * 判定方式:加载载荷类并读 `COUNT`。类不存在即不是 dex 模式 ——
     * 与 [hasPayload] 一样,不依赖任何配置字段。
     */
    @JvmStatic
    fun hasDexPayload(): Boolean = try {
        val clazz = Class.forName(DEX_PAYLOAD_CLASS)
        val count = clazz.getField("COUNT").getInt(null)
        count > 0
    } catch (t: Throwable) {
        false
    }

    /**
     * 从 dex 常量里取出载荷并解包到 [targetDir]。
     *
     * @return 解包出的文件数;非 dex 模式或失败返回 -1
     */
    @JvmStatic
    fun unpackFromDex(targetDir: File): Int {
        val encrypted = readDexPayload() ?: return -1
        val plain = try {
            // 载荷在 dex 侧是"已加密"的:seed 与段基与构建侧约定一致,
            // 这里用与 native 相同的算法还原(见 PayloadCrypto 的说明)。
            PayloadDecryptor.decrypt(encrypted, DEX_PAYLOAD_SEED, DEX_PAYLOAD_SEGMENT)
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "dex 载荷解密失败: ${t.message}", t)
            return -1
        }

        return try {
            unpackPayload(plain, targetDir)
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "dex 载荷解包失败: ${t.message}", t)
            -1
        }
    }

    /** 读出 dex 里承载的加密载荷(Base64 片段拼接后解码)。 */
    private fun readDexPayload(): ByteArray? = try {
        val clazz = Class.forName(DEX_PAYLOAD_CLASS)
        val count = clazz.getField("COUNT").getInt(null)
        if (count <= 0) {
            null
        } else {
            val sb = StringBuilder(count * 512)
            for (i in 0 until count) {
                val part = clazz.getField("$DEX_PAYLOAD_FIELD$i").get(null) as? String ?: ""
                sb.append(part)
            }
            android.util.Base64.decode(sb.toString(), android.util.Base64.NO_WRAP)
        }
    } catch (t: Throwable) {
        null
    }

    /** dex 模式载荷的 seed(构建期固定值,与加密侧一致)。 */
    private const val DEX_PAYLOAD_SEED = 0x4C464458

    /** dex 模式载荷的段基。 */
    private const val DEX_PAYLOAD_SEGMENT = 0x7100

    /**
     * 解包到应用的私有载荷目录。
     *
     * @param filesDir 通常是 `context.filesDir`
     * @return 目标目录(即便没有载荷也返回路径,便于调用方统一处理)
     */
    @JvmStatic
    fun unpackToPrivateDir(filesDir: File): File {
        val dir = File(filesDir, PAYLOAD_DIR)
        when {
            hasPayload() -> unpack(dir)
            hasDexPayload() -> unpackFromDex(dir)
        }
        return dir
    }

    /**
     * 任意打包模式是否携带了内嵌载荷(so / dex)。
     *
     * 明文加密模式与未加密模式都返回 false —— 它们的项目文件本来就在
     * `assets/` 里,由既有的模板解包流程处理。
     */
    @JvmStatic
    fun hasAnyPayload(): Boolean = hasPayload() || hasDexPayload()
}
