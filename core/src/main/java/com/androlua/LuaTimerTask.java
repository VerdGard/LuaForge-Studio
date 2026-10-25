package com.androlua;

import com.androlua.util.TimerTaskX;
import com.luajava.JavaFunction;
import com.luajava.LuaException;
import com.luajava.LuaObject;
import com.luajava.LuaState;
import com.luajava.LuaStateFactory;

import java.io.IOException;
import java.util.regex.Pattern;

public class LuaTimerTask extends TimerTaskX {
    private LuaState L;

    private final LuaContext mLuaContext;

    private String mSrc;

    private Object[] mArg = new Object[0];

    private boolean mEnabled = true;

    private byte[] mBuffer;

    public LuaTimerTask(LuaContext luaContext, String src) throws LuaException {
        this(luaContext, src, null);
    }

    public LuaTimerTask(LuaContext luaContext, String src, Object[] arg) throws LuaException {
        mLuaContext = luaContext;
        mSrc = src;
        if (arg != null)
            mArg = arg;
    }

    public LuaTimerTask(LuaContext luaContext, LuaObject func) throws LuaException {
        this(luaContext, func, null);
    }

    public LuaTimerTask(LuaContext luaContext, LuaObject func, Object[] arg) throws LuaException {

        mLuaContext = luaContext;
        if (arg != null)
            mArg = arg;

        mBuffer = func.dump();
    }


    @Override
    public void run() {
        if (!mEnabled)
            return;
        try {
            if (L == null) {
                initLua();

                if (mBuffer != null)
                    newLuaThread(mBuffer, mArg);
                else
                    newLuaThread(mSrc, mArg);
            } else {
                L.getGlobal("run");
                if (!L.isNil(-1))
                    runFunc("run");
                else {
                    if (mBuffer != null)
                        newLuaThread(mBuffer, mArg);
                    else
                        newLuaThread(mSrc, mArg);
                }
            }
        } catch (LuaException e) {
            mLuaContext.sendError(this.toString(), e);
        }
        // cancel() 会把 L 关掉并置 null,而定时器(另一个线程)仍可能投递一次
        // 已排队的执行。这里连"判空后立刻被 cancel 置空"的竞态一起兜住:
        // 先把引用抓进局部变量,再整体包 try/catch —— 直接写
        // `if (L != null) L.gc(...)` 在两步之间仍可能 NPE。
        try {
            LuaState cur = L;
            if (cur != null && !cur.isClosed()) {
                cur.gc(LuaState.LUA_GCCOLLECT, 1);
            }
        } catch (Throwable ignored) {
            // 已关闭/正被关闭,忽略
        }
        System.gc();

    }

    /**
     * 取消定时任务,并释放本任务持有的 Lua 虚拟机。
     *
     * <p>为什么必须在这里关:一个 {@link LuaTimerTask} 会跨多次触发**复用**
     * 同一个 VM(run() 里 `if (L == null) initLua()`),所以不能像
     * LuaThread/LuaRunnable 那样在 run() 末尾关闭。真正的生命周期终点是
     * cancel()(LuaTimer.stop() / LuaTimer.gc() 都会走到这里)。
     *
     * <p>不关的代价:newLuaState() 把 wrapper 注册进 LuaStateFactory.states
     * 这个静态 Map 并被强引用,于是 LuaState.finalize 的 close() 兜底永不触发 ——
     * 每建一个定时器就永久泄漏一个 lua_State(含它引用的全部 Java 对象)。
     *
     * <p>关闭后把 L 置 null:万一调用方 cancel 后又 start(LuaTimer 允许),
     * run() 里的 `if (L == null)` 会重新 initLua(),不会踩空指针。
     */
    @Override
    public boolean cancel() {
        if (L != null && !L.isClosed()) {
            try {
                L.gc(LuaState.LUA_GCCOLLECT, 1);
                L.close();
            } catch (Throwable ignored) {
                // 释放失败不该阻止取消
            }
            L = null;
        }
        return super.cancel();
    }

    public void setArg(Object[] arg) {
        mArg = arg;
    }

    public void setArg(LuaObject arg) throws ArrayIndexOutOfBoundsException, LuaException, IllegalArgumentException {
        mArg = arg.asArray();
    }

    public void setEnabled(boolean enabled) {
        mEnabled = enabled;
    }

    public boolean isEnabled() {
        return mEnabled;
    }


    public void set(String key, Object value) throws LuaException {
        L.pushObjectValue(value);
        L.setGlobal(key);
    }

    public Object get(String key) throws LuaException {
        L.getGlobal(key);
        return L.toJavaObject(-1);
    }

    private String errorReason(int error) {
        switch (error) {
            case 6:
                return "error error";
            case 5:
                return "GC error";
            case 4:
                return "Out of memory";
            case 3:
                return "Syntax error";
            case 2:
                return "Runtime error";
            case 1:
                return "Yield error";
        }
        return "Unknown error " + error;
    }

