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

    /** run() 是否正在执行(受 this 保护)。 */
    private boolean mRunning;

    /** 是否已请求取消;置位后 run() 不再执行新的触发。 */
    private boolean mCancelPending;

    /** cancel() 撞上 run() 执行中时,待 run() 收尾释放的 state。 */
    private LuaState mPendingClose;

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

        LuaState cur;
        boolean justCreated = false;
        synchronized (this) {
            // 已请求取消,不再执行
            if (mCancelPending) {
                return;
            }
            cur = L;
            if (cur != null && cur.isClosed()) {
                // 已关闭但字段没被清掉:当作未初始化,绝不能带着 0 指针进 native
                L = null;
                cur = null;
            }
            if (cur == null) {
                try {
                    initLua();
                } catch (Throwable t) {
                    mLuaContext.sendError(this.toString(), asException(t));
                    return;
                }
                cur = L;
                justCreated = true;
            }
            mRunning = true;
        }

        // 注意:Lua 代码在这里**不持 this 锁**执行。
        // 若一直持锁,cancel()(来自 UI 线程的 LuaTimer.stop()/gc()、
        // LuaActivity.onDestroy)就会被一次长脚本阻塞到 ANR。
        try {
            if (justCreated) {
                if (mBuffer != null)
                    newLuaThread(mBuffer, mArg);
                else
                    newLuaThread(mSrc, mArg);
            } else {
                cur.getGlobal("run");
                if (!cur.isNil(-1))
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
        } catch (Throwable t) {
            // 关键:原本只捕获 LuaException。定时器运行在 TimerX 的调度线程上,
            // 任何别的 Throwable(NullPointerException、IllegalStateException、
            // StackOverflowError、OOM......)都会沿 task.run() 一路上抛到该线程的
            // run(),而这个线程没有任何 UncaughtExceptionHandler 兜底 ——
            // ART 直接终结整个进程,表现为"定时器一跑就闪退/自动重启",
            // 且 tombstone 里看不到任何 Lua 栈。
            mLuaContext.sendError(this.toString(), asException(t));
        } finally {
            LuaState toClose = null;
            LuaState toGc = null;
            synchronized (this) {
                mRunning = false;
                if (mCancelPending) {
                    // cancel() 在执行期间到达:释放延后到这里,保证不会
                    // 一边跑 Lua 一边 lua_close(那正是 use-after-free 的来源)
                    toClose = mPendingClose;
                    mPendingClose = null;
                    L = null;
                } else {
                    toGc = L;
                }
            }
            if (toClose != null) {
                closeQuietly(toClose);
            } else if (toGc != null && !toGc.isClosed()) {
                try {
                    toGc.gc(LuaState.LUA_GCCOLLECT, 1);
                } catch (Throwable ignored) {
                    // 已关闭/正被关闭,忽略
                }
            }
        }
        System.gc();

    }

    /** 关闭一个 LuaState,任何失败都吞掉(收尾路径不该再抛)。 */
    private static void closeQuietly(LuaState state) {
        if (state == null || state.isClosed()) {
            return;
        }
        try {
            state.gc(LuaState.LUA_GCCOLLECT, 1);
            state.close();
        } catch (Throwable ignored) {
            // 释放失败不该影响收尾
        }
    }

    /** 把任意 Throwable 包装成 Exception,供 LuaContext.sendError 使用。 */
    private static Exception asException(Throwable t) {
        return (t instanceof Exception) ? (Exception) t : new RuntimeException(t);
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
        LuaState toClose = null;
        synchronized (this) {
            mCancelPending = true;
            LuaState cur = L;
            if (cur == null) {
                // 未初始化或已被关闭路径清空,无需处理
            } else if (mRunning) {
                // run() 正在执行 Lua:把释放交给它收尾,这里绝不 lua_close,
                // 否则就是"边跑边释放" —— 崩溃栈 LuaObject.push →
                // LuaState.rawGetI → pushJNIEnv(NULL) 正是这么产生的。
                //
                // 关键:此时**不能**把字段 L 置 null。run() 执行 Lua 时不持
                // this 锁,而 runFunc()/newLuaThread()/doFile() 等内部都直接用
                // 字段 L;置空会让正在跑的脚本立刻 NPE。字段 L 由 run() 的
                // finally 在收尾时统一置 null。
                mPendingClose = cur;
            } else {
                L = null;
                toClose = cur;
            }
        }
        if (toClose != null) {
            closeQuietly(toClose);
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


    /**
     * 取当前可用的 LuaState,已关闭或未初始化则抛 LuaException。
     *
     * <p>原本 set()/get() 直接写 {@code L.xxx}:定时器被 cancel 后 L 为 null,
     * 或在 run() 之前被外部调用,都会 NPE;若 L 已 close 则是 0 指针进 native。
     * 两种都会终结进程,这里统一转成可捕获的 LuaException。
     */
    private LuaState requireLuaState() throws LuaException {
        LuaState cur;
        synchronized (this) {
            cur = L;
        }
        if (cur == null || cur.isClosed()) {
            throw new LuaException("LuaTimerTask is not running");
        }
        return cur;
    }

    public void set(String key, Object value) throws LuaException {
        LuaState cur = requireLuaState();
        synchronized (cur) {
            cur.pushObjectValue(value);
            cur.setGlobal(key);
        }
    }

    public Object get(String key) throws LuaException {
        LuaState cur = requireLuaState();
        synchronized (cur) {
            cur.getGlobal(key);
            return cur.toJavaObject(-1);
        }
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
