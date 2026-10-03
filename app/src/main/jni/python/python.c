/**
 * @file luapython.c
 * @brief Lua <-> CPython 嵌入桥(单文件 C,无链接期 Python 依赖)
 *
 * ## 两种入口共用同一份静态运行时
 *   - JNI : Java_com_luaforge_studio_utils_PythonUtil_n*  —— 供 global_utils 桥接
 *   - Lua : luaopen_python                                 —— 供 require "python"
 *
 * ## 为什么 dlopen 而不是链接 libpython3.14.so
 *   1) 运行时是 vendor 来的预编译产物,只提供 arm64-v8a(3.12+ 无 32 位)。
 *      链接期依赖会让"Python 不可用"变成 **加载 .so 失败**,进而拖垮整个 app;
 *      dlopen 则退化成一次可预期的可用性检查(armv7 上 pythonAvailable() = false)。
 *   2) 免依赖对方头文件:CPython 3.13 起已把 Py_SetPath 等从公开头文件移除
 *      (符号仍在),自声明最小 ABI 反而更稳,也不受其版本宏改动影响。
 *   3) lib-dynload 下的扩展自身 NEEDED 该 soname,由 app 的 nativeLibraryDir 解析。
 *
 * ## 线程与 Lua 并行
 *   调用 Python 前后用 PyGILState_Ensure/Release 包裹,因此:
 *     - Python 线程之间:受 GIL 串行(标准构建,非 free-threaded)
 *     - Lua 线程 <-> Python 线程:真并行(Lua 侧无 GIL)
 *   本文件不做任何跨语言回调,避免把 Lua 线程安全引入 Python 线程。
 *
 * ## 输出与错误
 *   在 Python 层用 StringIO 接管 stdout/stderr,把整段输出作为返回值带回;
 *   失败时把 traceback 原样抛出(Java 侧 IllegalStateException / Lua 侧 error),
 *   不做静默降级 —— 脚本出错必须让调用方看见。
 */

#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "lua.h"
#include "lauxlib.h"
#include "lualib.h"

#define LOG_TAG "luapython"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

#define PY_VERSION_STRING "3.14"
#define PY_LIB_SONAME     "libpython3.14.so"

/* =====================================================================
 * 1. 最小 ABI 自声明
 *
 * 只声明用到的部分。PyObject 保持不完整类型:凡涉及引用计数一律走导出函数
 * (Py_DecRef 在 3.14 仍是真符号),不触碰结构体布局,避免随版本漂移。
 * ===================================================================== */

typedef struct _object PyObject;
typedef int PyGILState_STATE;

typedef void         (*fn_Py_Initialize)(void);
typedef int          (*fn_Py_IsInitialized)(void);
typedef int          (*fn_Py_FinalizeEx)(void);
typedef int          (*fn_PyRun_SimpleString)(const char *);
typedef void         (*fn_PyErr_Print)(void);
typedef void         (*fn_PyErr_Clear)(void);
typedef const char  *(*fn_Py_GetVersion)(void);
typedef PyGILState_STATE (*fn_PyGILState_Ensure)(void);
typedef void         (*fn_PyGILState_Release)(PyGILState_STATE);
typedef PyObject    *(*fn_PyImport_AddModule)(const char *);
typedef PyObject    *(*fn_PyModule_GetDict)(PyObject *);
typedef PyObject    *(*fn_PyRun_StringFlags)(const char *, int, PyObject *, PyObject *, void *);
typedef int          (*fn_PyDict_SetItemString)(PyObject *, const char *, PyObject *);
typedef PyObject    *(*fn_PyDict_GetItemString)(PyObject *, const char *);
typedef PyObject    *(*fn_PyUnicode_FromString)(const char *);
typedef const char  *(*fn_PyUnicode_AsUTF8)(PyObject *);
typedef PyObject    *(*fn_PyTuple_GetItem)(PyObject *, long);
typedef long         (*fn_PyTuple_Size)(PyObject *);
typedef int          (*fn_PyObject_IsTrue)(PyObject *);
typedef void         (*fn_Py_DecRef)(PyObject *);

