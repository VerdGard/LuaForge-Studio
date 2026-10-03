#define _GNU_SOURCE
/*
 * memkit.c — LuaForge 内存访问原生库
 *
 * 双层接口,共用同一套 mk_* 核心实现:
 *   1) JNI: Java_com_luaforge_studio_utils_MemUtil_*  —— 供 global_utils 桥接
 *   2) Lua: luaopen_memkit                            —— 供 require "memkit" 直接使用
 *
 * 权限模型(重要,如实反映内核/SELinux 约束):
 *   - pid == 自身进程:process_vm_readv/writev 即可,无需 root。
 *   - pid != 自身:需要 PTRACE_MODE_ATTACH(root 或同 uid 同签名)。
 *     Android 10+ SELinux enforcing 下即使 root 也可能被拦,此时返回 -1 并置 errno。
 *   所有读取失败都返回错误码,绝不直接解引用指针,避免 SIGSEGV 打死宿主进程。
 */

#include <jni.h>
#include <android/log.h>

#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <sys/uio.h>
#include <unistd.h>

#include "lua.h"
#include "lauxlib.h"

#define LOG_TAG "memkit"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#define MK_VERSION "1.0.0"
#define MK_DEFAULT_LIMIT (1u << 16)   /* 默认最多返回 65536 个结果 */
#define MK_MAX_LIMIT (1u << 20)       /* 上限 1048576 */
#define MK_SCAN_BUF (256u * 1024u)    /* 搜索分块缓冲 */

/* 交叉编译时若 <sys/syscall.h> 未提供 process_vm_* 编号,按架构补齐 */
#ifndef __NR_process_vm_readv
#if defined(__aarch64__)
#define __NR_process_vm_readv 270
#define __NR_process_vm_writev 271
#elif defined(__arm__)
#define __NR_process_vm_readv 376
#define __NR_process_vm_writev 377
#elif defined(__x86_64__)
#define __NR_process_vm_readv 310
#define __NR_process_vm_writev 311
#elif defined(__i386__)
#define __NR_process_vm_readv 347
#define __NR_process_vm_writev 348
#endif
#endif

/* ==================================================================
 * 基础工具:动态字符串 / JSON 转义
 * ================================================================== */

typedef struct {
    char *p;
    size_t len;
    size_t cap;
} mk_sb;

static void sb_init(mk_sb *sb) {
    sb->p = NULL;
    sb->len = 0;
    sb->cap = 0;
}

static void sb_free(mk_sb *sb) {
    free(sb->p);
    sb->p = NULL;
    sb->len = 0;
    sb->cap = 0;
}

static int sb_reserve(mk_sb *sb, size_t extra) {
    if (sb->len + extra + 1 <= sb->cap) return 0;
    size_t cap = sb->cap ? sb->cap : 256;
    while (cap < sb->len + extra + 1) cap *= 2;
    char *t = (char *) realloc(sb->p, cap);
    if (!t) return -1;
    sb->p = t;
    sb->cap = cap;
    return 0;
}

static void sb_putc(mk_sb *sb, char c) {
    if (sb_reserve(sb, 1) == 0) sb->p[sb->len++] = c;
}

static void sb_puts(mk_sb *sb, const char *s) {
    if (!s) return;
    size_t n = strlen(s);
    if (sb_reserve(sb, n) == 0) {
        memcpy(sb->p + sb->len, s, n);
        sb->len += n;
    }
}

static void sb_printf(mk_sb *sb, const char *fmt, ...) {
    char tmp[512];
    va_list ap;
    va_start(ap, fmt);
    int n = vsnprintf(tmp, sizeof tmp, fmt, ap);
    va_end(ap);
    if (n < 0) return;
    if ((size_t) n < sizeof tmp) {
        sb_puts(sb, tmp);
        return;
    }
    char *big = (char *) malloc((size_t) n + 1);
    if (!big) return;
    va_start(ap, fmt);
    vsnprintf(big, (size_t) n + 1, fmt, ap);
    va_end(ap);
    sb_puts(sb, big);
    free(big);
}

static void sb_json_str(mk_sb *sb, const char *s) {
    sb_putc(sb, '"');
    if (s) {
        for (const unsigned char *q = (const unsigned char *) s; *q; q++) {
            switch (*q) {
                case '"': sb_puts(sb, "\\\""); break;
                case '\\': sb_puts(sb, "\\\\"); break;
                case '\n': sb_puts(sb, "\\n"); break;
                case '\r': sb_puts(sb, "\\r"); break;
                case '\t': sb_puts(sb, "\\t"); break;
                default:
                    if (*q < 0x20) sb_printf(sb, "\\u%04x", (unsigned int) *q);
                    else sb_putc(sb, (char) *q);
            }
        }
    }
    sb_putc(sb, '"');
}

static void sb_hex(mk_sb *sb, const uint8_t *p, size_t n) {
    static const char *H = "0123456789abcdef";
    if (sb_reserve(sb, n * 2) != 0) return;
    for (size_t i = 0; i < n; i++) {
        sb->p[sb->len++] = H[(p[i] >> 4) & 0xF];
        sb->p[sb->len++] = H[p[i] & 0xF];
    }
}

static size_t mk_page_size(void) {
    static size_t ps = 0;
    if (ps == 0) {
        long v = sysconf(_SC_PAGESIZE);
        ps = (v > 0) ? (size_t) v : 4096u;
    }
    return ps;
}

/* ==================================================================
 * /proc/<pid>/mem fd 缓存 + 跨进程读写
 * ================================================================== */

#define MK_FD_SLOTS 8

typedef struct {
    int fd;
    pid_t pid;
    int writable;
} mk_fd_slot;

static mk_fd_slot g_fds[MK_FD_SLOTS];

static int mk_mem_fd(pid_t pid, int want_write) {
    for (int i = 0; i < MK_FD_SLOTS; i++) {
        if (g_fds[i].fd > 0 && g_fds[i].pid == pid) {
            if (!want_write || g_fds[i].writable) return g_fds[i].fd;
            close(g_fds[i].fd);
            g_fds[i].fd = -1;
            break;
        }
    }
    char path[64];
    snprintf(path, sizeof path, "/proc/%d/mem", (int) pid);
    int fd = -1;
    int writable = 0;
    if (want_write) {
        fd = open(path, O_RDWR | O_CLOEXEC);
        writable = (fd >= 0);
    }
    if (fd < 0) {
        fd = open(path, O_RDONLY | O_CLOEXEC);
        writable = 0;
    }
    if (fd < 0) return -1;

    int slot = -1;
    for (int i = 0; i < MK_FD_SLOTS; i++) {
        if (g_fds[i].fd <= 0) {
            slot = i;
            break;
        }
    }
    if (slot < 0) {
        static int rr = 0;
        rr = (rr + 1) % MK_FD_SLOTS;
        close(g_fds[rr].fd);
        slot = rr;
    }
    g_fds[slot].fd = fd;
    g_fds[slot].pid = pid;
    g_fds[slot].writable = writable;
    return fd;
}

static ssize_t mk_pvm_read(pid_t pid, uintptr_t addr, void *buf, size_t len) {
    struct iovec l, r;
    l.iov_base = buf;
    l.iov_len = len;
    r.iov_base = (void *) addr;
    r.iov_len = len;
    return (ssize_t) syscall(__NR_process_vm_readv, (int) pid, &l,
                             (unsigned long) 1, &r, (unsigned long) 1,
                             (unsigned long) 0);
}

static ssize_t mk_pvm_write(pid_t pid, uintptr_t addr, const void *buf, size_t len) {
    struct iovec l, r;
    l.iov_base = (void *) buf;
    l.iov_len = len;
    r.iov_base = (void *) addr;
    r.iov_len = len;
    return (ssize_t) syscall(__NR_process_vm_writev, (int) pid, &l,
                             (unsigned long) 1, &r, (unsigned long) 1,
                             (unsigned long) 0);
}

