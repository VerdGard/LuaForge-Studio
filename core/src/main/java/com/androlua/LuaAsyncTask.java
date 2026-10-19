package com.androlua;

import com.androlua.util.AsyncTaskX;
import com.luajava.JavaFunction;
import com.luajava.LuaException;
import com.luajava.LuaObject;
import com.luajava.LuaState;
import com.luajava.LuaStateFactory;

public class LuaAsyncTask extends AsyncTaskX implements LuaGcable {

    static {
        AsyncTaskX.setDefaultExecutor(AsyncTaskX.THREAD_POOL_EXECUTOR);
    }

    private Object[] loadeds;
    private boolean mGc;

    @Override
    public void gc() {
        // TODO: Implement this method
        if (getStatus() == Status.RUNNING)
            cancel(true);
        mGc = true;
    }

    @Override
    public boolean isGc() {
        return mGc;
    }

    private LuaState L;

    private final LuaContext mLuaContext;

    private byte[] mBuffer;

    private long mDelay = 0;

    private final LuaObject mCallback;

    private LuaObject mUpdate;

    public LuaAsyncTask(LuaContext luaContext, long delay, LuaObject callback) throws LuaException {
        luaContext.regGc(this);
        mLuaContext = luaContext;
        mDelay = delay;
        mCallback = callback;
    }

    public LuaAsyncTask(LuaContext luaContext, String src, LuaObject callback) throws LuaException {
        luaContext.regGc(this);
        mLuaContext = luaContext;
        mBuffer = src.getBytes();
        mCallback = callback;
    }


    public LuaAsyncTask(LuaContext luaContext, LuaObject func, LuaObject callback) throws LuaException {
        luaContext.regGc(this);
        mLuaContext = luaContext;
        mBuffer = func.dump();
        mCallback = callback;
        LuaState l = func.getLuaState();
        LuaObject g = l.getLuaObject("luajava");
        LuaObject loaded = g.getField("imported");
        if (!loaded.isNil()) {
            loadeds = loaded.asArray();
        }
    }

    public LuaAsyncTask(LuaContext luaContext, LuaObject func, LuaObject update, LuaObject callback) throws LuaException {
        luaContext.regGc(this);
        mLuaContext = luaContext;
        mBuffer = func.dump();
        mUpdate = update;
        mCallback = callback;
    }

    public void execute() throws IllegalArgumentException, ArrayIndexOutOfBoundsException, LuaException {
        // TODO: Implement this method
        super.execute();
    }

    public void update(Object msg) {
        publishProgress(msg);
    }

    public void update(String msg) {
        publishProgress(msg);
    }

    public void update(int msg) {
        publishProgress(msg);
    }

    @Override
    protected Object doInBackground(Object[] args) {
        if (mDelay != 0) {
            try {
                Thread.sleep(mDelay);
            } catch (InterruptedException e) {
            }
            return args;
        }
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

        L.getGlobal("luajava");
        L.pushString(mLuaContext.getLuaDir());
        L.setField(-2, "luadir");
        L.pop(1);

        try {
            JavaFunction print = new LuaPrint(mLuaContext, L);
            print.register("print");

            JavaFunction update = new JavaFunction(L) {

                @Override
                public int execute() throws LuaException {
                    // TODO: Implement this method
                    update(L.toJavaObject(2));
                    return 0;
                }
            };

            update.register("update");

            L.getGlobal("package");

            L.pushString(mLuaContext.getLuaLpath());
            L.setField(-2, "path");
            L.pushString(mLuaContext.getLuaCpath());
            L.setField(-2, "cpath");
            L.pop(1);
        } catch (LuaException e) {
            mLuaContext.sendError("AsyncTask", e);
        }

        if (loadeds != null) {
            LuaObject require = L.getLuaObject("require");
            try {
                require.call("import");
                LuaObject _import = L.getLuaObject("import");
                for (Object s : loadeds)
                    _import.call(s.toString());
            } catch (LuaException e) {
                //	e.printStackTrace();
            }
        }

        try {
            L.setTop(0);
            int ok = L.LloadBuffer(mBuffer, "LuaAsyncTask");

            if (ok == 0) {
                L.getGlobal("debug");
                L.getField(-1, "traceback");
                L.remove(-2);
                L.insert(-2);
                int l = args.length;
                for (int i = 0; i < l; i++) {
                    L.pushObjectValue(args[i]);
                }
                ok = L.pcall(l, LuaState.LUA_MULTRET, -2 - l);
                if (ok == 0) {
                    int n = L.getTop() - 1;
                    Object[] ret = new Object[n];
                    for (int i = 0; i < n; i++)
                        ret[i] = L.toJavaObject(i + 2);
                    return ret;
                }
            }
            throw new LuaException(errorReason(ok) + ": " + L.toString(-1));
        } catch (Exception e) {
            mLuaContext.sendError("doInBackground", e);
        }


        return null;
    }

