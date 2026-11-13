/*
 * libfastio.so —— 面向 LuaForge 的极速文件读写原生实现。
 *
 * 与 Java 侧 `java.io.FileInputStream` / `FileOutputStream` 相比,这里做三件
 * 决定性的事:
 *
 * 1. **大块缓冲的裸 syscall**:直接 `open`/`read`/`write`,用 1 MiB 缓冲区
 *    顺序搬运。Java 流即使套 `BufferedOutputStream` 也仍是"逐次 fill/drain
 *    到 8 KiB 缓冲再逐次 syscall",小文件感知不明显,几十 MB 的文件差距是
 *    数量级。
 * 2. **读用 mmap**:`fstat` 拿到长度后 `mmap(MAP_PRIVATE)` 映射,再一次性
 *    `memcpy` 到 Java 数组。省掉"内核缓冲区 -> 用户缓冲区"的逐块 copy,
 *    同时避免为超长文件预分配临时缓冲。
 * 3. **写保证全量**:`write()` 允许短写(尤其大文件遇到信号/低存储),这里
 *    循环到写完为止 —— Java 侧 `FileOutputStream.write` 不保证这一点,
 *    上层若未自行校验就会静默产出截断文件。
 *
 * 不提供缓冲/缓存层、不做编码转换:编码由 Java 侧显式指定,原生层只处理
 * 字节,这样二进制读写与文本读写走同一条路径,不会出现"文本正常、二进制
 * 被替换成 U+FFFD"这类常见问题。
 *
 * 所有函数失败时返回各自类型的"失败值"(NULL / -1 / false),由 Java 侧
 * 转成可判定的结果或异常,不在原生层 abort。
 */

#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/sendfile.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

/* 顺序读写的缓冲区:1 MiB。手机端存储的典型块大小远小于它,
 * 再大对吞吐没有帮助,反而多占内存(构建期可能并发读多个文件)。 */
#define FASTIO_BUF_SIZE (1024 * 1024)

/* 小于该阈值时不值得 mmap:映射的系统调用开销大于直接 read。 */
#define FASTIO_MMAP_THRESHOLD (64 * 1024)

/* ------------------------------------------------------------------ */
/* 路径转换                                                            */
/* ------------------------------------------------------------------ */

/*
 * 把 jstring 转成可用的 C 路径,写入调用方提供的缓冲区。
 *
 * 不用 GetStringUTFChars 的原因:它产出 **modified UTF-8**(`\u0000` 变成
 * 两字节的 C0 80),而 `open()` 需要的是标准 UTF-8。经 NewStringUTF /
 * GetStringUTFChars 往返在含代理对的路径上会得到错误字节,进而 ENOENT。
 * 这里改走 Java 侧 `getBytes("UTF-8")`,保证与文件系统看到的一致。
 *
 * @return 0 成功,-1 失败(缓冲区不足或转换异常)
 */
static int fastio_get_path(JNIEnv *env, jstring jpath, char *out, size_t out_size) {
    if (jpath == NULL) return -1;

    jclass string_class = (*env)->FindClass(env, "java/lang/String");
    if (string_class == NULL) return -1;

    jmethodID get_bytes = (*env)->GetMethodID(env, string_class, "getBytes",
                                              "(Ljava/lang/String;)[B");
    if (get_bytes == NULL) {
        (*env)->DeleteLocalRef(env, string_class);
        return -1;
    }

    jstring charset = (*env)->NewStringUTF(env, "UTF-8");
    if (charset == NULL) {
        (*env)->DeleteLocalRef(env, string_class);
        return -1;
    }

    jbyteArray bytes = (jbyteArray) (*env)->CallObjectMethod(env, jpath, get_bytes, charset);
    (*env)->DeleteLocalRef(env, charset);
    (*env)->DeleteLocalRef(env, string_class);

    if (bytes == NULL) {
        /* getBytes 抛了异常(如 OOM):清掉,交给上层按失败处理 */
        (*env)->ExceptionClear(env);
        return -1;
    }

    jsize len = (*env)->GetArrayLength(env, bytes);
    if (len <= 0 || (size_t) len >= out_size) {
        (*env)->DeleteLocalRef(env, bytes);
        return -1;
    }

    (*env)->GetByteArrayRegion(env, bytes, 0, len, (jbyte *) out);
    out[len] = '\0';
    (*env)->DeleteLocalRef(env, bytes);

    /* GetByteArrayRegion 在越界/异常时会抛,此处长度已自查,仍防御性清理 */
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        return -1;
    }
    return 0;
}

