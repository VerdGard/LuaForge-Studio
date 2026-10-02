package com.luaforge.studio.console.core;

/**
 * 方法调用拦截结果。
 *
 * <p>字段为 public:core 侧 ConsoleBridgeRef 以反射读 status / replaceValue
 * (Class.getField 只能取 public 字段),故此处不可改为 Kotlin 私有属性。
 */
public final class MethodCallResult {

    public enum Status {
        ALLOW,
        VETO,
        REPLACE
    }

    public static final MethodCallResult ALLOW = new MethodCallResult(Status.ALLOW, null);

    public final Status status;
    public final Object replaceValue;

    private MethodCallResult(Status status, Object replaceValue) {
        this.status = status;
        this.replaceValue = replaceValue;
    }

    /** 阻断本次调用(Lua 侧得到 nil)。 */
    public static MethodCallResult veto() {
        return new MethodCallResult(Status.VETO, null);
    }

    /** 以 value 替代本次调用的返回值。 */
    public static MethodCallResult replace(Object value) {
        return new MethodCallResult(Status.REPLACE, value);
    }
}
