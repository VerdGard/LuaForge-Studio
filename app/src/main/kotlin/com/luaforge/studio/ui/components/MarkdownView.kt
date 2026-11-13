package com.luaforge.studio.ui.components

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.AttributeSet
import android.util.Base64
import android.util.Log
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.net.toUri
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.regex.Pattern

class MarkdownView @JvmOverloads constructor(
    private val mContext: Context,
    attributeSet: AttributeSet? = null,
    i: Int = 0
) : WebView(
    mContext, attributeSet, i
) {
    var isOpenUrlInBrowser: Boolean = false
    private var mPreviewText: String? = null

    /**
     * 当前主题是否为深色。
     *
     * 预览页的 markdown/hljs 主题是硬编码浅色的上游产物,深色模式下若不切换
     * 会出现"白色文档卡片盖在深色对话框上"的割裂观感。这里记下状态,
     * 由 [applyTheme] 在页面加载完成后统一切换。
     */
    private var darkMode: Boolean = false

    /** 最近一次注入的调色板;页面重新加载后需要重新注入。 */
    private var palette: DocPalette? = null

    /** 文档预览用的调色板(与 Compose colorScheme 一一对应)。 */
    data class DocPalette(
        val background: Int,
        val foreground: Int,
        val muted: Int,
        val border: Int,
        val hr: Int,
        val inlineCodeBg: Int,
        val inlineCodeFg: Int,
        val preBorder: Int,
        val tableHeadBorder: Int
    )

    init {
        initialize()
    }

    @Suppress("DEPRECATION")
    @SuppressLint("SetJavaScriptEnabled")
    private fun initialize() {
        webViewClient = object : WebViewClient() {
            // android.webkit.WebViewClient
            override fun onPageFinished(webView: WebView?, str: String?) {
                // 先注入配色,再渲染正文:否则会先闪一帧默认浅色
                applyTheme()
                this@MarkdownView.mPreviewText?.let {
                    this@MarkdownView.evaluateJavascript(it, null)
                }
            }

            @Suppress("deprecation", "OVERRIDE_DEPRECATION")  // android.webkit.WebViewClient
            override fun shouldOverrideUrlLoading(webView: WebView?, str: String?): Boolean {
                if (this@MarkdownView.isOpenUrlInBrowser) {
                    this@MarkdownView.mContext.startActivity(
                        Intent(
                            "android.intent.action.VIEW",
                            str?.toUri()
                        )
                    )
                    return true
                }
                return false
            }
        }
        loadUrl("file:///android_asset/html/preview.html")
        settings.javaScriptEnabled = true
        settings.allowUniversalAccessFromFileURLs = true
        0.also { settings.mixedContentMode = 0 }
    }

    /**
     * 设置主题。可在任意时刻调用;页面未就绪时会记住配置,
     * 在 [WebViewClient.onPageFinished] 里补注入。
     *
     * @param dark 是否深色
     * @param palette 调色板;为 null 时仅切换 dark class,使用 CSS 内置信变量值
     */
    fun applyTheme(dark: Boolean, palette: DocPalette? = null) {
        this.darkMode = dark
        if (palette != null) this.palette = palette
        applyTheme()
    }

    /**
     * 把当前主题写进页面(设置 root class + CSS 变量)。
     *
     * 注入的 `<style>` 一定会排在 `doc-theme.css` 之后(动态插入到 head 末尾),
     * 所以**选择器优先级必须与目标模式对齐**,不能一律写 `:root`:
     *
     * - 浅色:`:root` 直接生效;
     * - 深色:`html.doc-dark` 必须写在注入样式里,否则注入的 `:root` 会盖掉
     *   `doc-theme.css` 里的 `html.doc-dark` 配色(同层级后写者胜),
     *   深色模式就退化成浅色底。
     *
     * 另外 `--doc-inline-code-*` 在深色下**不注入** —— 行内代码需要足够饱和
     * 的底色才能与正文区分,Compose 的 secondaryContainer 在部分配色下
     * 对比度不足,交由 CSS 使用 github-dark 的专用色。
     */
    private fun applyTheme() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) return

        val css = StringBuilder()
        palette?.let { p ->
            css.append(if (darkMode) "html.doc-dark{" else ":root{")
            css.append("--doc-bg:${p.background.toCss()};")
            css.append("--doc-fg:${p.foreground.toCss()};")
            css.append("--doc-muted:${p.muted.toCss()};")
            css.append("--doc-border:${p.border.toCss()};")
            css.append("--doc-hr:${p.hr.toCss()};")
            if (!darkMode) {
                css.append("--doc-inline-code-bg:${p.inlineCodeBg.toCss()};")
                css.append("--doc-inline-code-fg:${p.inlineCodeFg.toCss()};")
            }
            css.append("--doc-pre-border:${p.preBorder.toCss()};")
            css.append("--doc-table-head-border:${p.tableHeadBorder.toCss()};")
            css.append("}")
        }

        // 用一个 <style id> 承载变量:反复调用时整体替换,不累积<style>标签
        val script = buildString {
            append("(function(){")
            append("var s=document.getElementById('doc-vars');")
            append("if(!s){s=document.createElement('style');s.id='doc-vars';")
            append("document.head.appendChild(s);}")
            append("s.textContent=")
            append(jsQuote(css.toString()))
            append(";")
            append(if (darkMode) {
                "document.documentElement.classList.add('doc-dark');"
            } else {
                "document.documentElement.classList.remove('doc-dark');"
            })
            append("})();")
        }
        evaluateJavascript(script, null)
    }

    /** Int 颜色(0xAARRGGBB)转成 CSS 可用的 `#rrggbb`。 */
    private fun Int.toCss(): String = String.format("#%06X", this and 0xFFFFFF)

    /** 把任意字符串包成合法的 JS 字符串字面量。 */
    private fun jsQuote(text: String): String {
        val sb = StringBuilder(text.length + 2)
        sb.append('"')
        for (c in text) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c.code < 0x20) {
                    sb.append(String.format("\\u%04x", c.code))
                } else {
                    sb.append(c)
                }
            }
        }
        sb.append('"')
        return sb.toString()
    }

    fun loadFromFile(file: File?) {
        var str: String?
        try {
            // 用 use{} 保证异常路径也关闭 reader:早期版本只 close 了 FileInputStream,
            // 且异常时两者都不关 —— Markdown 预览会被反复打开,泄漏的 fd
            // 攒到上限就是 "Too many open files",表现为整个编辑器读文件全失败。
            val sb = StringBuilder()
            BufferedReader(InputStreamReader(FileInputStream(file), Charsets.UTF_8)).use { bufferedReader ->
                while (true) {
                    val readLine = bufferedReader.readLine() ?: break
                    sb.append(readLine)
                    sb.append("\n")
                }
            }
            str = sb.toString()
        } catch (e: FileNotFoundException) {
            Log.e(TAG, "FileNotFoundException:$e")
            str = ""
        } catch (e2: IOException) {
            Log.e(TAG, "IOException:$e2")
            str = ""
        }
        loadFromText(str)
    }

    fun loadFromAssets(str: String) {
        val text = try {
            // use{}:文档对话框会被反复打开,早期实现只在 EOF 分支 close,
            // 一旦中途抛 IOException 就泄漏 fd,攒到上限后整个编辑器读文件全失败。
            context.assets.open(str).use { input ->
                BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8)).use { reader ->
                    reader.readText()
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "读取文档失败: $str", e)
            return
        }
        loadFromText(text)
    }

    fun loadFromText(str: String) {
        // 用 JSON 字符串字面量承载正文:早期实现手工替换 \n 与单引号,
        // 一旦文档里出现「反斜杠 + 单引号」这类组合(例如路径示例 C:\'x'),
        // 转义顺序会把引号提前闭合,JS 语法错误导致整篇文档渲染成空白。
        // JSONObject.quote 覆盖引号、反斜杠与控制字符,是唯一需要担心的边界。
        val literal = org.json.JSONObject.quote(imgToBase64(str))
        this.mPreviewText = "preview($literal)"
        initialize()
    }

    private fun imgToBase64(str: String): String {
        val matcher = Pattern.compile(IMAGE_PATTERN).matcher(str)
        if (!matcher.find()) {
            return str
        }
        val group = matcher.group(2) ?: throw AssertionError()
        if (isUrlPrefix(group) || !isPathExCheck(group)) {
            return str
        }
        val imgEx2BaseType = imgEx2BaseType(group)
        if (imgEx2BaseType.isEmpty()) {
            return str
        }
        val file = File(group)
        val bArr = ByteArray(file.length().toInt())
        try {
            BufferedInputStream(FileInputStream(file)).use { bufferedInputStream ->
                bufferedInputStream.read(bArr, 0, bArr.size)
            }
        } catch (e: FileNotFoundException) {
            Log.e(TAG, "FileNotFoundException:$e")
        } catch (e2: IOException) {
            Log.e(TAG, "IOException:$e2")
        }
        return str.replace(group, imgEx2BaseType + Base64.encodeToString(bArr, 2))
    }

    private fun isUrlPrefix(str: String): Boolean {
        return str.startsWith("http://") || str.startsWith("https://")
    }

    private fun isPathExCheck(str: String): Boolean {
        return str.endsWith(".png") || str.endsWith(".jpg") || str.endsWith(".jpeg") || str.endsWith(
            ".gif"
        )
    }

    private fun imgEx2BaseType(str: String): String {
        if (str.endsWith(".png")) {
            return "data:image/png;base64,"
        }
        if (str.endsWith(".jpg") || str.endsWith(".jpeg")) {
            return "data:image/jpg;base64,"
        }
        if (str.endsWith(".gif")) {
            return "data:image/gif;base64,"
        }
        return ""
    }

    companion object {
        private const val IMAGE_PATTERN = "!\\[(.*)]\\((.*)\\)"
        private val TAG: String = MarkdownView::class.java.simpleName
    }
}