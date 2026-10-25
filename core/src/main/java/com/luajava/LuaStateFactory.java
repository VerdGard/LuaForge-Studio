/*
 * $Id: LuaStateFactory.java,v 1.4 2006/12/22 14:06:40 thiago Exp $
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

import java.util.HashMap;
import java.util.Map;

/**
 * This class is responsible for instantiating new LuaStates.
 * When a new LuaState is instantiated it is put into a List
 * and an index is returned. This index is registred in Lua
 * and it is used to find the right LuaState when lua calls
 * a Java Function.
 *
 * @author Thiago Ponte
 */
public final class LuaStateFactory {
    /**
     * Array with all luaState's instances
     */
    private static final Map<Long, LuaState> states = new HashMap<Long, LuaState>();

    /**
     * Non-public constructor.
     */
    private LuaStateFactory() {
    }

    /**
     * Method that creates a new instance of LuaState
     *
     * @return LuaState
     */
    public synchronized static LuaState newLuaState() {
        LuaState L = new LuaState();

        states.put(L.getPointer(), L);

        return L;
    }

    /**
     * Returns a existing instance of LuaState
     *
     * @param index
     * @return LuaState
     */
    public synchronized static LuaState getExistingState(long index) {
        LuaState l = states.get(index);
        if (l == null) {
            l = new LuaState(index);
            states.put(index, l);
        }
        return l;
    }

    /**
     * Receives a existing LuaState and checks if it exists in the states list.
     * If it doesn't exist adds it to the list.
     *
     * @param L
     * @return int
     */
    public synchronized static long insertLuaState(LuaState L) {
        states.put(L.getPointer(), L);

        return L.getPointer();
    }

    /**
     * 从 states 表里摘除一个 native 指针的登记(须带归属校验)。
     *
     * <p>为什么要带归属校验:{@link #getExistingState(long)} 在指针未知时会**新建**
     * 一个 wrapper 并把同一个指针登记进去,于是同一 {@code idx} 可能先后对应
     * 多个 wrapper。若关闭 A 时无条件 {@code remove(idx)},会把仍然存活的 B
     * 也从表里摘掉 —— 之后 Lua 回调经 {@code getExistingState} 再拿到指针时又会
     * 新建 C,而 C 与 B 共用同一 native state,A 的 {@code _close} 却已经把这块
     * 内存释放了。B/C 后续任何操作都是 use-after-free,表现随机且致命。
     *
     * <p>另外必须用 {@code remove} 而不是 {@code put(idx, null)}:后者在静态
     * Map 里留下的空条目永不回收,每关闭一个 LuaState 就多一条 (Long, null),
     * 反复开关页面时这张表会单调变大。语义等价 —— {@code getExistingState}
     * 对"不存在"和"值为 null"的处理完全相同(都会新建)。
     *
     * @param idx   待摘除的 native 指针
     * @param owner 发起关闭的 wrapper
     */
    public synchronized static void removeLuaState(long idx, LuaState owner) {
        if (idx == 0) {
            return;
        }
        LuaState registered = states.get(idx);
        if (registered == null || registered == owner) {
            states.remove(idx);
        }
    }

    /**
     * 崩溃诊断用:列出所有注册过的 native 指针及其当前 wrapper 的关闭状态。
     * 注意:不调用 LuaState 的任何 synchronized 方法,只读裸字段,避免在
     * LuaState monitor 卡死的现场追加自锁。
     * 同一指针可能对应多个 wrapper(removeLuaState 置 null 后 getExistingState 复活),
     * 借此可发现「残留 LuaState/LuaFunction 与已关闭 native state 脱钩」这类崩溃根因。
     */
    public static String snapshotStates() {
        StringBuilder sb = new StringBuilder();
        synchronized (LuaStateFactory.class) {
            for (Map.Entry<Long, LuaState> e : states.entrySet()) {
                LuaState L = e.getValue();
                boolean closed = L == null || L.getPointerUnsafe() == 0;
                sb.append("ptr=").append(e.getKey())
                  .append(" closed=").append(closed)
                  .append('\n');
            }
        }
        return sb.toString();
    }


}
