/*
** $Id: ldump.c $
** save precompiled Lua chunks
** See Copyright Notice in lua.h
*/

#define ldump_c
#define LUA_CORE

#include "lprefix.h"

#include <limits.h>
#include <stddef.h>

#include "lua.h"

#include "lobject.h"
#include "lstate.h"
#include "lundump.h"
/* 混淆算法与 lundump.c 共享同一实现,对称性由编译器保证 */
#include "lfprotect.h"

typedef struct {
    lua_State *L;
    lua_Writer writer;
    void *data;
    int strip;
    int status;
    /* 混淆用的密钥流。每段(字符串常量 / 指令段)各自从 block 0 起算,
     * 保证与读端的段内独立计数一致。 */
    uint8_t master_key[32];
    uint8_t keystream[64];
} DumpState;

/* 为某一段准备密钥流:段序号进入 counter,使同一 chunk 内不同段互不重复。 */
static void lf_prepare_keystream(DumpState *D, uint32_t segment) {
    lf_chacha20_block(D->master_key, segment, D->keystream);
}

/* 清零密钥流,避免残留上一段的内容(仅减少内存 dump 暴露面)。 */
static void lf_wipe_keystream(DumpState *D) {
    volatile uint8_t *p = D->keystream;
    int i;
    for (i = 0; i < 64; i++) p[i] = 0;
}

#define dumpVector(D, v, n)    dumpBlock(D,v,(n)*sizeof((v)[0]))
#define dumpLiteral(D, s)    dumpBlock(D,s,sizeof(s) - sizeof(char))

static void dumpBlock(DumpState *D, const void *b, size_t size) {
    if (D->status == 0 && size > 0) {
        lua_unlock(D->L);
        D->status = (*D->writer)(D->L, b, size, D->data);
        lua_lock(D->L);
    }
}

#define dumpVar(D, x)        dumpVector(D,&x,1)

static void dumpByte(DumpState *D, int y) {
    lu_byte x = (lu_byte) y;
    dumpVar(D, x);
}

#define DIBS    ((sizeof(size_t) * CHAR_BIT + 6) / 7)

static void dumpSize(DumpState *D, size_t x) {
    lu_byte buff[DIBS];
    int n = 0;
    do {
        buff[DIBS - (++n)] = x & 0x7f;
        x >>= 7;
    } while (x != 0);
    buff[DIBS - 1] |= 0x80;
    dumpVector(D, buff + DIBS - n, n);
}

static void dumpInt(DumpState *D, int x) {
    dumpSize(D, x);
}

static void dumpNumber(DumpState *D, lua_Number x) {
    dumpVar(D, x);
}

static void dumpInteger(DumpState *D, lua_Integer x) {
    dumpVar(D, x);
}

static void dumpString(DumpState *D, const TString *s) {
    if (s == NULL) {
        dumpSize(D, 0);
    } else {
        size_t size = tsslen(s);
        const char *str = getstr(s);
        char protected_char;

        dumpSize(D, size + 1);

        /* 逐字节保护并写入,避免为整串分配临时缓冲。
         * 段内计数从 0 起,与 lundump.c 的 loadStringN 保持一致。 */
        if (LUA_PROTECT_FLAGS & 0x01) {
            size_t i;
            lf_prepare_keystream(D, 0x1000u);  /* 字符串段专用 counter 基 */
            for (i = 0; i < size; i++) {
                protected_char = (char) lf_protect_byte((uint8_t) str[i], i, D->keystream);
                dumpBlock(D, &protected_char, 1);
            }
            lf_wipe_keystream(D);
        } else {
            dumpBlock(D, str, size);
        }
    }
}

static void dumpCode(DumpState *D, const Proto *f) {
    int orig_size = f->sizecode;

    dumpInt(D, orig_size);

    if ((LUA_PROTECT_FLAGS & 0x02) && orig_size > 0) {
        /* 逐字节保护指令段;同样从段内 0 起算 */
        lu_byte protected_byte;
        const lu_byte *code_bytes = (const lu_byte *) f->code;
        size_t total_bytes = orig_size * sizeof(Instruction);
        size_t i;

        lf_prepare_keystream(D, 0x2000u);  /* 指令段专用 counter 基 */
        for (i = 0; i < total_bytes; i++) {
            protected_byte = lf_protect_byte(code_bytes[i], i, D->keystream);
            dumpBlock(D, &protected_byte, 1);
        }
        lf_wipe_keystream(D);
    } else {
        dumpBlock(D, f->code, orig_size * sizeof(Instruction));
    }
}