/* 单个路径缓冲区大小:Android 路径上限 PATH_MAX(4096),留一倍余量。 */
#define FASTIO_PATH_MAX 8192

/* ------------------------------------------------------------------ */
/* 目录创建                                                            */
/* ------------------------------------------------------------------ */

/*
 * 递归创建目录(等价 `mkdir -p`)。
 *
 * 逐级 mkdir 并容忍 EEXIST:`File.mkdirs()` 在并发场景下也可能返回 false
 * 但目录实际已存在,按返回值判断会误报失败。
 *
 * @return 0 成功(含已存在),-1 失败
 */
static int fastio_mkdir_p(const char *path) {
    char tmp[FASTIO_PATH_MAX];
    size_t len = strlen(path);
    if (len == 0 || len >= sizeof(tmp)) return -1;

    memcpy(tmp, path, len + 1);

    /* 去掉末尾斜杠,避免对 "/a/b/" 判断错误 */
    while (len > 1 && tmp[len - 1] == '/') tmp[--len] = '\0';

    for (size_t i = 1; i < len; i++) {
        if (tmp[i] != '/') continue;
        tmp[i] = '\0';
        if (mkdir(tmp, 0777) != 0 && errno != EEXIST) return -1;
        tmp[i] = '/';
    }
    if (mkdir(tmp, 0777) != 0 && errno != EEXIST) return -1;
    return 0;
}

/** 为文件路径创建其父目录。@return 0 成功,-1 失败 */
static int fastio_mkdir_parent(const char *file_path) {
    char tmp[FASTIO_PATH_MAX];
    size_t len = strlen(file_path);
    if (len >= sizeof(tmp)) return -1;
    memcpy(tmp, file_path, len + 1);

    char *slash = strrchr(tmp, '/');
    if (slash == NULL || slash == tmp) return 0; /* 无父目录或已在根 */
    *slash = '\0';
    return fastio_mkdir_p(tmp);
}

/* ------------------------------------------------------------------ */
/* 读                                                                  */
/* ------------------------------------------------------------------ */

/*
 * 把整个文件读成 jbyteArray。
 *
 * @param max_bytes <= 0 表示不限制;超出时只读前 max_bytes 字节
 * @return 字节数组;文件不存在/无法读取返回 NULL(不抛 Java 异常)
 */