    private void initLua() throws LuaException {
        L = LuaStateFactory.newLuaState();
        L.openLibs();
        L.pushJavaObject(mLuaContext);
        if (mLuaContext instanceof LuaActivity) {
            L.setGlobal("activity");
        } else if (mLuaContext instanceof LuaService) {
            L.setGlobal("service");
        }
        L.pushJavaObject(this);
        L.setGlobal("this");

        L.pushContext(mLuaContext);

        JavaFunction print = new LuaPrint(mLuaContext, L);
        print.register("print");

        L.getGlobal("package");

        L.pushString(mLuaContext.getLuaLpath());
        L.setField(-2, "path");
        L.pushString(mLuaContext.getLuaCpath());
        L.setField(-2, "cpath");
        L.pop(1);

        JavaFunction set = new JavaFunction(L) {
            @Override
            public int execute() throws LuaException {

                mLuaContext.set(L.toString(2), L.toJavaObject(3));
                return 0;
            }
        };
        set.register("set");

        JavaFunction call = new JavaFunction(L) {
            @Override
            public int execute() throws LuaException {

                int top = L.getTop();
                if (top > 2) {
                    Object[] args = new Object[top - 2];
                    for (int i = 3; i <= top; i++) {
                        args[i - 3] = L.toJavaObject(i);
                    }
                    mLuaContext.call(L.toString(2), args);
                } else if (top == 2) {
                    mLuaContext.call(L.toString(2));
                }
                return 0;
            }
        };
        call.register("call");
    }

    private void newLuaThread(String str, Object... args) {
        try {

            if (Pattern.matches("^\\w+$", str)) {
                doAsset(str + ".lua", args);
            } else if (Pattern.matches("^[\\w\\.\\_/]+$", str)) {
                L.getGlobal("luajava");
                L.pushString(mLuaContext.getLuaDir());
                L.setField(-2, "luadir");
                L.pushString(str);
                L.setField(-2, "luapath");
                L.pop(1);

                doFile(str, args);
            } else {
                doString(str, args);
            }

        } catch (Exception e) {
            mLuaContext.sendError(this.toString(), e);

        }

    }

    private void newLuaThread(byte[] buf, Object... args) throws LuaException {
        int ok = 0;
        L.setTop(0);
        ok = L.LloadBuffer(buf, "TimerTask");

        if (ok == 0) {
            L.getGlobal("debug");
            L.getField(-1, "traceback");
            L.remove(-2);
            L.insert(-2);
            int l = args.length;
            for (int i = 0; i < l; i++) {
                L.pushObjectValue(args[i]);
            }
            ok = L.pcall(l, 0, -2 - l);
            if (ok == 0) {
                return;
            }
        }
        throw new LuaException(errorReason(ok) + ": " + L.toString(-1));
    }

    private void doFile(String filePath, Object... args) throws LuaException {
        int ok = 0;
        L.setTop(0);
        ok = L.LloadFile(filePath);

        if (ok == 0) {
            L.getGlobal("debug");
            L.getField(-1, "traceback");
            L.remove(-2);
            L.insert(-2);
            int l = args.length;
            for (int i = 0; i < l; i++) {
                L.pushObjectValue(args[i]);
            }
            ok = L.pcall(l, 0, -2 - l);
            if (ok == 0) {
                return;
            }
        }
        throw new LuaException(errorReason(ok) + ": " + L.toString(-1));
    }

    public void doAsset(String name, Object... args) throws LuaException, IOException {
        int ok = 0;
        byte[] bytes = LuaUtil.readAsset(mLuaContext.getContext(), name);
        L.setTop(0);
        ok = L.LloadBuffer(bytes, name);

        if (ok == 0) {
            L.getGlobal("debug");
            L.getField(-1, "traceback");
            L.remove(-2);
            L.insert(-2);
            int l = args.length;
            for (int i = 0; i < l; i++) {
                L.pushObjectValue(args[i]);
            }
            ok = L.pcall(l, 0, -2 - l);
            if (ok == 0) {
                return;
            }
        }
        throw new LuaException(errorReason(ok) + ": " + L.toString(-1));
    }

    private void doString(String src, Object... args) throws LuaException {
        L.setTop(0);
        int ok = L.LloadString(src);

        if (ok == 0) {
            L.getGlobal("debug");
            L.getField(-1, "traceback");
            L.remove(-2);
            L.insert(-2);
            int l = args.length;
            for (int i = 0; i < l; i++) {
                L.pushObjectValue(args[i]);
            }
            ok = L.pcall(l, 0, -2 - l);
            if (ok == 0) {

                return;
            }
        }
        throw new LuaException(errorReason(ok) + ": " + L.toString(-1));
    }


    private void runFunc(String funcName, Object... args) {
        try {
            L.setTop(0);
            L.getGlobal(funcName);
            if (L.isFunction(-1)) {
                L.getGlobal("debug");
                L.getField(-1, "traceback");
                L.remove(-2);
                L.insert(-2);

                int l = args.length;
                for (int i = 0; i < l; i++) {
                    L.pushObjectValue(args[i]);
                }

                int ok = L.pcall(l, 1, -2 - l);
                if (ok == 0) {
                    return;
                }
                throw new LuaException(errorReason(ok) + ": " + L.toString(-1));
            }
        } catch (LuaException e) {
            mLuaContext.sendError(this + " " + funcName, e);
        }

    }

    private void setField(String key, Object value) {
        try {
            L.pushObjectValue(value);
            L.setGlobal(key);
        } catch (LuaException e) {
            mLuaContext.sendError(this.toString(), e);
        }
    }

}