/* 单次底层读取(不跨页保护,调用方按页切分) */
static ssize_t mk_read_raw(pid_t pid, uintptr_t addr, void *buf, size_t len) {
    ssize_t n = mk_pvm_read(pid, addr, buf, len);
    if (n > 0) return n;
    int fd = mk_mem_fd(pid, 0);
    if (fd < 0) return -1;
    return pread(fd, buf, len, (off_t) addr);
}

static ssize_t mk_write_raw(pid_t pid, uintptr_t addr, const void *buf, size_t len) {
    ssize_t n = mk_pvm_write(pid, addr, buf, len);
    if (n > 0) return n;
    int fd = mk_mem_fd(pid, 1);
    if (fd < 0) return -1;
    return pwrite(fd, buf, len, (off_t) addr);
}

/*
 * 逐页读写:process_vm_get 在跨越未映射页时可能整体失败,
 * 按页切分可拿到"部分成功"的语义,也便于调用方判断边界。
 */
static ssize_t mk_read_mem(pid_t pid, uintptr_t addr, void *buf, size_t len) {
    size_t done = 0;
    size_t ps = mk_page_size();
    while (done < len) {
        size_t chunk = len - done;
        size_t off = (size_t) ((addr + done) & (ps - 1));
        size_t left = ps - off;
        if (chunk > left) chunk = left;
        ssize_t n = mk_read_raw(pid, addr + done, (char *) buf + done, chunk);
        if (n <= 0) break;
        done += (size_t) n;
        if ((size_t) n < chunk) break;
    }
    return (ssize_t) done;
}

static ssize_t mk_write_mem(pid_t pid, uintptr_t addr, const void *buf, size_t len) {
    size_t done = 0;
    size_t ps = mk_page_size();
    while (done < len) {
        size_t chunk = len - done;
        size_t off = (size_t) ((addr + done) & (ps - 1));
        size_t left = ps - off;
        if (chunk > left) chunk = left;
        ssize_t n = mk_write_raw(pid, addr + done, (const char *) buf + done, chunk);
        if (n <= 0) break;
        done += (size_t) n;
        if ((size_t) n < chunk) break;
    }
    return (ssize_t) done;
}

/* ==================================================================
 * 内存区域(/proc/<pid>/maps)
 * ================================================================== */

typedef struct {
    uintptr_t start;
    uintptr_t end;
    char perms[5];
    char path[256];
} mk_region;

static int mk_read_maps(pid_t pid, mk_region **out, size_t *out_count) {
    *out = NULL;
    *out_count = 0;
    char p[64];
    snprintf(p, sizeof p, "/proc/%d/maps", (int) pid);
    FILE *fp = fopen(p, "r");
    if (!fp) return -1;

    size_t cap = 64, n = 0;
    mk_region *arr = (mk_region *) malloc(cap * sizeof *arr);
    if (!arr) {
        fclose(fp);
        return -1;
    }

    char *line = NULL;
    size_t lcap = 0;
    while (getline(&line, &lcap, fp) > 0) {
        unsigned long long s = 0, e = 0;
        char perms[8] = {0};
        if (sscanf(line, "%llx-%llx %7s", &s, &e, perms) != 3) continue;
        if (n == cap) {
            cap *= 2;
            mk_region *t = (mk_region *) realloc(arr, cap * sizeof *arr);
            if (!t) break;
            arr = t;
        }
        arr[n].start = (uintptr_t) s;
        arr[n].end = (uintptr_t) e;
        memcpy(arr[n].perms, perms, 4);
        arr[n].perms[4] = '\0';
        arr[n].path[0] = '\0';

        /* 跳过前 5 个字段(range / perms / offset / dev / inode) */
        char *q = line;
        int field = 0;
        while (*q && field < 5) {
            while (*q && *q != ' ') q++;
            while (*q == ' ') q++;
            field++;
        }
        size_t L = strlen(q);
        while (L && (q[L - 1] == '\n' || q[L - 1] == '\r' || q[L - 1] == ' ')) q[--L] = '\0';
        if (L) snprintf(arr[n].path, sizeof arr[n].path, "%s", q);
        n++;
    }
    free(line);
    fclose(fp);
    *out = arr;
    *out_count = n;
    return 0;
}

/* ==================================================================
 * root 检测 / 进程查找
 * ================================================================== */

static int mk_path_ok(const char *p) {
    struct stat st;
    return (stat(p, &st) == 0);
}

static int mk_is_root_fast(void) {
    if (geteuid() == 0) return 1;
    static const char *SU[] = {
            "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su",
            "/system/sbin/su", "/vendor/bin/su", "/system/bin/.ext/.su",
            "/debug_ramdisk/su", "/system_ext/bin/su",
    };
    for (size_t i = 0; i < sizeof(SU) / sizeof(SU[0]); i++) {
        if (mk_path_ok(SU[i])) return 1;
    }
    static const char *MGR[] = {
            "/data/adb/magisk", "/sbin/.magisk", "/data/adb/ksu",
            "/data/adb/ap", "/data/adb/modules",
    };
    for (size_t i = 0; i < sizeof(MGR) / sizeof(MGR[0]); i++) {
        if (mk_path_ok(MGR[i])) return 1;
    }
    return 0;
}

/* 依据进程名(/proc/<pid>/cmdline basename 或 comm)查找 pid,找不到返回 -1 */
static int mk_pid_of(const char *name) {
    if (!name || !*name) return -1;
    DIR *d = opendir("/proc");
    if (!d) return -1;
    struct dirent *de;
    int found = -1;
    while ((de = readdir(d)) != NULL) {
        if (de->d_name[0] < '0' || de->d_name[0] > '9') continue;
        char path[64];
        int pid = atoi(de->d_name);
        if (pid <= 0) continue;

        char comm[256] = {0};
        snprintf(path, sizeof path, "/proc/%d/comm", pid);
        int fd = open(path, O_RDONLY | O_CLOEXEC);
        if (fd >= 0) {
            ssize_t n = read(fd, comm, sizeof comm - 1);
            close(fd);
            if (n > 0) {
                while (n > 0 && (comm[n - 1] == '\n' || comm[n - 1] == '\r')) comm[--n] = '\0';
            }
        }
        if (comm[0] && strcmp(comm, name) == 0) {
            found = pid;
            break;
        }

        char cmd[512] = {0};
        snprintf(path, sizeof path, "/proc/%d/cmdline", pid);
        fd = open(path, O_RDONLY | O_CLOEXEC);
        if (fd >= 0) {
            ssize_t n = read(fd, cmd, sizeof cmd - 1);
            close(fd);
            if (n > 0) cmd[n] = '\0';
        }
        if (cmd[0]) {
            const char *base = strrchr(cmd, '/');
            base = base ? base + 1 : cmd;
            if (strcmp(base, name) == 0 || strcmp(cmd, name) == 0) {
                found = pid;
                break;
            }
        }
    }
    closedir(d);
    return found;
}

/* ==================================================================
 * 值类型
 * ================================================================== */

#define MK_T_I8    1
#define MK_T_I16   2
#define MK_T_I32   4
#define MK_T_I64   8
#define MK_T_F32   16
#define MK_T_F64   17
#define MK_T_STR   32

static size_t mk_type_size(int t) {
    switch (t) {
        case MK_T_I8: return 1;
        case MK_T_I16: return 2;
        case MK_T_I32: return 4;
        case MK_T_I64: return 8;
        case MK_T_F32: return 4;
        case MK_T_F64: return 8;
        default: return 0;
    }
}

/*
 * 把文本值编码成用于逐字节比较的原始数据。
 * 支持十进制 / 0x 十六进制整数,浮点,以及字符串(含 "\xNN" 转义)。
 * 返回编码后长度,<0 表示解析失败。
 */