static void dumpFunction(DumpState *D, const Proto *f, TString *psource);

static void dumpConstants(DumpState *D, const Proto *f) {
    int i;
    int n = f->sizek;
    dumpInt(D, n);
    for (i = 0; i < n; i++) {
        const TValue *o = &f->k[i];
        int tt = ttypetag(o);
        dumpByte(D, tt);
        switch (tt) {
            case LUA_VNUMFLT:
                dumpNumber(D, fltvalue(o));
                break;
            case LUA_VNUMINT:
                dumpInteger(D, ivalue(o));
                break;
            case LUA_VSHRSTR:
            case LUA_VLNGSTR:
                dumpString(D, tsvalue(o));
                break;
            default:
                lua_assert(tt == LUA_VNIL || tt == LUA_VFALSE || tt == LUA_VTRUE);
        }
    }
}

static void dumpProtos(DumpState *D, const Proto *f) {
    int i;
    int n = f->sizep;
    dumpInt(D, n);
    for (i = 0; i < n; i++)
        dumpFunction(D, f->p[i], f->source);
}

static void dumpUpvalues(DumpState *D, const Proto *f) {
    int i, n = f->sizeupvalues;
    dumpInt(D, n);
    for (i = 0; i < n; i++) {
        dumpByte(D, f->upvalues[i].instack);
        dumpByte(D, f->upvalues[i].idx);
        dumpByte(D, f->upvalues[i].kind);
    }
}

static void dumpDebug(DumpState *D, const Proto *f) {
    int i, n;
    n = (D->strip) ? 0 : f->sizelineinfo;
    dumpInt(D, n);
    dumpVector(D, f->lineinfo, n);
    n = (D->strip) ? 0 : f->sizeabslineinfo;
    dumpInt(D, n);
    for (i = 0; i < n; i++) {
        dumpInt(D, f->abslineinfo[i].pc);
        dumpInt(D, f->abslineinfo[i].line);
    }
    n = (D->strip) ? 0 : f->sizelocvars;
    dumpInt(D, n);
    for (i = 0; i < n; i++) {
        dumpString(D, f->locvars[i].varname);
        dumpInt(D, f->locvars[i].startpc);
        dumpInt(D, f->locvars[i].endpc);
    }
    n = (D->strip) ? 0 : f->sizeupvalues;
    dumpInt(D, n);
    for (i = 0; i < n; i++)
        dumpString(D, f->upvalues[i].name);
}

static void dumpFunction(DumpState *D, const Proto *f, TString *psource) {
    if (D->strip || f->source == psource)
        dumpString(D, NULL);
    else
        dumpString(D, f->source);
    dumpInt(D, f->linedefined);
    dumpInt(D, f->lastlinedefined);
    dumpByte(D, f->numparams);
    dumpByte(D, f->is_vararg);
    dumpByte(D, f->maxstacksize);
    dumpByte(D, f->custom_flag);
    dumpInt(D, f->custom_version);
    dumpVar(D, f->custom_data);
    dumpCode(D, f);
    dumpConstants(D, f);
    dumpUpvalues(D, f);
    dumpProtos(D, f);
    dumpDebug(D, f);
}

static void dumpHeader(DumpState *D) {
    dumpLiteral(D, LUA_SIGNATURE);
    dumpByte(D, LUAC_VERSION);
    dumpByte(D, LUAC_FORMAT);
    dumpLiteral(D, LUAC_DATA);
    dumpByte(D, sizeof(Instruction));
    dumpByte(D, sizeof(lua_Integer));
    dumpByte(D, sizeof(lua_Number));
    dumpInteger(D, LUAC_INT);
    dumpNumber(D, LUAC_NUM);
}

int luaU_dump(lua_State *L, const Proto *f, lua_Writer w, void *data,
              int strip) {
    DumpState D;
    D.L = L;
    D.writer = w;
    D.data = data;
    D.strip = strip;
    D.status = 0;
    /* 主密钥在 native 内派生,Java 侧全程接触不到 */
    lf_master_key(D.master_key);
    memset(D.keystream, 0, sizeof(D.keystream));
    dumpHeader(&D);
    dumpByte(&D, f->sizeupvalues);
    dumpFunction(&D, f, NULL);
    return D.status;
}