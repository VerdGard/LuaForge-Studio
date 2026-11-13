/*
** $Id: lundump.c $
** load precompiled Lua chunks
** See Copyright Notice in lua.h
*/

#define lundump_c
#define LUA_CORE

#include "lprefix.h"

#include <limits.h>
#include <string.h>

#include "lua.h"

#include "ldebug.h"
#include "ldo.h"
#include "lfunc.h"
#include "lmem.h"
#include "lobject.h"
#include "lstring.h"
#include "lundump.h"
#include "lzio.h"
/* 与 ldump.c 共享同一份混淆实现:两端由同一份代码保证互逆 */
#include "lfprotect.h"

#if !defined(luai_verifycode)
#define luai_verifycode(L, f)  /* empty */
#endif

typedef struct {
    lua_State *L;
    ZIO *Z;
    const char *name;
    /* 与 DumpState 对应的密钥流 */
    uint8_t master_key[32];
    uint8_t keystream[64];
} LoadState;

/* 为某一段准备密钥流。counter 基必须与 ldump.c 完全一致。 */
static void lf_prepare_keystream(LoadState *S, uint32_t segment) {
    lf_chacha20_block(S->master_key, segment, S->keystream);
}

static void lf_wipe_keystream(LoadState *S) {
    volatile uint8_t *p = S->keystream;
    int i;
    for (i = 0; i < 64; i++) p[i] = 0;
}

static l_noret error(LoadState *S, const char *why) {
    luaO_pushfstring(S->L, "%s: bad binary format (%s)", S->name, why);
    luaD_throw(S->L, LUA_ERRSYNTAX);
}

#define loadVector(S, b, n)    loadBlock(S,b,(n)*sizeof((b)[0]))

static void loadBlock(LoadState *S, void *b, size_t size) {
    if (luaZ_read(S->Z, b, size) != 0)
        error(S, "truncated chunk");
}

#define loadVar(S, x)        loadVector(S,&x,1)

static lu_byte loadByte(LoadState *S) {
    int b = zgetc(S->Z);
    if (b == EOZ)
        error(S, "truncated chunk");
    return cast_byte(b);
}

static size_t loadUnsigned(LoadState *S, size_t limit) {
    size_t x = 0;
    int b;
    limit >>= 7;
    do {
        b = loadByte(S);
        if (x >= limit)
            error(S, "integer overflow");
        x = (x << 7) | (b & 0x7f);
    } while ((b & 0x80) == 0);
    return x;
}

static size_t loadSize(LoadState *S) {
    return loadUnsigned(S, MAX_SIZET);
}

static int loadInt(LoadState *S) {
    return cast_int(loadUnsigned(S, INT_MAX));
}

static lua_Number loadNumber(LoadState *S) {
    lua_Number x;
    loadVar(S, x);
    return x;
}

static lua_Integer loadInteger(LoadState *S) {
    lua_Integer x;
    loadVar(S, x);
    return x;
}

static TString *loadStringN(LoadState *S, Proto *p) {
    lua_State *L = S->L;
    TString *ts;
    size_t size = loadSize(S);
    if (size == 0)
        return NULL;
    else if (--size <= LUAI_MAXSHORTLEN) {
        char buff[LUAI_MAXSHORTLEN];
        lu_byte b;

        /* 逐字节加载并还原(段内计数从 0 起,与 dumpString 一致) */
        if (LUA_PROTECT_FLAGS & 0x01) {
            size_t i;
            lf_prepare_keystream(S, 0x1000u);
            for (i = 0; i < size; i++) {
                b = loadByte(S);
                buff[i] = lf_unprotect_byte(b, i, S->keystream);
            }
            lf_wipe_keystream(S);
        } else {
            loadVector(S, buff, size);
        }

        ts = luaS_newlstr(L, buff, size);
    } else {
        ts = luaS_createlngstrobj(L, size);
        setsvalue2s(L, L->top.p, ts);
        luaD_inctop(L);

        char *str = getlngstr(ts);
        lu_byte b;

        /* 逐字节加载并还原 */
        if (LUA_PROTECT_FLAGS & 0x01) {
            size_t i;
            lf_prepare_keystream(S, 0x1000u);
            for (i = 0; i < size; i++) {
                b = loadByte(S);
                str[i] = lf_unprotect_byte(b, i, S->keystream);
            }
            lf_wipe_keystream(S);
        } else {
            loadVector(S, str, size);
        }

        L->top.p--;
    }
    luaC_objbarrier(L, p, ts);
    return ts;
}