static int mk_encode_value(int type, const char *s, uint8_t *out, size_t outcap) {
    if (!s) return -1;
    if (type == MK_T_STR) {
        size_t w = 0;
        for (const char *p = s; *p && w < outcap; p++) {
            if (p[0] == '\\' && (p[1] == 'x' || p[1] == 'X')) {
                char h[3] = {p[2], p[3], 0};
                if (!h[0] || !h[1]) return -1;
                out[w++] = (uint8_t) strtoul(h, NULL, 16);
                p += 3;
            } else {
                out[w++] = (uint8_t) *p;
            }
        }
        return (int) w;
    }

    size_t sz = mk_type_size(type);
    if (sz == 0 || sz > outcap) return -1;
    errno = 0;
    if (type == MK_T_F32) {
        float f = strtof(s, NULL);
        memcpy(out, &f, 4);
        return 4;
    }
    if (type == MK_T_F64) {
        double d = strtod(s, NULL);
        memcpy(out, &d, 8);
        return 8;
    }
    uint64_t v = strtoull(s, NULL, 0);
    memcpy(out, &v, sz);   /* 小端:低 sz 字节即为该宽度的表示 */
    return (int) sz;
}

/* 把原始字节格式化成人读文本(供 JNI / Lua 返回) */
static void mk_format_value(int type, const uint8_t *p, size_t len, char *out, size_t outcap) {
    switch (type) {
        case MK_T_I8: {
            int8_t v;
            memcpy(&v, p, 1);
            snprintf(out, outcap, "%d", (int) v);
            break;
        }
        case MK_T_I16: {
            int16_t v;
            memcpy(&v, p, 2);
            snprintf(out, outcap, "%d", (int) v);
            break;
        }
        case MK_T_I32: {
            int32_t v;
            memcpy(&v, p, 4);
            snprintf(out, outcap, "%d", (int) v);
            break;
        }
        case MK_T_I64: {
            int64_t v;
            memcpy(&v, p, 8);
            snprintf(out, outcap, "%lld", (long long) v);
            break;
        }
        case MK_T_F32: {
            float v;
            memcpy(&v, p, 4);
            snprintf(out, outcap, "%.9g", (double) v);
            break;
        }
        case MK_T_F64: {
            double v;
            memcpy(&v, p, 8);
            snprintf(out, outcap, "%.17g", v);
            break;
        }
        default:
            if (len >= outcap) len = outcap - 1;
            memcpy(out, p, len);
            out[len] = '\0';
            break;
    }
}

/* ==================================================================
 * 结果集(首扫 / 续扫共享同一个活动结果集)
 * ================================================================== */

typedef struct {
    uintptr_t *addr;
    size_t count;
    size_t cap;
} mk_resultset;

static mk_resultset g_rs;

static void rs_clear(void) {
    free(g_rs.addr);
    g_rs.addr = NULL;
    g_rs.count = 0;
    g_rs.cap = 0;
}

static int rs_push(uintptr_t a) {
    if (g_rs.count == g_rs.cap) {
        size_t cap = g_rs.cap ? g_rs.cap * 2 : 1024;
        uintptr_t *t = (uintptr_t *) realloc(g_rs.addr, cap * sizeof *t);
        if (!t) return -1;
        g_rs.addr = t;
        g_rs.cap = cap;
    }
    g_rs.addr[g_rs.count++] = a;
    return 0;
}

/* ==================================================================
 * 扫描引擎
 * ================================================================== */

/*
 * 对齐过滤只对 >=4 字节的类型有意义:
 *   byte(1) / word(2) / utf8(0) 可能落在任意地址,按 4 字节对齐会漏掉绝大多数匹配。
 * dword/qword/float/double 才应用对齐。
 */
static uint32_t mk_effective_flags(int type, uint32_t flags) {
    if (mk_type_size(type) < 4) return flags & ~1u;
    return flags;
}

/* 某个区域是否可被扫描:需要 r 权限,且不是 [vvar]/[vsyscall] 这类不可读映射 */
static int region_scannable(const mk_region *r) {
    if (r->perms[0] != 'r') return 0;
    if (r->path[0] == '[' && strncmp(r->path, "[v", 2) == 0) return 0;
    return r->end > r->start;
}

/*
 * 在单个区域内搜索 val/valLen。
 * limit 为全局上限,命中数达到即停止,返回 1 表示"已触及上限"。
 */
static int scan_region(pid_t pid, const mk_region *r, const uint8_t *val, size_t valLen,
                       size_t limit, uint32_t flags) {
    size_t bufsz = MK_SCAN_BUF;
    if (bufsz < valLen * 2) bufsz = valLen * 2;
    uint8_t *buf = (uint8_t *) malloc(bufsz);
    if (!buf) return 0;

    uintptr_t cur = r->start;
    int hit_limit = 0;

    while (cur < r->end) {
        size_t want = bufsz;
        if ((uintptr_t) want > r->end - cur) want = (size_t) (r->end - cur);

        ssize_t got = mk_read_mem(pid, cur, buf, want);
        if (got <= 0) {
            /* 该块不可读(如 guard page),按页跳过 */
            cur += mk_page_size();
            continue;
        }
        size_t span = (size_t) got;
        if (span < valLen) {
            /*
             * 本块不足一个匹配宽度:通常是被未映射页截断,而非区域结束。
             * 必须前进后继续,不能 break——否则一个坏页会让整个区域剩余部分被跳过。
             */
            cur += span;
            continue;
        }

        for (size_t i = 0; i + valLen <= span; i++) {
            if (flags & 1u) {            /* 结果必须 4 字节对齐 */
                if (((cur + i) & 3u) != 0) continue;
            }
            if (memcmp(buf + i, val, valLen) != 0) continue;
            if (rs_push(cur + i) != 0) {
                free(buf);
                return 1;
            }
            if (g_rs.count >= limit) {
                hit_limit = 1;
                break;
            }
        }

        if (hit_limit) break;

        /* 保留 valLen-1 字节重叠,避免漏掉跨块匹配 */
        size_t step = span - (valLen - 1);
        if (step == 0) step = 1;
        cur += step;
    }

    free(buf);
    return hit_limit;
}

/* 首扫:遍历可扫描区域,收集所有匹配地址 */
static size_t mk_scan_first(pid_t pid, const uint8_t *val, size_t valLen,
                            size_t limit, uintptr_t start, uintptr_t end, uint32_t flags) {
    rs_clear();
    mk_region *regs = NULL;
    size_t n = 0;
    if (mk_read_maps(pid, &regs, &n) != 0) return 0;

    for (size_t i = 0; i < n; i++) {
        const mk_region *r = &regs[i];
        if (!region_scannable(r)) continue;
        /* 只保留与 [start,end] 有交集的区域 */
        if (start && r->end <= start) continue;
        if (end && r->start >= end) continue;

        mk_region sub = *r;
        if (start && sub.start < start) sub.start = start;
        if (end && sub.end > end) sub.end = end;
        if (sub.end <= sub.start) continue;

        if (scan_region(pid, &sub, val, valLen, limit, flags)) break;
    }

    free(regs);
    return g_rs.count;
}

/* 续扫:在已有结果集上按新值过滤,原地压缩 */
static size_t mk_scan_next(pid_t pid, const uint8_t *val, size_t valLen) {
    if (g_rs.count == 0) return 0;
    size_t w = 0;
    uint8_t buf[64];

    /* valLen == 0:不比较数值,只保留"仍可读"的地址(用于淘汰已解除映射的命中) */
    if (valLen == 0) {
        for (size_t i = 0; i < g_rs.count; i++) {
            if (mk_read_mem(pid, g_rs.addr[i], buf, 1) != 1) continue;
            g_rs.addr[w++] = g_rs.addr[i];
        }
        g_rs.count = w;
        return w;
    }

    for (size_t i = 0; i < g_rs.count; i++) {
        if (valLen > sizeof buf) break;
        ssize_t got = mk_read_mem(pid, g_rs.addr[i], buf, valLen);
        if (got < (ssize_t) valLen) continue;
        if (memcmp(buf, val, valLen) == 0) g_rs.addr[w++] = g_rs.addr[i];
    }
    g_rs.count = w;
    return w;
}

/* ==================================================================
 * 类型名 <-> 类型码
 * ================================================================== */