/* Py_file_input: 见 Include/pythonrun.h(256/257/258 三选一,宏,故自定) */
#define PY_FILE_INPUT 257
#define PY_EVAL_INPUT 258

static struct {
    void *handle;

    fn_Py_Initialize          Initialize;
    fn_Py_IsInitialized       IsInitialized;
    fn_Py_FinalizeEx          FinalizeEx;
    fn_PyRun_SimpleString     Run_SimpleString;
    fn_PyErr_Print            Err_Print;
    fn_PyErr_Clear            Err_Clear;
    fn_Py_GetVersion          GetVersion;
    fn_PyGILState_Ensure      GILState_Ensure;
    fn_PyGILState_Release     GILState_Release;
    fn_PyImport_AddModule     Import_AddModule;
    fn_PyModule_GetDict       Module_GetDict;
    fn_PyRun_StringFlags      Run_StringFlags;
    fn_PyDict_SetItemString   Dict_SetItemString;
    fn_PyDict_GetItemString   Dict_GetItemString;
    fn_PyUnicode_FromString   Unicode_FromString;
    fn_PyUnicode_AsUTF8       Unicode_AsUTF8;
    fn_PyTuple_GetItem        Tuple_GetItem;
    fn_PyTuple_Size           Tuple_Size;
    fn_PyObject_IsTrue        Object_IsTrue;
    fn_Py_DecRef              DecRef;

    int  ready;          /* 符号已解析 */
    int  initialized;    /* Py_Initialize 已完成 */
} g_py;

static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;

/**
 * 对外版本号只保留数字前缀。
 * Py_GetVersion() 会带上编译期 marker(Chaquopy 把 Android clang 的 __VERSION__
 * 编了进去,含 URL),直接暴露给 Lua/Java 既冗长又会误导用户。
 */
static void clean_version(const char *raw, char *out, size_t n) {
    if (!raw || !out || n == 0) return;
    size_t i = 0;
    while (i + 1 < n && raw[i] && raw[i] != ' ' && raw[i] != '\n') {
        out[i] = raw[i];
        i++;
    }
    out[i] = '\0';
    if (i == 0) snprintf(out, n, "%s", PY_VERSION_STRING);
}

/* 运行时布局(由 nPyInit 传入) */
static char g_home[1024];
static char g_tmpdir[1024];
static char g_libdir[1024];

#define SYM(field, name)                                                        \
    do {                                                                        \
        *(void **)(&g_py.field) = dlsym(g_py.handle, name);                     \
        if (!g_py.field) {                                                      \
            LOGE("缺少 Python 符号: %s (%s)", name, dlerror());                 \
            return 0;                                                           \
        }                                                                       \
    } while (0)

/** 解析全部所需符号。调用者须持锁。 */
static int load_symbols_locked(void) {
    SYM(Initialize,        "Py_Initialize");
    SYM(IsInitialized,     "Py_IsInitialized");
    SYM(FinalizeEx,        "Py_FinalizeEx");
    SYM(Run_SimpleString,  "PyRun_SimpleString");
    SYM(Err_Print,         "PyErr_Print");
    SYM(Err_Clear,         "PyErr_Clear");
    SYM(GetVersion,        "Py_GetVersion");
    SYM(GILState_Ensure,   "PyGILState_Ensure");
    SYM(GILState_Release,  "PyGILState_Release");
    SYM(Import_AddModule,  "PyImport_AddModule");
    SYM(Module_GetDict,    "PyModule_GetDict");
    SYM(Run_StringFlags,   "PyRun_StringFlags");
    SYM(Dict_SetItemString,"PyDict_SetItemString");
    SYM(Dict_GetItemString,"PyDict_GetItemString");
    SYM(Unicode_FromString,"PyUnicode_FromString");
    SYM(Unicode_AsUTF8,    "PyUnicode_AsUTF8");
    SYM(Tuple_GetItem,     "PyTuple_GetItem");
    SYM(Tuple_Size,        "PyTuple_Size");
    SYM(Object_IsTrue,     "PyObject_IsTrue");
    SYM(DecRef,            "Py_DecRef");
    return 1;
}

