package com.luaforge.studio.console.logcat

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile

/**
 * logcat 文件懒加载读取:按字节偏移分块读取(RandomAccessFile seek),
 * 收集整行后整体 UTF-8 解码,避免 readLine 的 Latin-1 破坏中文。
 */
class LogcatFileStore(private val file: File) {

    private var readOffset = 0L

    /** 从上次位置继续读取 maxLines 行;无新内容返回空列表。 */
    @Synchronized
    fun readChunk(maxLines: Int): List<String> {
        if (!file.exists() || file.length() <= readOffset) return emptyList()
        val lines = ArrayList<String>()
        try {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(readOffset)
                val chunk = ByteArray(BUFFER_BYTES)
                val lineBuf = ByteArrayOutputStream(256)
                var stoppedAtLineEnd = false
                while (lines.size < maxLines) {
                    val n = raf.read(chunk)
                    if (n <= 0) break
                    var start = 0
                    var i = 0
                    while (i < n) {
                        if (chunk[i] == NL) {
                            lineBuf.write(chunk, start, i - start)
                            lines.add(lineBuf.toString("UTF-8").trimEnd('\r'))
                            lineBuf.reset()
                            start = i + 1
                            if (lines.size >= maxLines) {
                                // 停在整行边界:把本块内未消费部分留给下次,下次从行首继续
                                readOffset = raf.filePointer - n + start
                                stoppedAtLineEnd = true
                                break
                            }
                        }
                        i++
                    }
                    if (stoppedAtLineEnd) break
                    if (start < n) lineBuf.write(chunk, start, n - start)
                }
                // 到达文件末尾仍未集满:剩余半行不输出(与逐字节版本一致),偏移推进至末尾
                if (!stoppedAtLineEnd) readOffset = raf.filePointer
            }
        } catch (e: Exception) {
            readOffset = file.length()
        }
        return lines
    }

    fun lineCount(): Long = file.length()

    fun file(): File = file

    private companion object {
        /** 块读粒度:逐字节 read() 每字一次 syscall,是 Logcat 页卡顿的主因。 */
        const val BUFFER_BYTES = 8 * 1024
        val NL = '\n'.code.toByte()
    }
}