static TString *loadString(LoadState *S, Proto *p) {
    TString *st = loadStringN(S, p);
    if (st == NULL)
        error(S, "bad format for constant string");
    return st;
}

static void loadCode(LoadState *S, Proto *f) {
    int orig_size = loadInt(S);

    f->code = luaM_newvectorchecked(S->L, orig_size, Instruction);
    f->sizecode = orig_size;

    lu_byte *code_bytes = (lu_byte *) f->code;
    size_t total_bytes = orig_size * sizeof(Instruction);
    lu_byte b;

    /* 逐字节加载并还原(与 dumpCode 的 counter 基一致) */
    if (LUA_PROTECT_FLAGS & 0x02) {
        size_t i;
        lf_prepare_keystream(S, 0x2000u);
        for (i = 0; i < total_bytes; i++) {
            b = loadByte(S);
            code_bytes[i] = lf_unprotect_byte(b, i, S->keystream);
        }
        lf_wipe_keystream(S);
    } else {
        loadBlock(S, f->code, total_bytes);
    }
}

static void loadFunction(LoadState *S, Proto *f, TString *psource);

static void loadConstants(LoadState *S, Proto *f) {
    int i;
    int n = loadInt(S);
    f->k = luaM_newvectorchecked(S->L, n, TValue);
    f->sizek = n;
    for (i = 0; i < n; i++)
        setnilvalue(&f->k[i]);
    for (i = 0; i < n; i++) {
        TValue *o = &f->k[i];
        int t = loadByte(S);
        switch (t) {
            case LUA_VNIL:
                setnilvalue(o);
                break;
            case LUA_VFALSE:
                setbfvalue(o);
                break;
            case LUA_VTRUE:
                setbtvalue(o);
                break;
            case LUA_VNUMFLT: setfltvalue(o, loadNumber(S));
                break;
            case LUA_VNUMINT: setivalue(o, loadInteger(S));
                break;
            case LUA_VSHRSTR:
            case LUA_VLNGSTR:
                setsvalue2n (S->L, o, loadString(S, f));
                break;
            default:
                lua_assert(0);
        }
    }
}

static void loadProtos(LoadState *S, Proto *f) {
    int i;
    int n = loadInt(S);
    f->p = luaM_newvectorchecked(S->L, n, Proto *);
    f->sizep = n;
    for (i = 0; i < n; i++)
        f->p[i] = NULL;
    for (i = 0; i < n; i++) {
        f->p[i] = luaF_newproto(S->L);
        luaC_objbarrier(S->L, f, f->p[i]);
        loadFunction(S, f->p[i], f->source);
    }
}

static void loadUpvalues(LoadState *S, Proto *f) {
    int i, n;
    n = loadInt(S);
    f->upvalues = luaM_newvectorchecked(S->L, n, Upvaldesc);
    f->sizeupvalues = n;
    for (i = 0; i < n; i++)
        f->upvalues[i].name = NULL;
    for (i = 0; i < n; i++) {
        f->upvalues[i].instack = loadByte(S);
        f->upvalues[i].idx = loadByte(S);
        f->upvalues[i].kind = loadByte(S);
    }
}