/**
 * 加载 libpython。调用者须持锁。
 *
 * 先按 soname(由 app 的 nativeLibraryDir / 链接器命名空间解析),再尝试绝对路径。
 * 两条都失败只记日志:armv7 上本就没有产物,这是预期状态而非错误。
 */
static int load_python_locked(void) {
    if (g_py.handle) return g_py.ready;

    g_py.handle = dlopen(PY_LIB_SONAME, RTLD_NOW | RTLD_GLOBAL);
    if (!g_py.handle && g_libdir[0]) {
        char abs[1200];
        snprintf(abs, sizeof abs, "%s/%s", g_libdir, PY_LIB_SONAME);
        g_py.handle = dlopen(abs, RTLD_NOW | RTLD_GLOBAL);
        if (g_py.handle) LOGI("已按绝对路径加载: %s", abs);
    }
    if (!g_py.handle) {
        LOGW("libpython 不可用(该 ABI 无产物?): %s", dlerror());
        return 0;
    }

    if (!load_symbols_locked()) {
        dlclose(g_py.handle);
        g_py.handle = NULL;
        return 0;
    }
    g_py.ready = 1;
    LOGI("libpython 已就绪: %s", g_py.GetVersion());
    return 1;
}

/* =====================================================================
 * 2. 输出捕获 harness
 *
 * 用固定脚本 + 注入变量,避免把用户代码拼进脚本(拼接会引入转义问题)。
 * 结果以 (ok, text) 元组写回 globals,由 C 侧取出。
 * ===================================================================== */

static const char *HARNESS =
"import sys as _sys, io as _io, os as _os, traceback as _tb\n"
"_lf_out = _io.StringIO()\n"
"_lf_old = (_sys.stdout, _sys.stderr)\n"
"_sys.stdout = _sys.stderr = _lf_out\n"
"_lf_ok = True\n"
"try:\n"
"    if __lf_cwd:\n"
"        _os.chdir(__lf_cwd)\n"
"    if __lf_path:\n"
"        _lf_d = _os.path.dirname(_os.path.abspath(__lf_path))\n"
"        if _lf_d not in _sys.path:\n"
"            _sys.path.insert(0, _lf_d)\n"
"        _sys.argv = list(__lf_argv) or [__lf_path]\n"
"        _lf_g = {'__name__': '__main__', '__file__': __lf_path,\n"
"                 '__builtins__': __builtins__}\n"
"        exec(compile(open(__lf_path, 'rb').read(), __lf_path, 'exec'), _lf_g)\n"
"    else:\n"
"        _sys.argv = list(__lf_argv) or ['-']\n"
"        _lf_g = {'__name__': '__main__', '__builtins__': __builtins__}\n"
"        exec(compile(__lf_code, '<luaforge>', 'exec'), _lf_g)\n"
"except SystemExit:\n"
"    pass\n"
"except BaseException:\n"
"    _tb.print_exc()\n"
"    _lf_ok = False\n"
"finally:\n"
"    _lf_text = _lf_out.getvalue()\n"
"    _sys.stdout, _sys.stderr = _lf_old\n"
"__lf_result = (_lf_ok, _lf_text)\n";

/** 把 __lf_result 的文本取出并 malloc 一份(调用者 free)。调用者须已持 GIL。 */
static char *read_result(PyObject *d, int *ok_out) {
    PyObject *res = g_py.Dict_GetItemString(d, "__lf_result");   /* borrowed */
    if (!res || g_py.Tuple_Size(res) < 2) {
        *ok_out = 0;
        return strdup("[luapython] harness 未返回结果(内部错误)");
    }

    PyObject *ok_obj   = g_py.Tuple_GetItem(res, 0);             /* borrowed */
    PyObject *text_obj = g_py.Tuple_GetItem(res, 1);             /* borrowed */
    *ok_out = ok_obj ? g_py.Object_IsTrue(ok_obj) : 0;
    if (!*ok_out && g_py.Err_Clear) g_py.Err_Clear();

    const char *utf8 = text_obj ? g_py.Unicode_AsUTF8(text_obj) : NULL;
    return strdup(utf8 ? utf8 : "");
}

