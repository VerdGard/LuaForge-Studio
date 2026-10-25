/*
 * $Id: LuaInvocationHandler.java,v 1.4 2006/12/22 14:06:40 thiago Exp $
 * Copyright (C) 2003-2007 Kepler Project.
 *
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the
 * "Software"), to deal in the Software without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to
 * the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
 * IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY
 * CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT,
 * TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
 * SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package com.luajava;

import android.util.Log;

import com.androlua.LuaContext;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.Arrays;

/**
 * Class that implements the InvocationHandler interface.
 * This class is used in the LuaJava's proxy system.
 * When a proxy object is accessed, the method invoked is
 * called from Lua
 *
 * @author Rizzato
 * @author Thiago Ponte
 */
public class LuaInvocationHandler implements InvocationHandler {
    private final LuaContext mContext;
    private final LuaObject obj;

    public LuaInvocationHandler(LuaObject obj) {
        this.obj = obj;
        mContext = obj.getLuaState().getContext();
    }

    /**
     * Function called when a proxy object function is invoked.
     */
    public Object invoke(Object proxy, Method method, Object[] args) throws LuaException {
        // 这里原本是 Log.i(TAG, "invoke: " + obj + ";" + method + ";" + Arrays.toString(args))。
        // 那行日志就是崩溃现场:字符串拼接会对 obj 隐式调用 toString(),
        // 而 toString() 要进 native(push -> rawGetI)。当 Lua 侧已 close 这个
        // LuaState 时,传进 JNI 的就是 0 指针,对 NULL 解引用 -> SIGSEGV。
        // tombstone 的 #11~#13 正是 LuaObject.toString <- StringBuilder.append
        // <- LuaInvocationHandler.invoke。
        // 改为不拼接 obj(用身份哈希代替),避免任何隐式 toString。
        if (DEBUG_LOG) {
            Log.i(TAG, "invoke: proxy@" + System.identityHashCode(obj)
                    + "." + method.getName() + ";" + Arrays.toString(args));
        }

        // 存活检查必须在取锁之前做,且**不能**靠抛异常来表达。
        // 代理通常被当作 Java 监听器/Runnable 投递给主线程 Handler 排队执行,
        // 等轮到它时页面可能已经销毁、LuaState 已被 close()。此时:
        //   - 继续往下走,isFunction()/getField() 都会去读 0 指针;
        //   - 若这里抛 LuaException,Proxy 会把它包成
        //     UndeclaredThrowableException 抛给调用方(Android 框架),
        //     同样沿着 Handler.dispatchMessage 冲到主线程 -> 进程直接被打掉。
        // 因此"已关闭"一律降级为返回该方法的类型默认值,让迟到回调安静失效。
        LuaState state = (obj == null) ? null : obj.getLuaState();
        if (obj == null || obj.isClosed() || state == null || state.isClosed()) {
            return defaultValue(method.getReturnType());
        }

        synchronized (state) {
            String methodName = method.getName();
            LuaObject func;
            if (obj.isFunction()) {
                func = obj;
            } else {
                func = obj.getField(methodName);
            }
            Class<?> retType = method.getReturnType();

            if (func.isNil()) {
                // 原实现一律返回 Integer 0:当接口声明的是 long/double 时,
                // Proxy 在返回处会做强制转换 -> ClassCastException 冲到调用方。
                // 按实际返回类型给默认值。
                return defaultValue(retType);
            }

            Object ret = null;
            try {
                // Checks if returned type is void. if it is returns null.
                if (retType.equals(Void.class) || retType.equals(void.class)) {
                    func.call(args);
                    ret = null;
                } else {
                    ret = func.call(args);
                    if (ret != null && ret instanceof Double) {
                        ret = LuaState.convertLuaNumber((Double) ret, retType);
                    }
                }
            } catch (Exception e) {
                // Lua 侧报错照旧上报;但 state 在调用过程中被关闭时不该再往外抛,
                // 否则迟到回调会把异常带上主线程。
                if (mContext != null && !isDead(state)) {
                    mContext.sendError(methodName, e);
                }
                return defaultValue(retType);
            } catch (Throwable t) {
                // 兜底:invoke() 处在 Proxy 调用链上,任何 Throwable 逃出去都可能
                // 沿 Handler.dispatchMessage 终结进程。
                return defaultValue(retType);
            }
            if (ret == null) {
                return defaultValue(retType);
            }
            return ret;
        }
    }

    private static final String TAG = "LuaInvocationHandler";

    /** 逐次代理调用都打日志开销过大,默认关闭;排查时置 true。 */
    private static final boolean DEBUG_LOG = false;

    private static boolean isDead(LuaState state) {
        return state == null || state.isClosed();
    }

    /**
     * 该类型的"默认值",用于 Lua 侧已不可用时的迟到回调。
     *
     * <p>必须按返回类型给值:监听器回调多为 void,但 Java 侧也有
     * {@code boolean onXxx()}、{@code int getCount()} 这类必须返回定值的接口,
     * 直接返回 null 会在自动拆箱处抛 NullPointerException。
     */
    private static Object defaultValue(Class<?> retType) {
        if (retType == null) {
            return null;
        }
        if (retType == Void.class || retType == void.class) {
            return null;
        }
        if (retType == boolean.class || retType == Boolean.class) {
            return Boolean.FALSE;
        }
        if (retType == char.class || retType == Character.class) {
            return '\0';
        }
        if (retType == byte.class || retType == Byte.class) {
            return (byte) 0;
        }
        if (retType == short.class || retType == Short.class) {
            return (short) 0;
        }
        if (retType == int.class || retType == Integer.class) {
            return 0;
        }
        if (retType == long.class || retType == Long.class) {
            return 0L;
        }
        if (retType == float.class || retType == Float.class) {
            return 0f;
        }
        if (retType == double.class || retType == Double.class) {
            return 0d;
        }
        return null;
    }
}