static jbyteArray fastio_read_all(JNIEnv *env, jstring jpath, jlong max_bytes) {
    char path[FASTIO_PATH_MAX];
    if (fastio_get_path(env, jpath, path, sizeof(path)) != 0) return NULL;

    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return NULL;

    struct stat st;
    if (fstat(fd, &st) != 0 || !S_ISREG(st.st_mode)) {
        close(fd);
        return NULL;
    }

    /* st_size 为 0 也可能是伪文件(如 /proc),这里仍按常规文件处理:
     * 长度为 0 时直接返回空数组,与 Java 的 File.length() == 0 行为一致。 */
    jlong size = (jlong) st.st_size;
    if (max_bytes > 0 && size > max_bytes) size = max_bytes;
    if (size < 0) {
        close(fd);
        return NULL;
    }

    jbyteArray result = (*env)->NewByteArray(env, (jsize) size);
    if (result == NULL) {
        close(fd);
        return NULL;
    }
    if (size == 0) {
        close(fd);
        return result;
    }

    if (size >= FASTIO_MMAP_THRESHOLD) {
        /* 大文件走 mmap:一次映射 + 一次 memcpy,省掉逐块 read 的内核拷贝。 */
        void *mapped = mmap(NULL, (size_t) size, PROT_READ, MAP_PRIVATE, fd, 0);
        if (mapped != MAP_FAILED) {
            (*env)->SetByteArrayRegion(env, result, 0, (jsize) size, (const jbyte *) mapped);
            munmap(mapped, (size_t) size);
            close(fd);
            return result;
        }
        /* mmap 失败(如低内存/文件系统不支持)退回常规 read,不当作错误 */
    }

    jbyte *buf = (jbyte *) malloc(FASTIO_BUF_SIZE);
    if (buf == NULL) {
        close(fd);
        return NULL;
    }

    jlong done = 0;
    while (done < size) {
        jlong remain = size - done;
        size_t want = remain > FASTIO_BUF_SIZE ? FASTIO_BUF_SIZE : (size_t) remain;
        ssize_t n = read(fd, buf, want);
        if (n < 0) {
            if (errno == EINTR) continue; /* 被信号打断:重试 */
            free(buf);
            close(fd);
            return NULL;
        }
        if (n == 0) break; /* 文件比 fstat 报告得更短(被截断) */
        (*env)->SetByteArrayRegion(env, result, (jsize) done, (jsize) n, buf);
        done += n;
    }

    free(buf);
    close(fd);

    if (done != size) {
        /* 读到的比预期少(读期间文件被截断)。
         * 重裁成实际长度,而不是返回带 0 尾巴的数组 —— 后者会让调用方
         * 把垃圾数据当成文件内容。 */
        jbyteArray shrunk = (*env)->NewByteArray(env, (jsize) done);
        if (shrunk == NULL) return result;
        jbyte *src = (*env)->GetByteArrayElements(env, result, NULL);
        if (src == NULL) return result; /* 已抛 OOM,调用方会看到异常 */
        (*env)->SetByteArrayRegion(env, shrunk, 0, (jsize) done, src);
        (*env)->ReleaseByteArrayElements(env, result, src, JNI_ABORT);
        return shrunk;
    }

    return result;
}

/*
 * 从指定偏移读取一段字节(随机访问)。
 * @return 字节数组;失败返回 NULL
 */
static jbyteArray fastio_read_chunk(JNIEnv *env, jstring jpath, jlong offset, jint length) {
    char path[FASTIO_PATH_MAX];
    if (fastio_get_path(env, jpath, path, sizeof(path)) != 0) return NULL;
    if (length < 0 || offset < 0) return NULL;

    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return NULL;

    jbyteArray result = (*env)->NewByteArray(env, length);
    if (result == NULL) {
        close(fd);
        return NULL;
    }
    if (length == 0) {
        close(fd);
        return result;
    }

    jbyte *buf = (jbyte *) malloc((size_t) length);
    if (buf == NULL) {
        close(fd);
        return NULL;
    }

    ssize_t n = pread(fd, buf, (size_t) length, (off_t) offset);
    close(fd);
    if (n < 0) {
        free(buf);
        return NULL;
    }

    (*env)->SetByteArrayRegion(env, result, 0, (jsize) n, buf);
    free(buf);
    return result;
}

/* ------------------------------------------------------------------ */
/* 写                                                                  */
/* ------------------------------------------------------------------ */

/*
 * 把字节数组写入文件。
 *
 * @param create_dir true 时自动递归创建父目录
 * @param append     true 时追加(文件不存在则创建)
 * @param sync       true 时 fsync,保证内容落盘后才返回
 * @return true 成功
 */