/**
 * 执行一段 Python。code 与 path 二选一(见 harness)。
 * 返回 malloc 的输出文本;*ok_out 表示脚本是否正常结束。
 * 调用者须持锁且已初始化。
 */
static char *run_locked(const char *code, const char *path,
                        const char *const *argv, size_t argc,
                        const char *cwd, int *ok_out) {
    PyGILState_STATE gil = g_py.GILState_Ensure();

    PyObject *main_mod = g_py.Import_AddModule("__main__");
    PyObject *d = main_mod ? g_py.Module_GetDict(main_mod) : NULL;
    if (!d) {
        g_py.GILState_Release(gil);
        *ok_out = 0;
        return strdup("[luapython] 无法访问 __main__ 命名空间");
    }

    /* 注入 harness 用到的变量(每次覆盖,避免上一次的残留) */
    PyObject *v;
    v = g_py.Unicode_FromString(code ? code : "");
    g_py.Dict_SetItemString(d, "__lf_code", v); g_py.DecRef(v);
    v = g_py.Unicode_FromString(path ? path : "");
    g_py.Dict_SetItemString(d, "__lf_path", v); g_py.DecRef(v);
    v = g_py.Unicode_FromString(cwd ? cwd : "");
    g_py.Dict_SetItemString(d, "__lf_cwd", v); g_py.DecRef(v);

    /* argv: eval 模式拿到真 list 再逐个 append(file 模式返回的是 None) */
    PyObject *lst = g_py.Run_StringFlags("[]", PY_EVAL_INPUT, d, d, NULL);
    if (lst) {
        g_py.Dict_SetItemString(d, "__lf_argv", lst);   /* dict 持有一份引用 */
        for (size_t i = 0; i < argc; i++) {
            PyObject *s = g_py.Unicode_FromString(argv[i]);
            if (!s) { if (g_py.Err_Clear) g_py.Err_Clear(); continue; }
            g_py.Dict_SetItemString(d, "__lf_tmp_arg", s);
            g_py.DecRef(s);
            PyObject *r2 = g_py.Run_StringFlags("__lf_argv.append(__lf_tmp_arg)",
                                                PY_FILE_INPUT, d, d, NULL);
            if (r2) g_py.DecRef(r2);
            else if (g_py.Err_Clear) g_py.Err_Clear();
        }
        g_py.DecRef(lst);
    } else {
        if (g_py.Err_Clear) g_py.Err_Clear();
    }

    PyObject *r = g_py.Run_StringFlags(HARNESS, PY_FILE_INPUT, d, d, NULL);
    if (!r) {
        /* harness 自身崩了(理论上不该发生),把错误文本化后返回 */
        if (g_py.Err_Clear) g_py.Err_Clear();
        g_py.GILState_Release(gil);
        *ok_out = 0;
        return strdup("[luapython] harness 执行失败");
    }
    g_py.DecRef(r);

    char *out = read_result(d, ok_out);
    g_py.GILState_Release(gil);
    return out;
}

/* =====================================================================
 * 3. 初始化 / 终结
 * ===================================================================== */

/**
 * 初始化 Python 运行时。home = 解包出的运行时根目录。
 * 幂等:重复调用只更新路径并直接返回成功。
 */