static int mk_type_of_name(const char *n) {
    if (!n) return 0;
    if (!strcmp(n, "byte") || !strcmp(n, "i8")) return MK_T_I8;
    if (!strcmp(n, "word") || !strcmp(n, "i16")) return MK_T_I16;
    if (!strcmp(n, "dword") || !strcmp(n, "i32") || !strcmp(n, "int")) return MK_T_I32;
    if (!strcmp(n, "qword") || !strcmp(n, "i64") || !strcmp(n, "long")) return MK_T_I64;
    if (!strcmp(n, "float") || !strcmp(n, "f32")) return MK_T_F32;
    if (!strcmp(n, "double") || !strcmp(n, "f64")) return MK_T_F64;
    if (!strcmp(n, "utf8") || !strcmp(n, "string") || !strcmp(n, "str")) return MK_T_STR;
    return 0;
}

static const char *mk_name_of_type(int t) {
    switch (t) {
        case MK_T_I8: return "byte";
        case MK_T_I16: return "word";
        case MK_T_I32: return "dword";
        case MK_T_I64: return "qword";
        case MK_T_F32: return "float";
        case MK_T_F64: return "double";
        case MK_T_STR: return "utf8";
        default: return "unknown";
    }
}

/* ==================================================================
 * 十六进制编解码(JNI 侧用字符串传原始字节)
 * ================================================================== */

static int hex_val(int c) {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    return -1;
}

static uint8_t *hex_decode(const char *s, size_t *outLen) {
    *outLen = 0;
    if (!s) return NULL;
    size_t n = strlen(s);
    size_t cap = n / 2 + 1;
    uint8_t *out = (uint8_t *) malloc(cap);
    if (!out) return NULL;
    size_t w = 0;
    for (size_t i = 0; i + 1 < n; i++) {
        int hi = hex_val((unsigned char) s[i]);
        int lo = hex_val((unsigned char) s[i + 1]);
        if (hi < 0 || lo < 0) continue;   /* 容忍空格等分隔符 */
        out[w++] = (uint8_t) ((hi << 4) | lo);
        i++;
    }
    *outLen = w;
    return out;
}

/* ==================================================================
 * JNI 实现体
 * ================================================================== */

/*
 * 单点读写失败时抛 Java 异常,而不是静默返回 0。
 * 静默 0 会让调用方把"读取失败"误当成"读到了 0",掩盖真实问题。
 * 批量扫描不走这里(部分区域不可读是常态)。
 */
static void jni_throw_io(JNIEnv *env, const char *op, pid_t pid, uintptr_t addr,
                         size_t len, int err) {
    char msg[256];
    snprintf(msg, sizeof msg,
             "memkit: %s 失败 pid=%d addr=0x%llx len=%zu errno=%d(%s)",
             op, (int) pid, (unsigned long long) addr, len, err, strerror(err));
    jclass cls = (*env)->FindClass(env, "java/lang/IllegalStateException");
    if (cls) (*env)->ThrowNew(env, cls, msg);
}

static ssize_t jni_read(pid_t pid, uintptr_t addr, void *buf, size_t len) {
    return mk_read_mem(pid, addr, buf, len);
}

static ssize_t jni_write(pid_t pid, uintptr_t addr, const void *buf, size_t len) {
    return mk_write_mem(pid, addr, buf, len);
}

/*
 * 哨兵值:告知续扫"不比较数值,只保留仍可读的地址"。
 * 用不可打印字节包裹,避免与任何合法搜索串冲突。
 */
static const char MK_UNKNOWN[] = "\x01memkit:any\x01";

static int mk_is_unknown(const char *s) {
    return s && strcmp(s, MK_UNKNOWN) == 0;
}

JNIEXPORT jstring JNICALL
Java_com_luaforge_studio_utils_MemUtil_nMemVersion(JNIEnv *env, jobject thiz) {
    (void) thiz;
    return (*env)->NewStringUTF(env, MK_VERSION);
}