static jboolean fastio_write_all(JNIEnv *env, jstring jpath, jbyteArray data,
                                 jboolean create_dir, jboolean append, jboolean sync) {
    char path[FASTIO_PATH_MAX];
    if (fastio_get_path(env, jpath, path, sizeof(path)) != 0) return JNI_FALSE;

    if (create_dir) {
        if (fastio_mkdir_parent(path) != 0) return JNI_FALSE;
    }

    int flags = O_WRONLY | O_CREAT | O_CLOEXEC;
    flags |= append ? O_APPEND : O_TRUNC;

    int fd = open(path, flags, 0666);
    if (fd < 0) return JNI_FALSE;

    jsize len = data == NULL ? 0 : (*env)->GetArrayLength(env, data);
    if (len <= 0) {
        close(fd);
        return JNI_TRUE; /* 空内容:已按 flags 截断/创建,直接成功 */
    }

    jbyte *buf = (jbyte *) malloc(FASTIO_BUF_SIZE);
    if (buf == NULL) {
        close(fd);
        return JNI_FALSE;
    }

    jboolean ok = JNI_TRUE;
    jsize done = 0;
    while (done < len) {
        jsize remain = len - done;
        jsize chunk = remain > FASTIO_BUF_SIZE ? FASTIO_BUF_SIZE : remain;

        (*env)->GetByteArrayRegion(env, data, done, chunk, buf);
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionClear(env);
            ok = JNI_FALSE;
            break;
        }

        jsize written = 0;
        while (written < chunk) {
            ssize_t n = write(fd, buf + written, (size_t) (chunk - written));
            if (n < 0) {
                if (errno == EINTR) continue;
                ok = JNI_FALSE;
                break;
            }
            written += (jsize) n; /* 短写:继续,不丢内容 */
        }
        if (!ok) break;

        done += chunk;
    }

    free(buf);

    if (ok && sync) {
        /* fsync 只保证"数据可见性",失败(如某些 FUSE 文件系统不支持)不算写失败 */
        fsync(fd);
    }

    if (close(fd) != 0) ok = JNI_FALSE;
    return ok;
}

/* ------------------------------------------------------------------ */
/* JNI 导出                                                            */
/* ------------------------------------------------------------------ */

JNIEXPORT jbyteArray JNICALL
Java_com_luaforge_studio_utils_FastIOUtil_nativeReadBytes(
        JNIEnv *env, jclass clazz, jstring path, jlong max_bytes) {
    (void) clazz;
    return fastio_read_all(env, path, max_bytes);
}

JNIEXPORT jbyteArray JNICALL
Java_com_luaforge_studio_utils_FastIOUtil_nativeReadChunk(
        JNIEnv *env, jclass clazz, jstring path, jlong offset, jint length) {
    (void) clazz;
    return fastio_read_chunk(env, path, offset, length);
}

JNIEXPORT jboolean JNICALL
Java_com_luaforge_studio_utils_FastIOUtil_nativeWriteBytes(
        JNIEnv *env, jclass clazz, jstring path, jbyteArray data,
        jboolean create_dir, jboolean append, jboolean sync) {
    (void) clazz;
    return fastio_write_all(env, path, data, create_dir, append, sync);
}

JNIEXPORT jlong JNICALL
Java_com_luaforge_studio_utils_FastIOUtil_nativeSize(
        JNIEnv *env, jclass clazz, jstring path) {
    (void) clazz;
    char cpath[FASTIO_PATH_MAX];
    if (fastio_get_path(env, path, cpath, sizeof(cpath)) != 0) return -1;

    struct stat st;
    if (stat(cpath, &st) != 0 || !S_ISREG(st.st_mode)) return -1;
    return (jlong) st.st_size;
}