    @Override
    protected void onPostExecute(Object result) {
        if (isCancelled()) {
            // 取消路径原本直接 return,连 gc 都不做 —— 而 doInBackground 里
            // 已经 newLuaState() 建过 VM 了,于是这个 VM 永久留在
            // LuaStateFactory.states 里。执行 Lua 脚本的任务常被取消
            // (页面切走、gc() 主动 cancel),这条路径比正常完成更频繁,
            // 所以必须先把回调跑完再统一释放。
            runCallbackSafely(null);
            closeLua();
            return;
        }
        runCallbackSafely(result);
        closeLua();
    }

    private void runCallbackSafely(Object result) {
        if (mCallback == null) return;
        // mCallback 是**父上下文**(LuaActivity/LuaService)那个 VM 上的函数引用,
        // 不是本任务自建的 L 上的。而父 Activity 的 onDestroy 现在会 close 掉
        // 父 VM —— 若任务在页面销毁后才回调,这个引用指向的 native state 已经没了。
        // LuaObject.call() 会走到 _pushValue(0, ...):那是空指针,**直接崩在 native**,
        // 而不是抛 LuaException,所以必须在 Java 侧先拦。
        LuaState owner = mCallback.getLuaState();
        if (owner == null || owner.isClosed()) {
            return;
        }
        try {
            mCallback.call((Object[]) result);
        } catch (LuaException e) {
            mLuaContext.sendError("onPostExecute", e);
        } catch (Throwable t) {
            // LuaContext.sendError 只声明了 Exception 形参(Error 需走
            // LuaActivity.sendError(String, Throwable) 那个重载)。这里必须继续捕
            // Throwable:回调里抛 Error(StackOverflowError 等)若逃逸会直接崩进程。
            mLuaContext.sendError("onPostExecute", asException(t));
        }
    }

    /**
     * 释放本任务自建的 Lua 虚拟机。
     *
     * <p>{@code doInBackground} 为每个任务 {@code newLuaState()} 了一个独立 VM,
     * 但旧代码只做了 {@code L.gc(...)} —— 那是"让 Lua 自己回收垃圾",
     * 不是"释放这个 VM"。VM 的 wrapper 被 {@code LuaStateFactory.states}
     * 静态持有,finalize 兜底永远不会触发,所以每一层
     * {@code task(function() ... end)} 都实实在在泄漏一整个 lua_State
     * (含 registry、栈、以及它引用的所有 Java 对象)。
     * 原代码末尾那句被注释掉的 {@code //L.close();} 就是当时想到了但没做完。
     *
     * <p>只在 doInBackground 真的建过 VM(L != null)时关闭;mDelay 分支
     * 提前返回、从未建 VM,此时 L 为 null。
     */
    private void closeLua() {
        if (L != null && !L.isClosed()) {
            try {
                L.gc(LuaState.LUA_GCCOLLECT, 1);
                L.close();
            } catch (Throwable ignored) {
                // 释放失败不能影响任务收尾
            }
        }
        L = null;
        System.gc();
    }

    /**
     * 把 {@link Throwable} 适配成 {@link Exception}。
     *
     * <p>{@code LuaContext#sendError} 的形参类型是 {@code Exception},但收尾路径
     * 必须捕 {@code Throwable} —— 回调里可能抛 {@code Error}。已经是
     * {@code Exception} 的原样返回,避免多包一层改变日志里的异常类型。
     */
    private static Exception asException(Throwable t) {
        return (t instanceof Exception) ? (Exception) t : new RuntimeException(t);
    }

    @Override
    protected void onProgressUpdate(Object[] values) {
        // mUpdate 与 mCallback 一样,引用的是**父上下文**那个 VM 上的函数。
        // 父 Activity 的 onDestroy 现在会 close 掉父 VM,而进度回调可能在其后
        // 才被投递到主线程 —— 直接 call() 会撞上 native 空指针,所以先判存活。
        LuaObject update = mUpdate;
        if (update != null) {
            LuaState owner = update.getLuaState();
            if (owner != null && !owner.isClosed()) {
                try {
                    update.call(values);
                } catch (LuaException e) {
                    mLuaContext.sendError("onProgressUpdate", e);
                } catch (Throwable t) {
                    mLuaContext.sendError("onProgressUpdate", asException(t));
                }
            }
        }
        super.onProgressUpdate(values);
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

}