static int ensure_init(const char *home, const char *libDir, const char *tmpDir) {
    pthread_mutex_lock(&g_lock);

    if (home && home[0]) snprintf(g_home, sizeof g_home, "%s", home);
    if (libDir && libDir[0]) snprintf(g_libdir, sizeof g_libdir, "%s", libDir);
    if (tmpDir && tmpDir[0]) snprintf(g_tmpdir, sizeof g_tmpdir, "%s", tmpDir);

    if (g_py.initialized) { pthread_mutex_unlock(&g_lock); return 1; }
    if (!load_python_locked()) { pthread_mutex_unlock(&g_lock); return 0; }

    /* sys.path 三件套: 运行时根 + lib-dynload + site-packages。
     * 用环境变量而非 PyConfig: 既少一处 ABI 依赖,也让 -X / 用户覆盖行为一致。 */
    char pathbuf[4096];
    snprintf(pathbuf, sizeof pathbuf,
             "%s:%s/lib-dynload:%s/site-packages", g_home, g_home, g_home);
    setenv("PYTHONHOME", g_home, 1);
    setenv("PYTHONPATH", pathbuf, 1);
    setenv("PYTHONDONTWRITEBYTECODE", "1", 1);
    if (g_tmpdir[0]) {
        setenv("TMPDIR", g_tmpdir, 1);
        setenv("TEMP",   g_tmpdir, 1);
        setenv("TMP",    g_tmpdir, 1);
    }
    /* Android 无 /dev/random 语义差异,交给内核 getrandom 即可 */
    setenv("PYTHONHASHSEED", "random", 1);

    g_py.Initialize();

    if (!g_py.IsInitialized()) {
        LOGE("Py_Initialize 后仍非 initialized 状态");
        pthread_mutex_unlock(&g_lock);
        return 0;
    }
    g_py.initialized = 1;
    LOGI("Python 就绪: %s | home=%s", g_py.GetVersion(), g_home);
    pthread_mutex_unlock(&g_lock);
    return 1;
}

/* =====================================================================
 * 4. JNI 接口 (com.luaforge.studio.utils.PythonUtil)
 * ===================================================================== */

static jstring new_string_utf(JNIEnv *env, const char *s) {
    jstring r = (*env)->NewStringUTF(env, s ? s : "");
    free((void *)s);
    return r;
}

/** 抛 IllegalStateException 并返回 NULL —— 失败必须让调用方看见。 */
static void *throw_state(JNIEnv *env, const char *msg) {
    jclass cls = (*env)->FindClass(env, "java/lang/IllegalStateException");
    if (cls) (*env)->ThrowNew(env, cls, msg ? msg : "Python 调用失败");
    return NULL;
}