JNIEXPORT jboolean JNICALL
Java_com_luaforge_studio_utils_FastIOUtil_nativeExists(
        JNIEnv *env, jclass clazz, jstring path) {
    (void) clazz;
    char cpath[FASTIO_PATH_MAX];
    if (fastio_get_path(env, path, cpath, sizeof(cpath)) != 0) return JNI_FALSE;
    return access(cpath, F_OK) == 0 ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_luaforge_studio_utils_FastIOUtil_nativeDelete(
        JNIEnv *env, jclass clazz, jstring path) {
    (void) clazz;
    char cpath[FASTIO_PATH_MAX];
    if (fastio_get_path(env, path, cpath, sizeof(cpath)) != 0) return JNI_FALSE;
    return unlink(cpath) == 0 ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_luaforge_studio_utils_FastIOUtil_nativeMkdirs(
        JNIEnv *env, jclass clazz, jstring path) {
    (void) clazz;
    char cpath[FASTIO_PATH_MAX];
    if (fastio_get_path(env, path, cpath, sizeof(cpath)) != 0) return JNI_FALSE;
    return fastio_mkdir_p(cpath) == 0 ? JNI_TRUE : JNI_FALSE;
}

/*
 * 原生拷贝:读 + 写全在 C 侧完成,不经 Java 堆。
 *
 * @return 拷贝的字节数;失败返回 -1
 */
JNIEXPORT jlong JNICALL
Java_com_luaforge_studio_utils_FastIOUtil_nativeCopy(
        JNIEnv *env, jclass clazz, jstring jsrc, jstring jdst, jboolean create_dir) {
    (void) clazz;
    char src[FASTIO_PATH_MAX];
    char dst[FASTIO_PATH_MAX];
    if (fastio_get_path(env, jsrc, src, sizeof(src)) != 0) return -1;
    if (fastio_get_path(env, jdst, dst, sizeof(dst)) != 0) return -1;

    if (create_dir && fastio_mkdir_parent(dst) != 0) return -1;

    int in = open(src, O_RDONLY | O_CLOEXEC);
    if (in < 0) return -1;
    int out = open(dst, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0666);
    if (out < 0) {
        close(in);
        return -1;
    }

    /* 与 java.nio.file.Files.copy 相比,这里额外用 sendfile 快路径:
     * 同一次内核拷贝内完成搬运,用户态不持有整份数据。 */
    struct stat st;
    jlong total = 0;
    int ok = 1;

    if (fstat(in, &st) == 0 && S_ISREG(st.st_mode)) {
        off_t off = 0;
        while (off < st.st_size) {
            ssize_t n = sendfile(out, in, &off, (size_t) (st.st_size - off));
            if (n <= 0) {
                if (n < 0 && errno == EINTR) continue;
                /* sendfile 不可用(内核/文件系统不支持)时退回常规缓冲拷贝 */
                if (n < 0 && (errno == EINVAL || errno == ENOSYS || errno == EXDEV)) {
                    ok = 0;
                }
                break;
            }
            total += n;
        }
    } else {
        ok = 0;
    }

    if (!ok) {
        /* 常规路径:重新定位到开头再整份搬运 */
        if (lseek(in, 0, SEEK_SET) < 0 || ftruncate(out, 0) != 0) {
            close(in);
            close(out);
            return -1;
        }
        total = 0;
        char *buf = (char *) malloc(FASTIO_BUF_SIZE);
        if (buf == NULL) {
            close(in);
            close(out);
            return -1;
        }
        for (;;) {
            ssize_t n = read(in, buf, FASTIO_BUF_SIZE);
            if (n < 0) {
                if (errno == EINTR) continue;
                free(buf);
                close(in);
                close(out);
                return -1;
            }
            if (n == 0) break;
            ssize_t written = 0;
            while (written < n) {
                ssize_t w = write(out, buf + written, (size_t) (n - written));
                if (w < 0) {
                    if (errno == EINTR) continue;
                    free(buf);
                    close(in);
                    close(out);
                    return -1;
                }
                written += w;
            }
            total += n;
        }
        free(buf);
    }

    close(in);
    if (close(out) != 0) return -1;
    return total;
}

/* ------------------------------------------------------------------ */
/* 库加载                                                              */
/* ------------------------------------------------------------------ */

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void) vm;
    (void) reserved;
    return JNI_VERSION_1_6;
}
