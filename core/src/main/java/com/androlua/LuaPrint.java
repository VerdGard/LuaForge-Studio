package com.androlua;

import com.luajava.JavaFunction;
import com.luajava.LuaException;
import com.luajava.LuaState;

public class LuaPrint extends JavaFunction {

    private final LuaState L;
    private final LuaContext mLuaContext;
    private final StringBuilder output = new StringBuilder();

    public LuaPrint(LuaContext luaContext, LuaState L) {
        super(L);
        this.L = L;
        mLuaContext = luaContext;
    }

    @Override
    public int execute() throws LuaException {
        int top = L.getTop();
        if (top < 2) {
            mLuaContext.sendMsg("");
            return 0;
        }
        for (int i = 2; i <= top; i++) {
            int type = L.type(i);
            String val = null;
            String stype = L.typeName(type);
            if (stype.equals("userdata")) {
                Object obj = L.toJavaObject(i);
                if (obj != null)
                    val = obj.toString();
            } else if (stype.equals("boolean")) {
                val = L.toBoolean(i) ? "true" : "false";
            } else {
                val = L.LtoString(i);
            }
            if (val == null)
                val = stype;
            output.append("\t");
            output.append(val);
            output.append("\t");
        }
        String text = output.toString().substring(1, output.length() - 1);
        mLuaContext.sendMsg(text);
        output.setLength(0);
        return 0;
    }


}