JNIEXPORT jboolean JNICALL
Java_com_luaforge_studio_utils_PythonUtil_nPyAvailable(JNIEnv *env, jobject thiz) {
    (void) env; (void) thiz;
    pthread_mutex_lock(&g_lock);
    int ok = load_python_locked();
    pthread_mutex_unlock(&g_lock);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_luaforge_studio_utils_PythonUtil_nPyVersion(JNIEnv *env, jobject thiz) {
    (void) thiz;
    char buf[64];
    pthread_mutex_lock(&g_lock);
    if (load_python_locked()) clean_version(g_py.GetVersion(), buf, sizeof buf);
    else snprintf(buf, sizeof buf, "%s (runtime 未加载)", PY_VERSION_STRING);
    pthread_mutex_unlock(&g_lock);
    return new_string_utf(env, strdup(buf));
}

JNIEXPORT jboolean JNICALL
Java_com_luaforge_studio_utils_PythonUtil_nPyInit(JNIEnv *env, jobject thiz,
                                                  jstring home, jstring libDir, jstring tmpDir) {
    (void) thiz;
    const char *h = home   ? (*env)->GetStringUTFChars(env, home, NULL)   : NULL;
    const char *l = libDir ? (*env)->GetStringUTFChars(env, libDir, NULL) : NULL;
    const char *t = tmpDir ? (*env)->GetStringUTFChars(env, tmpDir, NULL) : NULL;
    int ok = ensure_init(h, l, t);
    if (home)   (*env)->ReleaseStringUTFChars(env, home, h);
    if (libDir) (*env)->ReleaseStringUTFChars(env, libDir, l);
    if (tmpDir) (*env)->ReleaseStringUTFChars(env, tmpDir, t);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_luaforge_studio_utils_PythonUtil_nPyRun(JNIEnv *env, jobject thiz,
                                                 jstring code, jstring cwd) {
    (void) thiz;
    const char *c = code ? (*env)->GetStringUTFChars(env, code, NULL) : "";
    const char *w = (cwd && (*env)->GetStringLength(env, cwd) > 0)
                    ? (*env)->GetStringUTFChars(env, cwd, NULL) : NULL;

    /* initialized 必须与 run 在同一临界区内判断:否则 finalize 可能插在两者之间,
     * 让已释放的运行时被使用。 */
    pthread_mutex_lock(&g_lock);
    int inited = g_py.initialized;
    int ok = 0;
    char *out = inited ? run_locked(c, NULL, NULL, 0, w, &ok) : NULL;
    pthread_mutex_unlock(&g_lock);

    if (cwd && w) (*env)->ReleaseStringUTFChars(env, cwd, w);
    if (code)     (*env)->ReleaseStringUTFChars(env, code, c);

    if (!inited) return throw_state(env, "Python 未初始化,请先调用 pythonInit()");
    if (!ok) {
        char buf[8192];
        snprintf(buf, sizeof buf, "Python 执行失败:\n%s", out ? out : "");
        free(out);
        return throw_state(env, buf);
    }
    return new_string_utf(env, out);
}

JNIEXPORT jstring JNICALL
Java_com_luaforge_studio_utils_PythonUtil_nPyRunFile(JNIEnv *env, jobject thiz,
                                                     jstring path, jobjectArray argv, jstring cwd) {
    (void) thiz;
    const char *p = path ? (*env)->GetStringUTFChars(env, path, NULL) : NULL;
    if (!p || !p[0]) {
        if (path) (*env)->ReleaseStringUTFChars(env, path, p);
        return throw_state(env, "脚本路径为空");
    }

    /* argv -> C 数组(元素在循环结束前保持有效) */
    jsize argc = argv ? (*env)->GetArrayLength(env, argv) : 0;
    const char **cargv = argc > 0 ? calloc((size_t) argc, sizeof(char *)) : NULL;
    for (jsize i = 0; i < argc; i++) {
        jstring s = (jstring) (*env)->GetObjectArrayElement(env, argv, i);
        cargv[i] = s ? (*env)->GetStringUTFChars(env, s, NULL) : "";
    }
    const char *w = (cwd && (*env)->GetStringLength(env, cwd) > 0)
                    ? (*env)->GetStringUTFChars(env, cwd, NULL) : NULL;

    /* 同 nPyRun: initialized 与 run 必须同临界区 */
    pthread_mutex_lock(&g_lock);
    int inited = g_py.initialized;
    int ok = 0;
    char *out = inited ? run_locked(NULL, p, cargv, (size_t) argc, w, &ok) : NULL;
    pthread_mutex_unlock(&g_lock);

    for (jsize i = 0; i < argc; i++) {
        jstring s = (jstring) (*env)->GetObjectArrayElement(env, argv, i);
        if (s) (*env)->ReleaseStringUTFChars(env, s, cargv[i]);
    }
    free(cargv);
    if (w) (*env)->ReleaseStringUTFChars(env, cwd, w);
    (*env)->ReleaseStringUTFChars(env, path, p);

    if (!inited) return throw_state(env, "Python 未初始化,请先调用 pythonInit()");
    if (!ok) {
        char buf[8192];
        snprintf(buf, sizeof buf, "Python 脚本失败:\n%s", out ? out : "");
        free(out);
        return throw_state(env, buf);
    }
    return new_string_utf(env, out);
}

JNIEXPORT void JNICALL
Java_com_luaforge_studio_utils_PythonUtil_nPyFinalize(JNIEnv *env, jobject thiz) {
    (void) env; (void) thiz;
    pthread_mutex_lock(&g_lock);
    if (g_py.initialized) {
        g_py.FinalizeEx();
        g_py.initialized = 0;
        LOGI("Python 已 finalize");
    }
    pthread_mutex_unlock(&g_lock);
}

/* =====================================================================
 * 5. Lua 侧: require "python"
 * ===================================================================== */

/** 把 run/runFile 的公共部分收敛:失败时 raiserror,成功压入输出。 */
static int l_push_run_result(lua_State *L, char *out, int ok) {
    if (!ok) {
        char buf[8192];
        snprintf(buf, sizeof buf, "python 执行失败:\n%s", out ? out : "");
        free(out);
        return luaL_error(L, "%s", buf);   /* 不返回 */
    }
    lua_pushstring(L, out ? out : "");
    free(out);
    return 1;
}

static int l_py_available(lua_State *L) {
    pthread_mutex_lock(&g_lock);
    int ok = load_python_locked();
    pthread_mutex_unlock(&g_lock);
    lua_pushboolean(L, ok);
    return 1;
}

static int l_py_version(lua_State *L) {
    char buf[64];
    pthread_mutex_lock(&g_lock);
    if (load_python_locked()) clean_version(g_py.GetVersion(), buf, sizeof buf);
    else snprintf(buf, sizeof buf, "%s (runtime 未加载)", PY_VERSION_STRING);
    pthread_mutex_unlock(&g_lock);
    lua_pushstring(L, buf);
    return 1;
}

static int l_py_init(lua_State *L) {
    const char *home = luaL_optstring(L, 1, NULL);
    const char *lib  = luaL_optstring(L, 2, NULL);
    const char *tmp  = luaL_optstring(L, 3, NULL);
    lua_pushboolean(L, ensure_init(home, lib, tmp));
    return 1;
}

static int l_py_run(lua_State *L) {
    size_t n = 0;
    const char *code = luaL_checklstring(L, 1, &n);
    const char *cwd  = luaL_optstring(L, 2, NULL);

    /* 同 JNI:同临界区判断 + 执行。
     * luaL_error 会 longjmp,绝不能在持锁时调用,故错误在解锁后再抛。 */
    pthread_mutex_lock(&g_lock);
    int inited = g_py.initialized;
    int ok = 0;
    char *out = inited ? run_locked(code, NULL, NULL, 0, cwd, &ok) : NULL;
    pthread_mutex_unlock(&g_lock);

    if (!inited) return luaL_error(L, "python 未初始化,请先调用 python.init(home)");
    return l_push_run_result(L, out, ok);
}

static int l_py_run_file(lua_State *L) {
    const char *path = luaL_checkstring(L, 1);
    const char *cwd  = luaL_optstring(L, 2, NULL);

    int argc = lua_gettop(L) - 2;
    const char **argv = NULL;
    if (argc > 0) {
        argv = calloc((size_t) argc, sizeof(char *));
        for (int i = 0; i < argc; i++) argv[i] = luaL_checkstring(L, 3 + i);
    }

    pthread_mutex_lock(&g_lock);
    int inited = g_py.initialized;
    int ok = 0;
    char *out = inited ? run_locked(NULL, path, argv, (size_t) (argc > 0 ? argc : 0), cwd, &ok) : NULL;
    pthread_mutex_unlock(&g_lock);

    free(argv);
    if (!inited) return luaL_error(L, "python 未初始化,请先调用 python.init(home)");
    return l_push_run_result(L, out, ok);
}

static int l_py_finalize(lua_State *L) {
    (void) L;
    pthread_mutex_lock(&g_lock);
    if (g_py.initialized) { g_py.FinalizeEx(); g_py.initialized = 0; }
    pthread_mutex_unlock(&g_lock);
    lua_pushboolean(L, 1);
    return 1;
}

/* 供 require "python" 使用(顶层 CMake 导出该符号) */
int luaopen_python(lua_State *L) {
    static const luaL_Reg py_funcs[] = {
        {"available", l_py_available},
        {"version",   l_py_version},
        {"init",      l_py_init},
        {"run",       l_py_run},
        {"runFile",   l_py_run_file},
        {"finalize",  l_py_finalize},
        {NULL, NULL}
    };
    luaL_newlib(L, py_funcs);
    lua_pushstring(L, PY_VERSION_STRING);
    lua_setfield(L, -2, "_VERSION");
    return 1;
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void) vm;
    (void) reserved;
    return JNI_VERSION_1_6;
}