JNIEXPORT jboolean JNICALL
Java_com_luaforge_studio_utils_MemUtil_nMemIsRoot(JNIEnv *env, jobject thiz) {
    (void) env;
    (void) thiz;
    return mk_is_root_fast() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_luaforge_studio_utils_MemUtil_nMemSelfPid(JNIEnv *env, jobject thiz) {
    (void) env;
    (void) thiz;
    return (jint) getpid();
}

JNIEXPORT jint JNICALL
Java_com_luaforge_studio_utils_MemUtil_nMemFindPid(JNIEnv *env, jobject thiz, jstring name) {
    (void) thiz;
    if (!name) return -1;
    const char *c = (*env)->GetStringUTFChars(env, name, NULL);
    if (!c) return -1;
    int pid = mk_pid_of(c);
    (*env)->ReleaseStringUTFChars(env, name, c);
    return (jint) pid;
}

JNIEXPORT jstring JNICALL
Java_com_luaforge_studio_utils_MemUtil_nMemRegions(JNIEnv *env, jobject thiz, jint pid) {
    (void) thiz;
    pid_t p = (pid <= 0) ? getpid() : (pid_t) pid;
    mk_region *regs = NULL;
    size_t n = 0;
    if (mk_read_maps(p, &regs, &n) != 0) return (*env)->NewStringUTF(env, "[]");

    mk_sb sb;
    sb_init(&sb);
    sb_putc(&sb, '[');
    for (size_t i = 0; i < n; i++) {
        if (i) sb_putc(&sb, ',');
        sb_printf(&sb, "{\"start\":%llu,\"end\":%llu,\"perms\":",
                  (unsigned long long) regs[i].start, (unsigned long long) regs[i].end);
        sb_json_str(&sb, regs[i].perms);
        sb_puts(&sb, ",\"path\":");
        sb_json_str(&sb, regs[i].path);
        sb_putc(&sb, '}');
    }
    sb_putc(&sb, ']');
    free(regs);

    jstring r = (*env)->NewStringUTF(env, sb.p ? sb.p : "[]");
    sb_free(&sb);
    return r;
}

JNIEXPORT jstring JNICALL
Java_com_luaforge_studio_utils_MemUtil_nMemReadBytes(JNIEnv *env, jobject thiz,
                                                    jint pid, jlong addr, jint len) {
    (void) thiz;
    if (len <= 0) return (*env)->NewStringUTF(env, "");
    if ((unsigned int) len > MK_MAX_LIMIT) len = (jint) MK_MAX_LIMIT;
    pid_t p = (pid <= 0) ? getpid() : (pid_t) pid;
    uint8_t *buf = (uint8_t *) malloc((size_t) len);
    if (!buf) return (*env)->NewStringUTF(env, "");

    ssize_t got = jni_read(p, (uintptr_t) addr, buf, (size_t) len);
    if (got <= 0) {
        int e = errno;
        free(buf);
        jni_throw_io(env, "read", p, (uintptr_t) addr, (size_t) len, e);
        return NULL;
    }
    mk_sb sb;
    sb_init(&sb);
    sb_hex(&sb, buf, (size_t) got);
    free(buf);

    jstring r = (*env)->NewStringUTF(env, sb.p ? sb.p : "");
    sb_free(&sb);
    return r;
}

JNIEXPORT jlong JNICALL
Java_com_luaforge_studio_utils_MemUtil_nMemReadInt(JNIEnv *env, jobject thiz,
                                                  jint pid, jlong addr, jint size) {
    (void) thiz;
    if (size != 1 && size != 2 && size != 4 && size != 8) return 0;
    pid_t p = (pid <= 0) ? getpid() : (pid_t) pid;
    uint8_t buf[8] = {0};
    ssize_t got = jni_read(p, (uintptr_t) addr, buf, (size_t) size);
    if (got != size) {
        jni_throw_io(env, "read", p, (uintptr_t) addr, (size_t) size, errno);
        return 0;
    }

    uint64_t v = 0;
    memcpy(&v, buf, (size_t) size);
    /* 按位宽做符号扩展,方便 Lua 侧直接看到负数 */
    if (size == 1) return (jlong) (int8_t) v;
    if (size == 2) return (jlong) (int16_t) v;
    if (size == 4) return (jlong) (int32_t) v;
    return (jlong) (int64_t) v;
}

JNIEXPORT jdouble JNICALL
Java_com_luaforge_studio_utils_MemUtil_nMemReadFloat(JNIEnv *env, jobject thiz,
                                                    jint pid, jlong addr, jboolean isDouble) {
    (void) thiz;
    pid_t p = (pid <= 0) ? getpid() : (pid_t) pid;
    if (isDouble) {
        double d = 0;
        if (jni_read(p, (uintptr_t) addr, &d, 8) != 8) {
            jni_throw_io(env, "read", p, (uintptr_t) addr, 8, errno);
            return 0.0;
        }
        return (jdouble) d;
    }
    float f = 0;
    if (jni_read(p, (uintptr_t) addr, &f, 4) != 4) {
        jni_throw_io(env, "read", p, (uintptr_t) addr, 4, errno);
        return 0.0;
    }
    return (jdouble) f;
}

JNIEXPORT jstring JNICALL
Java_com_luaforge_studio_utils_MemUtil_nMemReadString(JNIEnv *env, jobject thiz,
                                                     jint pid, jlong addr, jint maxLen) {
    (void) thiz;
    if (maxLen <= 0) return (*env)->NewStringUTF(env, "");
    if (maxLen > 65536) maxLen = 65536;
    pid_t p = (pid <= 0) ? getpid() : (pid_t) pid;
    char *buf = (char *) malloc((size_t) maxLen + 1);
    if (!buf) return (*env)->NewStringUTF(env, "");

    ssize_t got = jni_read(p, (uintptr_t) addr, buf, (size_t) maxLen);
    if (got <= 0) {
        int e = errno;
        free(buf);
        jni_throw_io(env, "read", p, (uintptr_t) addr, (size_t) maxLen, e);
        return NULL;
    }
    size_t limit = (size_t) got;
    size_t n = 0;
    while (n < limit && buf[n] != '\0') n++;
    buf[n] = '\0';

    jstring r = (*env)->NewStringUTF(env, buf);
    free(buf);
    return r;
}

JNIEXPORT jint JNICALL
Java_com_luaforge_studio_utils_MemUtil_nMemWriteBytes(JNIEnv *env, jobject thiz,
                                                     jint pid, jlong addr, jstring hex) {
    (void) thiz;
    if (!hex) return 0;
    const char *c = (*env)->GetStringUTFChars(env, hex, NULL);
    if (!c) return 0;
    size_t n = 0;
    uint8_t *raw = hex_decode(c, &n);
    (*env)->ReleaseStringUTFChars(env, hex, c);
    if (!raw || n == 0) {
        free(raw);
        return 0;
    }
    pid_t p = (pid <= 0) ? getpid() : (pid_t) pid;
    ssize_t wrote = jni_write(p, (uintptr_t) addr, raw, n);
    if (wrote <= 0) {
        int e = errno;
        free(raw);
        jni_throw_io(env, "write", p, (uintptr_t) addr, n, e);
        return 0;
    }
    free(raw);
    return (jint) wrote;
}

JNIEXPORT jint JNICALL
Java_com_luaforge_studio_utils_MemUtil_nMemWriteInt(JNIEnv *env, jobject thiz,
                                                   jint pid, jlong addr, jlong value, jint size) {
    (void) thiz;
    if (size != 1 && size != 2 && size != 4 && size != 8) return 0;
    pid_t p = (pid <= 0) ? getpid() : (pid_t) pid;
    uint64_t v = (uint64_t) value;
    ssize_t wrote = jni_write(p, (uintptr_t) addr, &v, (size_t) size);
    if (wrote <= 0) {
        jni_throw_io(env, "write", p, (uintptr_t) addr, (size_t) size, errno);
        return 0;
    }
    return (jint) wrote;
}

JNIEXPORT jint JNICALL
Java_com_luaforge_studio_utils_MemUtil_nMemWriteFloat(JNIEnv *env, jobject thiz,
                                                     jint pid, jlong addr, jdouble value,
                                                     jboolean isDouble) {
    (void) thiz;
    pid_t p = (pid <= 0) ? getpid() : (pid_t) pid;
    if (isDouble) {
        double d = (double) value;
        ssize_t wrote = jni_write(p, (uintptr_t) addr, &d, 8);
        if (wrote <= 0) {
            jni_throw_io(env, "write", p, (uintptr_t) addr, 8, errno);
            return 0;
        }
        return (jint) wrote;
    }
    float f = (float) value;
    ssize_t wrote = jni_write(p, (uintptr_t) addr, &f, 4);
    if (wrote <= 0) {
        jni_throw_io(env, "write", p, (uintptr_t) addr, 4, errno);
        return 0;
    }
    return (jint) wrote;
}

JNIEXPORT jint JNICALL
Java_com_luaforge_studio_utils_MemUtil_nMemScan(JNIEnv *env, jobject thiz,
                                               jint pid, jstring typeName, jstring value,
                                               jint limit, jlong start, jlong end,
                                               jboolean align4) {
    (void) thiz;
    if (!typeName || !value) return 0;
    const char *tn = (*env)->GetStringUTFChars(env, typeName, NULL);
    const char *vs = (*env)->GetStringUTFChars(env, value, NULL);
    if (!tn || !vs) {
        if (tn) (*env)->ReleaseStringUTFChars(env, typeName, tn);
        if (vs) (*env)->ReleaseStringUTFChars(env, value, vs);
        return 0;
    }
    int type = mk_type_of_name(tn);
    (*env)->ReleaseStringUTFChars(env, typeName, tn);

    uint8_t enc[4096];
    int encLen = mk_encode_value(type, vs, enc, sizeof enc);
    (*env)->ReleaseStringUTFChars(env, value, vs);
    if (type == 0 || encLen <= 0) return 0;

    if (limit <= 0) limit = (jint) MK_DEFAULT_LIMIT;
    if ((unsigned) limit > MK_MAX_LIMIT) limit = (jint) MK_MAX_LIMIT;
    pid_t p = (pid <= 0) ? getpid() : (pid_t) pid;

    size_t n = mk_scan_first(p, enc, (size_t) encLen, (size_t) limit,
                             (uintptr_t) start, (uintptr_t) end,
                             mk_effective_flags(type, align4 ? 1u : 0u));
    return (jint) n;
}

JNIEXPORT jint JNICALL
Java_com_luaforge_studio_utils_MemUtil_nMemScanNext(JNIEnv *env, jobject thiz,
                                                   jint pid, jstring typeName, jstring value) {
    (void) thiz;
    if (!typeName || !value) return 0;
    const char *tn = (*env)->GetStringUTFChars(env, typeName, NULL);
    const char *vs = (*env)->GetStringUTFChars(env, value, NULL);
    if (!tn || !vs) {
        if (tn) (*env)->ReleaseStringUTFChars(env, typeName, tn);
        if (vs) (*env)->ReleaseStringUTFChars(env, value, vs);
        return 0;
    }
    int type = mk_type_of_name(tn);
    (*env)->ReleaseStringUTFChars(env, typeName, tn);
    if (type == 0) {
        (*env)->ReleaseStringUTFChars(env, value, vs);
        return 0;
    }

    pid_t p = (pid <= 0) ? getpid() : (pid_t) pid;
    if (mk_is_unknown(vs)) {
        (*env)->ReleaseStringUTFChars(env, value, vs);
        return (jint) mk_scan_next(p, NULL, 0);
    }

    uint8_t enc[4096];
    int encLen = mk_encode_value(type, vs, enc, sizeof enc);
    (*env)->ReleaseStringUTFChars(env, value, vs);
    if (encLen <= 0) return 0;

    return (jint) mk_scan_next(p, enc, (size_t) encLen);
}

JNIEXPORT jint JNICALL
Java_com_luaforge_studio_utils_MemUtil_nMemScanCount(JNIEnv *env, jobject thiz) {
    (void) env;
    (void) thiz;
    return (jint) g_rs.count;
}

JNIEXPORT jlong JNICALL
Java_com_luaforge_studio_utils_MemUtil_nMemScanResult(JNIEnv *env, jobject thiz, jint index) {
    (void) env;
    (void) thiz;
    if (index < 0 || (size_t) index >= g_rs.count) return 0;
    return (jlong) g_rs.addr[index];
}

JNIEXPORT jstring JNICALL
Java_com_luaforge_studio_utils_MemUtil_nMemScanResults(JNIEnv *env, jobject thiz,
                                                      jint offset, jint count) {
    (void) thiz;
    if (offset < 0) offset = 0;
    if ((size_t) offset >= g_rs.count) return (*env)->NewStringUTF(env, "[]");
    size_t begin = (size_t) offset;
    size_t avail = g_rs.count - begin;
    size_t want = (count <= 0 || (size_t) count > avail) ? avail : (size_t) count;

    mk_sb sb;
    sb_init(&sb);
    sb_putc(&sb, '[');
    for (size_t i = 0; i < want; i++) {
        if (i) sb_putc(&sb, ',');
        sb_printf(&sb, "%llu", (unsigned long long) g_rs.addr[begin + i]);
    }
    sb_putc(&sb, ']');

    jstring r = (*env)->NewStringUTF(env, sb.p ? sb.p : "[]");
    sb_free(&sb);
    return r;
}

JNIEXPORT jint JNICALL
Java_com_luaforge_studio_utils_MemUtil_nMemScanClear(JNIEnv *env, jobject thiz) {
    (void) env;
    (void) thiz;
    int n = (int) g_rs.count;
    rs_clear();
    return n;
}

/* GG 式「把所有结果改成某值」,返回成功写入的个数 */
JNIEXPORT jint JNICALL
Java_com_luaforge_studio_utils_MemUtil_nMemScanWriteAll(JNIEnv *env, jobject thiz,
                                                          jint pid, jstring typeName,
                                                          jstring value) {
    (void) thiz;
    if (!typeName || !value || g_rs.count == 0) return 0;
    const char *tn = (*env)->GetStringUTFChars(env, typeName, NULL);
    const char *vs = (*env)->GetStringUTFChars(env, value, NULL);
    if (!tn || !vs) {
        if (tn) (*env)->ReleaseStringUTFChars(env, typeName, tn);
        if (vs) (*env)->ReleaseStringUTFChars(env, value, vs);
        return 0;
    }
    int type = mk_type_of_name(tn);
    (*env)->ReleaseStringUTFChars(env, typeName, tn);

    uint8_t enc[4096];
    int encLen = mk_encode_value(type, vs, enc, sizeof enc);
    (*env)->ReleaseStringUTFChars(env, value, vs);
    if (type == 0 || encLen <= 0) return 0;

    pid_t p = (pid <= 0) ? getpid() : (pid_t) pid;
    int ok = 0;
    for (size_t i = 0; i < g_rs.count; i++) {
        ssize_t w = jni_write(p, g_rs.addr[i], enc, (size_t) encLen);
        if (w == encLen) ok++;
    }
    return (jint) ok;
}

/* 按结果集当前位置重新读取,返回 JSON [[addr,"value"],...] */
JNIEXPORT jstring JNICALL
Java_com_luaforge_studio_utils_MemUtil_nMemScanRefresh(JNIEnv *env, jobject thiz,
                                                      jint pid, jstring typeName, jint limit) {
    (void) thiz;
    if (!typeName || g_rs.count == 0) return (*env)->NewStringUTF(env, "[]");
    const char *tn = (*env)->GetStringUTFChars(env, typeName, NULL);
    if (!tn) return (*env)->NewStringUTF(env, "[]");
    int type = mk_type_of_name(tn);
    (*env)->ReleaseStringUTFChars(env, typeName, tn);
    if (type == 0) return (*env)->NewStringUTF(env, "[]");

    size_t want = g_rs.count;
    if (limit > 0 && (size_t) limit < want) want = (size_t) limit;

    pid_t p = (pid <= 0) ? getpid() : (pid_t) pid;
    size_t vsz = (type == MK_T_STR) ? 64 : mk_type_size(type);
    if (vsz == 0 || vsz > 64) vsz = 64;

    mk_sb sb;
    sb_init(&sb);
    sb_putc(&sb, '[');
    size_t emitted = 0;
    for (size_t i = 0; i < want; i++) {
        uint8_t buf[64];
        if (jni_read(p, g_rs.addr[i], buf, vsz) != (ssize_t) vsz) continue;
        char txt[128];
        if (type == MK_T_STR) {
            size_t n = 0;
            while (n < vsz && buf[n]) n++;
            if (n >= sizeof txt) n = sizeof txt - 1;
            memcpy(txt, buf, n);
            txt[n] = '\0';
        } else {
            mk_format_value(type, buf, vsz, txt, sizeof txt);
        }
        if (emitted++) sb_putc(&sb, ',');
        sb_printf(&sb, "[%llu,", (unsigned long long) g_rs.addr[i]);
        sb_json_str(&sb, txt);
        sb_putc(&sb, ']');
    }
    sb_putc(&sb, ']');

    jstring r = (*env)->NewStringUTF(env, sb.p ? sb.p : "[]");
    sb_free(&sb);
    return r;
}

/* 元信息:供 Lua/MCP 侧展示当前类型体系 */
JNIEXPORT jstring JNICALL
Java_com_luaforge_studio_utils_MemUtil_nMemTypeInfo(JNIEnv *env, jobject thiz) {
    (void) thiz;
    mk_sb sb;
    sb_init(&sb);
    sb_putc(&sb, '[');
    static const int CODES[] = {MK_T_I8, MK_T_I16, MK_T_I32, MK_T_I64, MK_T_F32, MK_T_F64, MK_T_STR};
    for (size_t i = 0; i < sizeof(CODES) / sizeof(CODES[0]); i++) {
        if (i) sb_putc(&sb, ',');
        sb_puts(&sb, "{\"name\":");
        sb_json_str(&sb, mk_name_of_type(CODES[i]));
        sb_printf(&sb, ",\"size\":%d}", (int) (CODES[i] == MK_T_STR ? 0 : mk_type_size(CODES[i])));
    }
    sb_putc(&sb, ']');
    jstring r = (*env)->NewStringUTF(env, sb.p ? sb.p : "[]");
    sb_free(&sb);
    return r;
}

/* ==================================================================
 * Lua 绑定(require "memkit")
 * ================================================================== */

/* 从可选参数取 pid:缺省或 <=0 表示当前进程 */
static pid_t mk_lua_pid(lua_State *L, int idx) {
    if (lua_isnoneornil(L, idx)) return getpid();
    lua_Integer v = luaL_checkinteger(L, idx);
    if (v <= 0) return getpid();
    return (pid_t) v;
}

/* 把 Lua 值按类型编码为用于比较/写入的原始字节,返回长度;0 表示失败 */
static int mk_encode_from_lua(lua_State *L, int idx, int type, uint8_t *out, size_t cap) {
    if (type == MK_T_STR) {
        size_t n = 0;
        const char *s = luaL_checklstring(L, idx, &n);
        if (n > cap) n = cap;
        memcpy(out, s, n);
        return (int) n;
    }
    size_t sz = mk_type_size(type);
    if (sz == 0 || sz > cap) return 0;
    if (type == MK_T_F32) {
        float f = (float) luaL_checknumber(L, idx);
        memcpy(out, &f, 4);
        return 4;
    }
    if (type == MK_T_F64) {
        double d = (double) luaL_checknumber(L, idx);
        memcpy(out, &d, 8);
        return 8;
    }
    uint64_t v = (uint64_t) (int64_t) luaL_checkinteger(L, idx);
    memcpy(out, &v, sz);
    return (int) sz;
}

static int l_mk_version(lua_State *L) {
    lua_pushstring(L, MK_VERSION);
    return 1;
}

static int l_mk_isRoot(lua_State *L) {
    lua_pushboolean(L, mk_is_root_fast());
    return 1;
}

static int l_mk_selfPid(lua_State *L) {
    lua_pushinteger(L, (lua_Integer) getpid());
    return 1;
}

static int l_mk_findPid(lua_State *L) {
    const char *name = luaL_checkstring(L, 1);
    lua_pushinteger(L, (lua_Integer) mk_pid_of(name));
    return 1;
}

static int l_mk_regions(lua_State *L) {
    pid_t p = mk_lua_pid(L, 1);
    mk_region *regs = NULL;
    size_t n = 0;
    if (mk_read_maps(p, &regs, &n) != 0 || !regs) {
        lua_newtable(L);
        return 1;
    }
    lua_createtable(L, (int) n, 0);
    for (size_t i = 0; i < n; i++) {
        lua_createtable(L, 0, 4);
        lua_pushinteger(L, (lua_Integer) regs[i].start);
        lua_setfield(L, -2, "start");
        lua_pushinteger(L, (lua_Integer) regs[i].end);
        lua_setfield(L, -2, "end");
        lua_pushinteger(L, (lua_Integer) (regs[i].end - regs[i].start));
        lua_setfield(L, -2, "size");
        lua_pushstring(L, regs[i].perms);
        lua_setfield(L, -2, "perms");
        lua_pushstring(L, regs[i].path);
        lua_setfield(L, -2, "path");
        lua_rawseti(L, -2, (lua_Integer) i + 1);
    }
    free(regs);
    return 1;
}

static int l_mk_readBytes(lua_State *L) {
    lua_Integer addr = luaL_checkinteger(L, 1);
    lua_Integer len = luaL_checkinteger(L, 2);
    pid_t p = mk_lua_pid(L, 3);
    if (len <= 0 || len > MK_MAX_LIMIT) return luaL_error(L, "memkit: len 必须在 1..%u", MK_MAX_LIMIT);

    uint8_t *buf = (uint8_t *) malloc((size_t) len);
    if (!buf) return luaL_error(L, "memkit: 内存不足");
    ssize_t got = jni_read(p, (uintptr_t) addr, buf, (size_t) len);
    if (got <= 0) {
        free(buf);
        lua_pushstring(L, "");
        return 1;
    }
    mk_sb sb;
    sb_init(&sb);
    sb_hex(&sb, buf, (size_t) got);
    free(buf);
    lua_pushlstring(L, sb.p ? sb.p : "", sb.p ? sb.len : 0);
    sb_free(&sb);
    return 1;
}

static int l_mk_readInt(lua_State *L) {
    lua_Integer addr = luaL_checkinteger(L, 1);
    lua_Integer size = luaL_checkinteger(L, 2);
    pid_t p = mk_lua_pid(L, 3);
    if (size != 1 && size != 2 && size != 4 && size != 8) return luaL_error(L, "memkit: size 必须为 1/2/4/8");
    uint8_t buf[8] = {0};
    if (jni_read(p, (uintptr_t) addr, buf, (size_t) size) != (ssize_t) size) {
        lua_pushnil(L);
        return 1;
    }
    uint64_t v = 0;
    memcpy(&v, buf, (size_t) size);
    switch (size) {
        case 1: v = (uint64_t) (int64_t) (int8_t) v; break;
        case 2: v = (uint64_t) (int64_t) (int16_t) v; break;
        case 4: v = (uint64_t) (int64_t) (int32_t) v; break;
        default: break;
    }
    lua_pushinteger(L, (lua_Integer) (int64_t) v);
    return 1;
}

static int l_mk_readFloat(lua_State *L) {
    lua_Integer addr = luaL_checkinteger(L, 1);
    lua_Integer size = luaL_optinteger(L, 2, 4);
    pid_t p = mk_lua_pid(L, 3);
    if (size == 8) {
        double d = 0;
        if (jni_read(p, (uintptr_t) addr, &d, 8) != 8) { lua_pushnil(L); return 1; }
        lua_pushnumber(L, (lua_Number) d);
        return 1;
    }
    float f = 0;
    if (jni_read(p, (uintptr_t) addr, &f, 4) != 4) { lua_pushnil(L); return 1; }
    lua_pushnumber(L, (lua_Number) f);
    return 1;
}

static int l_mk_readString(lua_State *L) {
    lua_Integer addr = luaL_checkinteger(L, 1);
    lua_Integer maxLen = luaL_optinteger(L, 2, 256);
    pid_t p = mk_lua_pid(L, 3);
    if (maxLen <= 0) maxLen = 256;
    if (maxLen > 65536) maxLen = 65536;
    char *buf = (char *) malloc((size_t) maxLen + 1);
    if (!buf) return luaL_error(L, "memkit: 内存不足");
    ssize_t got = jni_read(p, (uintptr_t) addr, buf, (size_t) maxLen);
    if (got <= 0) {
        free(buf);
        lua_pushstring(L, "");
        return 1;
    }
    size_t limit = (size_t) got, n = 0;
    while (n < limit && buf[n]) n++;
    lua_pushlstring(L, buf, n);
    free(buf);
    return 1;
}

static int l_mk_writeBytes(lua_State *L) {
    lua_Integer addr = luaL_checkinteger(L, 1);
    size_t n = 0;
    const char *hex = luaL_checklstring(L, 2, &n);
    pid_t p = mk_lua_pid(L, 3);

    size_t rawLen = 0;
    uint8_t *raw = hex_decode(hex, &rawLen);
    if (!raw || rawLen == 0) {
        free(raw);
        lua_pushinteger(L, 0);
        return 1;
    }
    ssize_t wrote = jni_write(p, (uintptr_t) addr, raw, rawLen);
    free(raw);
    lua_pushinteger(L, (lua_Integer) (wrote > 0 ? wrote : 0));
    return 1;
}

static int l_mk_writeInt(lua_State *L) {
    lua_Integer addr = luaL_checkinteger(L, 1);
    lua_Integer value = luaL_checkinteger(L, 2);
    lua_Integer size = luaL_optinteger(L, 3, 4);
    pid_t p = mk_lua_pid(L, 4);
    if (size != 1 && size != 2 && size != 4 && size != 8) return luaL_error(L, "memkit: size 必须为 1/2/4/8");
    uint64_t v = (uint64_t) value;
    ssize_t wrote = jni_write(p, (uintptr_t) addr, &v, (size_t) size);
    lua_pushinteger(L, (lua_Integer) (wrote > 0 ? wrote : 0));
    return 1;
}

static int l_mk_writeFloat(lua_State *L) {
    lua_Integer addr = luaL_checkinteger(L, 1);
    lua_Number value = luaL_checknumber(L, 2);
    lua_Integer size = luaL_optinteger(L, 3, 4);
    pid_t p = mk_lua_pid(L, 4);
    if (size == 8) {
        double d = (double) value;
        ssize_t wrote = jni_write(p, (uintptr_t) addr, &d, 8);
        lua_pushinteger(L, (lua_Integer) (wrote > 0 ? wrote : 0));
        return 1;
    }
    float f = (float) value;
    ssize_t wrote = jni_write(p, (uintptr_t) addr, &f, 4);
    lua_pushinteger(L, (lua_Integer) (wrote > 0 ? wrote : 0));
    return 1;
}

/* 统一解析 scan 的 opts 表:{pid=, limit=, start=, end=, align4=} */
static void mk_lua_scan_opts(lua_State *L, int idx, pid_t *pid, size_t *limit,
                             uintptr_t *start, uintptr_t *end, uint32_t *flags) {
    *pid = getpid();
    *limit = MK_DEFAULT_LIMIT;
    *start = 0;
    *end = 0;
    *flags = 1;   /* 默认 4 字节对齐,符合 GG 常见行为 */
    if (!lua_istable(L, idx)) return;

    lua_getfield(L, idx, "pid");
    if (lua_isinteger(L, -1)) {
        lua_Integer v = lua_tointeger(L, -1);
        if (v > 0) *pid = (pid_t) v;
    }
    lua_pop(L, 1);

    lua_getfield(L, idx, "limit");
    if (lua_isinteger(L, -1)) {
        lua_Integer v = lua_tointeger(L, -1);
        if (v > 0) *limit = (v > (lua_Integer) MK_MAX_LIMIT) ? MK_MAX_LIMIT : (size_t) v;
    }
    lua_pop(L, 1);

    lua_getfield(L, idx, "start");
    if (lua_isinteger(L, -1)) *start = (uintptr_t) lua_tointeger(L, -1);
    lua_pop(L, 1);

    lua_getfield(L, idx, "end");
    if (lua_isinteger(L, -1)) *end = (uintptr_t) lua_tointeger(L, -1);
    lua_pop(L, 1);

    lua_getfield(L, idx, "align4");
    if (lua_isboolean(L, -1)) *flags = lua_toboolean(L, -1) ? 1u : 0u;
    lua_pop(L, 1);
}

static int l_mk_scan(lua_State *L) {
    const char *typeName = luaL_checkstring(L, 1);
    int type = mk_type_of_name(typeName);
    if (type == 0) return luaL_error(L, "memkit: 未知类型 '%s'", typeName);

    uint8_t enc[4096];
    int encLen = mk_encode_from_lua(L, 2, type, enc, sizeof enc);
    if (encLen <= 0) return luaL_error(L, "memkit: 无法编码待搜索的值");

    pid_t pid;
    size_t limit;
    uintptr_t start, end;
    uint32_t flags;
    mk_lua_scan_opts(L, 3, &pid, &limit, &start, &end, &flags);

    size_t n = mk_scan_first(pid, enc, (size_t) encLen, limit, start, end,
                             mk_effective_flags(type, flags));
    lua_pushinteger(L, (lua_Integer) n);
    return 1;
}

static int l_mk_scanNext(lua_State *L) {
    const char *typeName = luaL_checkstring(L, 1);
    int type = mk_type_of_name(typeName);
    if (type == 0) return luaL_error(L, "memkit: 未知类型 '%s'", typeName);
    pid_t p = mk_lua_pid(L, 3);

    /* 省略 value:只保留仍可读的地址 */
    if (lua_isnoneornil(L, 2)) {
        lua_pushinteger(L, (lua_Integer) mk_scan_next(p, NULL, 0));
        return 1;
    }

    uint8_t enc[4096];
    int encLen = mk_encode_from_lua(L, 2, type, enc, sizeof enc);
    if (encLen <= 0) return luaL_error(L, "memkit: 无法编码待搜索的值");
    lua_pushinteger(L, (lua_Integer) mk_scan_next(p, enc, (size_t) encLen));
    return 1;
}

static int l_mk_count(lua_State *L) {
    lua_pushinteger(L, (lua_Integer) g_rs.count);
    return 1;
}

static int l_mk_result(lua_State *L) {
    lua_Integer i = luaL_checkinteger(L, 1);
    if (i < 1 || (size_t) i > g_rs.count) { lua_pushnil(L); return 1; }
    lua_pushinteger(L, (lua_Integer) g_rs.addr[i - 1]);
    return 1;
}

static int l_mk_results(lua_State *L) {
    lua_Integer offset = luaL_optinteger(L, 1, 1);
    lua_Integer count = luaL_optinteger(L, 2, 0);
    if (offset < 1) offset = 1;
    if ((size_t) offset > g_rs.count) { lua_newtable(L); return 1; }
    size_t begin = (size_t) offset - 1;
    size_t avail = g_rs.count - begin;
    size_t want = (count <= 0 || (size_t) count > avail) ? avail : (size_t) count;

    lua_createtable(L, (int) want, 0);
    for (size_t i = 0; i < want; i++) {
        lua_pushinteger(L, (lua_Integer) g_rs.addr[begin + i]);
        lua_rawseti(L, -2, (lua_Integer) i + 1);
    }
    return 1;
}

static int l_mk_clear(lua_State *L) {
    lua_pushinteger(L, (lua_Integer) g_rs.count);
    rs_clear();
    return 1;
}

static int l_mk_writeAll(lua_State *L) {
    const char *typeName = luaL_checkstring(L, 1);
    int type = mk_type_of_name(typeName);
    if (type == 0) return luaL_error(L, "memkit: 未知类型 '%s'", typeName);

    uint8_t enc[4096];
    int encLen = mk_encode_from_lua(L, 2, type, enc, sizeof enc);
    if (encLen <= 0) return luaL_error(L, "memkit: 无法编码写入值");

    pid_t p = mk_lua_pid(L, 3);
    int ok = 0;
    for (size_t i = 0; i < g_rs.count; i++) {
        ssize_t w = jni_write(p, g_rs.addr[i], enc, (size_t) encLen);
        if (w == encLen) ok++;
    }
    lua_pushinteger(L, (lua_Integer) ok);
    return 1;
}

static int l_mk_refresh(lua_State *L) {
    const char *typeName = luaL_checkstring(L, 1);
    int type = mk_type_of_name(typeName);
    if (type == 0) return luaL_error(L, "memkit: 未知类型 '%s'", typeName);
    pid_t p = mk_lua_pid(L, 2);

    size_t vsz = (type == MK_T_STR) ? 64 : mk_type_size(type);
    if (vsz == 0) vsz = 8;

    lua_createtable(L, (int) g_rs.count, 0);
    for (size_t i = 0; i < g_rs.count; i++) {
        uint8_t buf[64];
        if (jni_read(p, g_rs.addr[i], buf, vsz) != (ssize_t) vsz) continue;

        lua_pushinteger(L, (lua_Integer) g_rs.addr[i]);   /* key: 地址 */
        if (type == MK_T_STR) {
            size_t n = 0;
            while (n < vsz && buf[n]) n++;
            lua_pushlstring(L, (const char *) buf, n);
        } else if (type == MK_T_F32) {
            float f;
            memcpy(&f, buf, 4);
            lua_pushnumber(L, (lua_Number) f);
        } else if (type == MK_T_F64) {
            double d;
            memcpy(&d, buf, 8);
            lua_pushnumber(L, (lua_Number) d);
        } else {
            uint64_t v = 0;
            memcpy(&v, buf, vsz);
            if (type == MK_T_I8) v = (uint64_t) (int64_t) (int8_t) v;
            else if (type == MK_T_I16) v = (uint64_t) (int64_t) (int16_t) v;
            else if (type == MK_T_I32) v = (uint64_t) (int64_t) (int32_t) v;
            lua_pushinteger(L, (lua_Integer) (int64_t) v);
        }
        lua_settable(L, -3);
    }
    return 1;
}

static const luaL_Reg mk_funcs[] = {
        {"version",     l_mk_version},
        {"isRoot",      l_mk_isRoot},
        {"selfPid",     l_mk_selfPid},
        {"findPid",     l_mk_findPid},
        {"regions",     l_mk_regions},
        {"readBytes",   l_mk_readBytes},
        {"readInt",     l_mk_readInt},
        {"readFloat",   l_mk_readFloat},
        {"readString",  l_mk_readString},
        {"writeBytes",  l_mk_writeBytes},
        {"writeInt",    l_mk_writeInt},
        {"writeFloat",  l_mk_writeFloat},
        {"scan",        l_mk_scan},
        {"scanNext",    l_mk_scanNext},
        {"count",       l_mk_count},
        {"result",      l_mk_result},
        {"results",     l_mk_results},
        {"clear",       l_mk_clear},
        {"writeAll",    l_mk_writeAll},
        {"refresh",     l_mk_refresh},
        {NULL, NULL}
};

/* 供 require "memkit" 使用(顶层 CMake 会导出该符号) */
int luaopen_memkit(lua_State *L) {
    luaL_newlib(L, mk_funcs);
    lua_pushstring(L, MK_VERSION);
    lua_setfield(L, -2, "_VERSION");
    return 1;
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void) vm;
    (void) reserved;
    return JNI_VERSION_1_6;
}