static void loadDebug(LoadState *S, Proto *f) {
    int i, n;
    n = loadInt(S);
    f->lineinfo = luaM_newvectorchecked(S->L, n, ls_byte);
    f->sizelineinfo = n;
    loadVector(S, f->lineinfo, n);
    n = loadInt(S);
    f->abslineinfo = luaM_newvectorchecked(S->L, n, AbsLineInfo);
    f->sizeabslineinfo = n;
    for (i = 0; i < n; i++) {
        f->abslineinfo[i].pc = loadInt(S);
        f->abslineinfo[i].line = loadInt(S);
    }
    n = loadInt(S);
    f->locvars = luaM_newvectorchecked(S->L, n, LocVar);
    f->sizelocvars = n;
    for (i = 0; i < n; i++)
        f->locvars[i].varname = NULL;
    for (i = 0; i < n; i++) {
        f->locvars[i].varname = loadStringN(S, f);
        f->locvars[i].startpc = loadInt(S);
        f->locvars[i].endpc = loadInt(S);
    }
    n = loadInt(S);
    if (n != 0)
        n = f->sizeupvalues;
    for (i = 0; i < n; i++)
        f->upvalues[i].name = loadStringN(S, f);
}

static void loadFunction(LoadState *S, Proto *f, TString *psource) {
    f->source = loadStringN(S, f);
    if (f->source == NULL)
        f->source = psource;
    f->linedefined = loadInt(S);
    f->lastlinedefined = loadInt(S);
    f->numparams = loadByte(S);
    f->is_vararg = loadByte(S);
    f->maxstacksize = loadByte(S);
    f->custom_flag = loadByte(S);
    f->custom_version = loadInt(S);
    loadVar(S, f->custom_data);
    loadCode(S, f);
    loadConstants(S, f);
    loadUpvalues(S, f);
    loadProtos(S, f);
    loadDebug(S, f);
}

static void checkliteral(LoadState *S, const char *s, const char *msg) {
    char buff[sizeof(LUA_SIGNATURE) + sizeof(LUAC_DATA)];
    size_t len = strlen(s);
    loadVector(S, buff, len);
    if (memcmp(s, buff, len) != 0)
        error(S, msg);
}

static void fchecksize(LoadState *S, size_t size, const char *tname) {
    if (loadByte(S) != size)
        error(S, luaO_pushfstring(S->L, "%s size mismatch", tname));
}

#define checksize(S, t)    fchecksize(S,sizeof(t),#t)

static void checkHeader(LoadState *S) {
    checkliteral(S, &LUA_SIGNATURE[1], "not a binary chunk");
    loadByte(S);
    if (loadByte(S) != LUAC_FORMAT)
        error(S, "format mismatch");
    checkliteral(S, LUAC_DATA, "corrupted chunk");
    checksize(S, Instruction);
    checksize(S, lua_Integer);
    checksize(S, lua_Number);
    if (loadInteger(S) != LUAC_INT)
        error(S, "integer format mismatch");
    if (loadNumber(S) != LUAC_NUM)
        error(S, "float format mismatch");
}

LClosure *luaU_undump(lua_State *L, ZIO *Z, const char *name) {
    LoadState S;
    LClosure *cl;
    if (*name == '@' || *name == '=')
        S.name = name + 1;
    else if (*name == LUA_SIGNATURE[0])
        S.name = "binary string";
    else
        S.name = name;
    S.L = L;
    S.Z = Z;
    /* 与 luaU_dump 同样在 native 内派生主密钥 */
    lf_master_key(S.master_key);
    memset(S.keystream, 0, sizeof(S.keystream));
    checkHeader(&S);
    cl = luaF_newLclosure(L, loadByte(&S));
    setclLvalue2s(L, L->top.p, cl);
    luaD_inctop(L);
    cl->p = luaF_newproto(L);
    luaC_objbarrier(L, cl, cl->p);
    loadFunction(&S, cl->p, NULL);
    lua_assert(cl->nupvalues == cl->p->sizeupvalues);
    luai_verifycode(L, cl->p);
    return cl;
}