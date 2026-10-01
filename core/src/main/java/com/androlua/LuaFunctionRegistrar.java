package com.androlua;

import android.content.Context;
import android.util.Log;

import androidx.recyclerview.widget.RecyclerView;

import com.luaforge.studio.utils.LuaRecyclerAdapter;
import com.luajava.JavaFunction;
import com.luajava.LuaException;
import com.luajava.LuaObject;
import com.luajava.LuaState;

import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class LuaFunctionRegistrar {

    private static final String TAG = "LuaFunctionRegistrar";

    // 工具类映射表，用于快速查找
    private static final Map<String, String> UTIL_CLASS_MAP =
            new HashMap<String, String>() {
                {
                    put("BitmapUtil", "com.luaforge.studio.utils.BitmapUtil");
                    put("GlideUtil", "com.luaforge.studio.utils.GlideUtil");
                    put("OkHttpUtil", "com.luaforge.studio.utils.OkHttpUtil");
                    put("UiUtil", "com.luaforge.studio.utils.UiUtil");
                    put("RecyclerAdapterUtil", "com.luaforge.studio.utils.RecyclerAdapterUtil");
                    put("ThemeUtil", "com.luaforge.studio.utils.ThemeUtil");
                }
            };

    private final LuaState L;
    private final Context context;
    private final String luaDir;

    public LuaFunctionRegistrar(LuaState L, Context context, String luaDir) {
        this.L = L;
        this.context = context;
        this.luaDir = luaDir;
    }

    /**
     * 根据提供的工具类名称列表选择性注册函数
     */
    public void registerSelectedFunctions(List<String> selectedUtils) {

        // 将列表转换为集合以便快速查找
        Set<String> selectedSet = new HashSet<>(selectedUtils);

        try {
            // 注册选中的工具类
            for (String utilName : selectedSet) {
                String className = UTIL_CLASS_MAP.get(utilName);
                if (className != null) {
                    registerUtilClass(className);
                } else {
                    Log.w(TAG, "未找到工具类: " + utilName);
                }
            }

            // 特殊处理：如果包含 OkHttpUtil，需要注册 HTTP 函数
            if (selectedSet.contains("OkHttpUtil")) {
                registerHttpFunctions();
            }

            if (selectedSet.contains("RecyclerAdapterUtil")) {
                registerRecyclerAdapterFunctions();
            }

            // 注册通用函数（无论选择什么工具类都需要的）
            registerCommonFunctions();

        } catch (Exception e) {
            Log.e(TAG, "选择性注册工具类函数时出错", e);
            e.printStackTrace();
        }
    }

    /**
     * 注册单个工具类
     */
    private void registerUtilClass(String className) {
        try {
            Class<?> clazz = Class.forName(className);
            Method[] methods = clazz.getMethods();

            for (final Method method : methods) {
                // 只处理公共静态方法,并跳过 Kotlin 合成方法(规则见 isRegisterable)
                if (!isRegisterable(method)) {
                    continue;
                }

                final String methodName = method.getName();

                // 检查是否需要Context参数
                final Class<?>[] paramTypes = method.getParameterTypes();
                final boolean needsContext =
                        paramTypes.length > 0 && Context.class.isAssignableFrom(paramTypes[0]);

                // 检查是否是可变参数方法
                final boolean isVarArgs = method.isVarArgs();

                // 为这个方法创建Lua函数
                L.pushJavaFunction(
                        new JavaFunction(L) {
                            @Override
                            public int execute() throws LuaException {
                                try {
                                    // 计算实际需要的Lua参数数量
                                    int paramCount = paramTypes.length;
                                    int luaArgCount = L.getTop() - 1;

                                    // 准备参数数组
                                    Object[] args = new Object[paramCount];
                                    int luaIndex = 2; // Lua参数从索引2开始

                                    // 如果需要Context，自动注入当前context
                                    if (needsContext) {
                                        args[0] = context;
                                    }

                                    // 处理固定参数（可变参数前的参数）
                                    int fixedParamCount = isVarArgs ? paramCount - 1 : paramCount;
                                    for (int i = needsContext ? 1 : 0; i < fixedParamCount; i++) {
                                        if (luaIndex <= L.getTop()) {
                                            args[i] =
                                                    coerce(L.toJavaObject(luaIndex), paramTypes[i]);
                                            luaIndex++;
                                        } else {
                                            // 缺少参数，设为null
                                            args[i] = null;
                                        }
                                    }

                                    // 处理可变参数（如果有）
                                    if (isVarArgs) {
                                        // 计算可变参数数量
                                        int varArgCount = luaArgCount - (fixedParamCount - (needsContext ? 1 : 0));

                                        if (varArgCount > 0) {
                                            // 创建数组来保存可变参数
                                            Class<?> varArgType = paramTypes[paramCount - 1].getComponentType();
                                            Object[] varArgs =
                                                    (Object[]) java.lang.reflect.Array.newInstance(varArgType, varArgCount);

                                            // 从Lua栈中获取可变参数
                                            for (int i = 0; i < varArgCount; i++) {
                                                varArgs[i] =
                                                        coerce(L.toJavaObject(luaIndex), varArgType);
                                                luaIndex++;
                                            }

                                            args[paramCount - 1] = varArgs;
                                        } else {
                                            // 如果没有可变参数，传入空数组
                                            Class<?> varArgType = paramTypes[paramCount - 1].getComponentType();
                                            args[paramCount - 1] = java.lang.reflect.Array.newInstance(varArgType, 0);
                                        }
                                    }

                                    // 调用方法
                                    Object result = method.invoke(null, args);

                                    // 处理返回值
                                    if (method.getReturnType() == void.class
                                            || method.getReturnType() == Void.class) {
                                        return 0; // 没有返回值
                                    } else {
                                        L.pushObjectValue(result);
                                        return 1; // 有返回值
                                    }

                                } catch (Exception e) {
                                    Log.e(TAG, "调用方法 " + methodName + " 失败: " + e.getMessage());
                                    e.printStackTrace();
                                    throw new LuaException("Failed to call " + methodName + ": " + e.getMessage());
                                }
                            }
                        });

                // 注册到Lua全局变量
                L.setGlobal(methodName);
            }

        } catch (ClassNotFoundException e) {
            e.printStackTrace();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 注册HTTP网络请求函数为Lua全局函数
     */
    private void registerHttpFunctions() {
        try {
            // ========== GET请求 ==========
            // get(url,cookie,charset,header,callback)
            JavaFunction httpGet =
                    new JavaFunction(L) {
                        @Override
                        public int execute() throws LuaException {
                            try {
                                int top = L.getTop();
                                if (top < 2) {
                                    throw new LuaException("参数不足，至少需要url和callback");
                                }

                                // Lua传递的第一个参数在索引2
                                final String url = L.toString(2);

                                // 解析可选参数
                                String cookie = null;
                                String charset = null;
                                HashMap<String, String> headers = null;
                                LuaObject callback = null;

                                // 遍历参数，判断类型（从索引3开始）
                                for (int i = 3; i <= top; i++) {
                                    int type = L.type(i);

                                    if (type == LuaState.LUA_TFUNCTION) {
                                        // 最后一个参数是callback
                                        callback = L.getLuaObject(i);
                                    } else if (type == LuaState.LUA_TSTRING) {
                                        String value = L.toString(i);

                                        // 判断是cookie还是charset
                                        if (cookie == null) {
                                            cookie = value;
                                        } else if (charset == null) {
                                            charset = value;
                                        }
                                    } else if (type == LuaState.LUA_TTABLE) {
                                        // 解析header表
                                        headers = new HashMap<>();
                                        L.pushNil();
                                        while (L.next(i) != 0) {
                                            try {
                                                String key = L.toString(-2);
                                                String value = L.toString(-1);
                                                headers.put(key, value);
                                            } catch (Exception e) {
                                                // 忽略转换错误
                                            }
                                            L.pop(1);
                                        }
                                    }
                                }

                                if (callback == null) {
                                    throw new LuaException("缺少callback函数");
                                }

                                final String finalCookie = cookie;
                                final String finalCharset = charset;
                                final HashMap<String, String> finalHeaders = headers;
                                final LuaObject finalCallback = callback;

                                Thread thread =
                                        new Thread(
                                                new Runnable() {
                                                    @Override
                                                    public void run() {
                                                        try {
                                                            com.luaforge.studio.utils.OkHttpUtil.HttpResponse response =
                                                                    com.luaforge.studio.utils.OkHttpUtil.get(
                                                                            context, // 使用传入的Context
                                                                            url,
                                                                            finalCookie,
                                                                            finalCharset,
                                                                            finalHeaders);

                                                            ((android.app.Activity) context)
                                                                    .runOnUiThread(
                                                                            new Runnable() {
                                                                                @Override
                                                                                public void run() {
                                                                                    try {
                                                                                        // 调用回调函数，传入四个参数
                                                                                        finalCallback.call(
                                                                                                response.getCode(),
                                                                                                response.getContent(),
                                                                                                response.getCookie(),
                                                                                                response.getHeaders());
                                                                                    } catch (
                                                                                            LuaException e) {
                                                                                        e.printStackTrace();
                                                                                        try {
                                                                                            finalCallback.call(
                                                                                                    -1,
                                                                                                    "Callback error: " + e.getMessage(),
                                                                                                    "",
                                                                                                    new HashMap<String, String>());
                                                                                        } catch (
                                                                                                LuaException e2) {
                                                                                            e2.printStackTrace();
                                                                                        }
                                                                                    }
                                                                                }
                                                                            });
                                                        } catch (Exception e) {
                                                            ((android.app.Activity) context)
                                                                    .runOnUiThread(
                                                                            new Runnable() {
                                                                                @Override
                                                                                public void run() {
                                                                                    try {
                                                                                        finalCallback.call(
                                                                                                -1,
                                                                                                "Error: " + e.getMessage(),
                                                                                                "",
                                                                                                new HashMap<String, String>());
                                                                                    } catch (
                                                                                            LuaException e2) {
                                                                                        e2.printStackTrace();
                                                                                    }
                                                                                }
                                                                            });
                                                        }
                                                    }
                                                });
                                thread.start();

                            } catch (Exception e) {
                                throw new LuaException(e);
                            }
                            return 0;
                        }
                    };
            httpGet.register("get");

            // ========== POST请求 ==========
            JavaFunction httpPost =
                    new JavaFunction(L) {
                        @Override
                        public int execute() throws LuaException {
                            try {
                                int top = L.getTop();
                                if (top < 3) {
                                    throw new LuaException("参数不足，至少需要url、data和callback");
                                }

                                // Lua传递的参数从索引2开始
                                final String url = L.toString(2);

                                // 解析参数
                                HashMap<String, String> formData = new HashMap<>();
                                String cookie = null;
                                String charset = null;
                                HashMap<String, String> headers = null;
                                LuaObject callback = null;

                                // 参数索引（从3开始）
                                int paramIndex = 3;

                                // 第一个参数应该是data表
                                if (paramIndex <= top) {
                                    int type = L.type(paramIndex);

                                    if (type == LuaState.LUA_TTABLE) {
                                        // 解析formData表
                                        L.pushNil();
                                        while (L.next(paramIndex) != 0) {
                                            try {
                                                String key = L.toString(-2);
                                                String value = L.toString(-1);
                                                formData.put(key, value);
                                            } catch (Exception e) {
                                                // 忽略转换错误
                                            }
                                            L.pop(1);
                                        }
                                        paramIndex++;
                                    } else if (type == LuaState.LUA_TFUNCTION) {
                                        // 没有data参数，直接是callback
                                        callback = L.getLuaObject(paramIndex);
                                        paramIndex++;
                                    }
                                }

                                // 继续解析剩余参数（如果有）
                                while (paramIndex <= top) {
                                    int type = L.type(paramIndex);

                                    if (type == LuaState.LUA_TFUNCTION) {
                                        // callback函数
                                        callback = L.getLuaObject(paramIndex);
                                        paramIndex++;
                                    } else if (type == LuaState.LUA_TSTRING) {
                                        String value = L.toString(paramIndex);

                                        // 判断是cookie还是charset
                                        if (cookie == null) {
                                            cookie = value;
                                        } else if (charset == null) {
                                            charset = value;
                                        }
                                        paramIndex++;
                                    } else if (type == LuaState.LUA_TTABLE) {
                                        // 解析header表
                                        headers = new HashMap<>();
                                        L.pushNil();
                                        while (L.next(paramIndex) != 0) {
                                            try {
                                                String key = L.toString(-2);
                                                String value = L.toString(-1);
                                                headers.put(key, value);
                                            } catch (Exception e) {
                                                // 忽略转换错误
                                            }
                                            L.pop(1);
                                        }
                                        paramIndex++;
                                    } else {
                                        // 未知参数类型，跳过
                                        paramIndex++;
                                    }
                                }

                                if (callback == null) {
                                    throw new LuaException("缺少callback函数");
                                }

                                final HashMap<String, String> finalFormData = formData.isEmpty() ? null : formData;
                                final String finalCookie = cookie;
                                final String finalCharset = charset;
                                final HashMap<String, String> finalHeaders = headers;
                                final LuaObject finalCallback = callback;

                                Thread thread =
                                        new Thread(
                                                new Runnable() {
                                                    @Override
                                                    public void run() {
                                                        try {
                                                            com.luaforge.studio.utils.OkHttpUtil.HttpResponse response =
                                                                    com.luaforge.studio.utils.OkHttpUtil.post(
                                                                            context,
                                                                            url,
                                                                            finalFormData,
                                                                            finalCookie,
                                                                            finalCharset,
                                                                            finalHeaders);

                                                            ((android.app.Activity) context)
                                                                    .runOnUiThread(
                                                                            new Runnable() {
                                                                                @Override
                                                                                public void run() {
                                                                                    try {
                                                                                        // 调用回调函数，传入四个参数
                                                                                        finalCallback.call(
                                                                                                response.getCode(),
                                                                                                response.getContent(),
                                                                                                response.getCookie(),
                                                                                                response.getHeaders());
                                                                                    } catch (
                                                                                            LuaException e) {
                                                                                        e.printStackTrace();
                                                                                        try {
                                                                                            finalCallback.call(
                                                                                                    -1,
                                                                                                    "Callback error: " + e.getMessage(),
                                                                                                    "",
                                                                                                    new HashMap<String, String>());
                                                                                        } catch (
                                                                                                LuaException e2) {
                                                                                            e2.printStackTrace();
                                                                                        }
                                                                                    }
                                                                                }
                                                                            });
                                                        } catch (Exception e) {
                                                            ((android.app.Activity) context)
                                                                    .runOnUiThread(
                                                                            new Runnable() {
                                                                                @Override
                                                                                public void run() {
                                                                                    try {
                                                                                        finalCallback.call(
                                                                                                -1,
                                                                                                "Error: " + e.getMessage(),
                                                                                                "",
                                                                                                new HashMap<String, String>());
                                                                                    } catch (
                                                                                            LuaException e2) {
                                                                                        e2.printStackTrace();
                                                                                    }
                                                                                }
                                                                            });
                                                        }
                                                    }
                                                });
                                thread.start();

                            } catch (Exception e) {
                                throw new LuaException(e);
                            }
                            return 0;
                        }
                    };
            httpPost.register("post");

            // ========== 文件下载 ==========
            JavaFunction httpDownload =
                    new JavaFunction(L) {
                        @Override
                        public int execute() throws LuaException {
                            try {
                                int top = L.getTop();
                                if (top < 3) {
                                    throw new LuaException("参数不足，至少需要url、path和callback");
                                }

                                // Lua传递的参数在索引2和3
                                final String url = L.toString(2);
                                final String savePath = L.toString(3);

                                // 获取完整保存路径
                                final String fullSavePath = getFullPathForHttp(savePath);

                                // 解析可选参数
                                String cookie = null;
                                HashMap<String, String> headers = null;
                                LuaObject callback = null;

                                // 遍历参数，判断类型（从索引4开始）
                                for (int i = 4; i <= top; i++) {
                                    int type = L.type(i);

                                    if (type == LuaState.LUA_TFUNCTION) {
                                        // 最后一个参数是callback
                                        callback = L.getLuaObject(i);
                                    } else if (type == LuaState.LUA_TSTRING) {
                                        // 只能是cookie
                                        cookie = L.toString(i);
                                    } else if (type == LuaState.LUA_TTABLE) {
                                        // 解析header表
                                        headers = new HashMap<>();
                                        L.pushNil();
                                        while (L.next(i) != 0) {
                                            try {
                                                String key = L.toString(-2);
                                                String value = L.toString(-1);
                                                headers.put(key, value);
                                            } catch (Exception e) {
                                                // 忽略转换错误
                                            }
                                            L.pop(1);
                                        }
                                    }
                                }

                                if (callback == null) {
                                    throw new LuaException("缺少callback函数");
                                }

                                final String finalCookie = cookie;
                                final HashMap<String, String> finalHeaders = headers;
                                final LuaObject finalCallback = callback;

                                Thread thread =
                                        new Thread(
                                                new Runnable() {
                                                    @Override
                                                    public void run() {
                                                        try {
                                                            com.luaforge.studio.utils.OkHttpUtil.HttpResponse response =
                                                                    com.luaforge.studio.utils.OkHttpUtil.download(
                                                                            context, url, fullSavePath, finalCookie, finalHeaders);

                                                            ((android.app.Activity) context)
                                                                    .runOnUiThread(
                                                                            new Runnable() {
                                                                                @Override
                                                                                public void run() {
                                                                                    try {
                                                                                        // 调用回调函数，传入四个参数
                                                                                        finalCallback.call(
                                                                                                response.getCode(),
                                                                                                response.getContent(),
                                                                                                response.getCookie(),
                                                                                                response.getHeaders());
                                                                                    } catch (
                                                                                            LuaException e) {
                                                                                        e.printStackTrace();
                                                                                        try {
                                                                                            finalCallback.call(
                                                                                                    -1,
                                                                                                    "Callback error: " + e.getMessage(),
                                                                                                    "",
                                                                                                    new HashMap<String, String>());
                                                                                        } catch (
                                                                                                LuaException e2) {
                                                                                            e2.printStackTrace();
                                                                                        }
                                                                                    }
                                                                                }
                                                                            });
                                                        } catch (Exception e) {
                                                            ((android.app.Activity) context)
                                                                    .runOnUiThread(
                                                                            new Runnable() {
                                                                                @Override
                                                                                public void run() {
                                                                                    try {
                                                                                        finalCallback.call(
                                                                                                -1,
                                                                                                "Error: " + e.getMessage(),
                                                                                                "",
                                                                                                new HashMap<String, String>());
                                                                                    } catch (
                                                                                            LuaException e2) {
                                                                                        e2.printStackTrace();
                                                                                    }
                                                                                }
                                                                            });
                                                        }
                                                    }
                                                });
                                thread.start();

                            } catch (Exception e) {
                                throw new LuaException(e);
                            }
                            return 0;
                        }
                    };
            httpDownload.register("download");

            // ========== 文件上传 ==========
            JavaFunction httpUpload =
                    new JavaFunction(L) {
                        @Override
                        public int execute() throws LuaException {
                            try {
                                int top = L.getTop();
                                if (top < 3) {
                                    throw new LuaException("参数不足，至少需要url、filePath和callback");
                                }

                                // Lua传递的参数在索引2和3
                                final String url = L.toString(2);
                                final String filePath = L.toString(3);

                                // 获取完整文件路径
                                final String fullFilePath = getFullPathForHttp(filePath);

                                // 解析可选参数
                                String cookie = null;
                                HashMap<String, String> headers = null;
                                LuaObject callback = null;

                                // 遍历参数，判断类型（从索引4开始）
                                for (int i = 4; i <= top; i++) {
                                    int type = L.type(i);

                                    if (type == LuaState.LUA_TFUNCTION) {
                                        // 最后一个参数是callback
                                        callback = L.getLuaObject(i);
                                    } else if (type == LuaState.LUA_TSTRING) {
                                        // 只能是cookie
                                        cookie = L.toString(i);
                                    } else if (type == LuaState.LUA_TTABLE) {
                                        // 解析header表
                                        headers = new HashMap<>();
                                        L.pushNil();
                                        while (L.next(i) != 0) {
                                            try {
                                                String key = L.toString(-2);
                                                String value = L.toString(-1);
                                                headers.put(key, value);
                                            } catch (Exception e) {
                                                // 忽略转换错误
                                            }
                                            L.pop(1);
                                        }
                                    }
                                }

                                if (callback == null) {
                                    throw new LuaException("缺少callback函数");
                                }

                                final String finalCookie = cookie;
                                final HashMap<String, String> finalHeaders = headers;
                                final LuaObject finalCallback = callback;

                                Thread thread =
                                        new Thread(
                                                new Runnable() {
                                                    @Override
                                                    public void run() {
                                                        try {
                                                            com.luaforge.studio.utils.OkHttpUtil.HttpResponse response =
                                                                    com.luaforge.studio.utils.OkHttpUtil.upload(
                                                                            context,
                                                                            url,
                                                                            fullFilePath,
                                                                            finalCookie,
                                                                            finalHeaders,
                                                                            "file", // fieldName 参数
                                                                            null // extraParams 参数
                                                                    );

                                                            ((android.app.Activity) context)
                                                                    .runOnUiThread(
                                                                            new Runnable() {
                                                                                @Override
                                                                                public void run() {
                                                                                    try {
                                                                                        // 调用回调函数，传入四个参数
                                                                                        finalCallback.call(
                                                                                                response.getCode(),
                                                                                                response.getContent(),
                                                                                                response.getCookie(),
                                                                                                response.getHeaders());
                                                                                    } catch (
                                                                                            LuaException e) {
                                                                                        e.printStackTrace();
                                                                                        try {
                                                                                            finalCallback.call(
                                                                                                    -1,
                                                                                                    "Callback error: " + e.getMessage(),
                                                                                                    "",
                                                                                                    new HashMap<String, String>());
                                                                                        } catch (
                                                                                                LuaException e2) {
                                                                                            e2.printStackTrace();
                                                                                        }
                                                                                    }
                                                                                }
                                                                            });
                                                        } catch (Exception e) {
                                                            ((android.app.Activity) context)
                                                                    .runOnUiThread(
                                                                            new Runnable() {
                                                                                @Override
                                                                                public void run() {
                                                                                    try {
                                                                                        finalCallback.call(
                                                                                                -1,
                                                                                                "Error: " + e.getMessage(),
                                                                                                "",
                                                                                                new HashMap<String, String>());
                                                                                    } catch (
                                                                                            LuaException e2) {
                                                                                        e2.printStackTrace();
                                                                                    }
                                                                                }
                                                                            });
                                                        }
                                                    }
                                                });
                                thread.start();

                            } catch (Exception e) {
                                throw new LuaException(e);
                            }
                            return 0;
                        }
                    };
            httpUpload.register("upload");

        } catch (Exception e) {
            e.printStackTrace();
            Log.e(TAG, "注册HTTP函数失败: " + e.getMessage());
        }
    }


    private void registerRecyclerAdapterFunctions() {
        try {

            // 注册createAdapter方法
            JavaFunction createAdapterFunc = new JavaFunction(L) {
                @Override
                public int execute() throws LuaException {
                    try {
                        // 获取参数：data, list_item, method
                        int top = L.getTop();
                        if (top < 3) {
                            throw new LuaException("参数不足，需要data, list_item, method三个参数");
                        }

                        // 获取data参数
                        List<Object> dataList = new ArrayList<>();

                        // 参数1是data表
                        if (L.isTable(1)) {
                            // 使用LuaTable的asArray()方法将Lua表转换为Java数组
                            LuaObject luaData = L.getLuaObject(1);

                            // 检查是否为数组表
                            if (luaData.isTable()) {
                                try {
                                    // 使用asArray()方法将Lua表转换为Object数组
                                    Object[] array = luaData.asArray();
                                    if (array != null) {
                                        // 将数组转换为List
                                        dataList = Arrays.asList(array);
                                    } else {
                                        // 如果asArray()返回null，尝试手动遍历
                                        int len = L.rawLen(1);
                                        for (int i = 1; i <= len; i++) {
                                            L.pushInteger(i);
                                            L.getTable(1);
                                            try {
                                                dataList.add(L.toJavaObject(-1));
                                            } catch (Exception e) {
                                                // 忽略转换错误
                                            }
                                            L.pop(1);
                                        }
                                    }
                                } catch (Exception e) {
                                    Log.e(TAG, "转换Lua表为数组失败: " + e.getMessage(), e);
                                    // 备用方法：手动遍历
                                    int len = L.rawLen(1);
                                    for (int i = 1; i <= len; i++) {
                                        L.pushInteger(i);
                                        L.getTable(1);
                                        try {
                                            dataList.add(L.toJavaObject(-1));
                                        } catch (Exception e2) {
                                            // 忽略转换错误
                                        }
                                        L.pop(1);
                                    }
                                }
                            }
                        } else if (L.isUserdata(1)) {
                            Object obj = L.toJavaObject(1);
                            if (obj instanceof List) {
                                dataList = (List<Object>) obj;
                            }
                        }

                        // 获取list_item参数
                        Object listItem;
                        int type = L.type(2);

                        switch (type) {
                            case LuaState.LUA_TSTRING:
                                listItem = L.toString(2);
                                break;
                            case LuaState.LUA_TNUMBER:
                                if (L.isInteger(2)) {
                                    listItem = (int) L.toInteger(2);
                                } else {
                                    listItem = L.toNumber(2);
                                }
                                break;
                            case LuaState.LUA_TTABLE:
                                listItem = L.getLuaObject(2);
                                break;
                            default:
                                listItem = L.toJavaObject(2);
                                break;
                        }

                        // 获取method参数
                        LuaObject method = L.getLuaObject(3);

                        // 调用RecyclerAdapterUtil.createAdapter
                        LuaRecyclerAdapter adapter =
                                com.luaforge.studio.utils.RecyclerAdapterUtil.createAdapter(
                                        context,
                                        dataList,
                                        listItem,
                                        method
                                );

                        L.pushJavaObject(adapter);
                        return 1;

                    } catch (Exception e) {
                        Log.e(TAG, "创建RecyclerAdapter失败: " + e.getMessage(), e);
                        throw new LuaException("创建RecyclerAdapter失败: " + e.getMessage());
                    }
                }
            };
            createAdapterFunc.register("createRecyclerAdapter");

            // 注册notifyDataSetChanged方法
            JavaFunction notifyDataSetChangedFunc = new JavaFunction(L) {
                @Override
                public int execute() throws LuaException {
                    try {
                        if (L.getTop() < 1) {
                            throw new LuaException("参数不足，需要adapter参数");
                        }

                        LuaObject adapterObj = L.getLuaObject(1);
                        if (adapterObj.isUserdata()) {
                            Object obj = adapterObj.getObject();
                            if (obj instanceof LuaRecyclerAdapter) {
                                ((LuaRecyclerAdapter) obj).notifyDataSetChanged();
                            } else if (obj instanceof RecyclerView.Adapter) {
                                ((RecyclerView.Adapter) obj).notifyDataSetChanged();
                            }
                        }
                        return 0;
                    } catch (Exception e) {
                        throw new LuaException(e);
                    }
                }
            };
            notifyDataSetChangedFunc.register("notifyDataSetChanged");


        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 注册一些通用的Lua函数
     */
    private void registerCommonFunctions() {
        try {
            // 注册获取Lua目录的函数
            JavaFunction getLuaDirectory =
                    new JavaFunction(L) {
                        @Override
                        public int execute() throws LuaException {
                            L.pushString(luaDir);
                            return 1;
                        }
                    };
            getLuaDirectory.register("getLuaDir");
        } catch (Exception e) {
        }
    }

    /**
     * 判断某个方法是否会按名字注册为 Lua 全局函数。
     *
     * [registerUtilClass] 与 [describeFunctions] 共用此规则:两处规则一旦不一致,
     * "查看"结果就会与运行时实际注册的函数漂移。
     */
    public static boolean isRegisterable(Method method) {
        return Modifier.isStatic(method.getModifiers())
                && Modifier.isPublic(method.getModifiers())
                && !method.getName().contains("$");
    }

    /**
     * 可注册为 Lua 全局函数的工具类名(运行时按 global_utils 里的名字查这张表)。
     *
     * 排序输出:底层是 HashMap,顺序本身不保证稳定,MCP 输出需要确定性。
     */
    public static List<String> getUtilNames() {
        List<String> names = new ArrayList<>(UTIL_CLASS_MAP.keySet());
        Collections.sort(names);
        return names;
    }

    /**
     * 按函数名查找其反射来源方法。
     *
     * 手工注册的 http / recycler 函数(以及 getLuaDir)没有对应的 Java 方法,返回 null。
     * 与 [describeFunctions] 共用同一套规则,因此"能查到方法"等价于"能按形参类型调用"。
     */
    public static Method findUtilMethod(String functionName, List<String> selectedUtils) {
        if (functionName == null || functionName.isEmpty() || selectedUtils == null) {
            return null;
        }
        for (String utilName : new LinkedHashSet<>(selectedUtils)) {
            String className = UTIL_CLASS_MAP.get(utilName);
            if (className == null) {
                continue;
            }
            try {
                for (Method method : Class.forName(className).getMethods()) {
                    if (isRegisterable(method) && method.getName().equals(functionName)) {
                        return method;
                    }
                }
            } catch (ClassNotFoundException e) {
                // 与 registerUtilClass 一致:找不到类就跳过
            }
        }
        return null;
    }

    /**
     * 描述给定 global_utils 列表在运行时**实际会注册**的 Lua 全局函数。
     *
     * 规则与 [registerSelectedFunctions] 保持一致:同一套反射过滤 + 同样三个特殊分支,
     * 供 MCP 的 list_global_utils 使用。未知名称、找不到的类在运行时只是被跳过,这里同样跳过。
     */
    public static List<FunctionInfo> describeFunctions(List<String> selectedUtils) {
        Set<String> selected = new LinkedHashSet<>(selectedUtils);
        // global_utils 为空时 LuaActivity 根本不会构造 LuaFunctionRegistrar(见 initENV),
        // 因此这里也返回空:没有函数会被注册。
        if (selected.isEmpty()) {
            return new ArrayList<>();
        }

        Map<String, FunctionInfo> out = new LinkedHashMap<>();

        for (String utilName : selected) {
            String className = UTIL_CLASS_MAP.get(utilName);
            if (className == null) {
                continue;
            }
            try {
                for (Method method : Class.forName(className).getMethods()) {
                    if (isRegisterable(method)) {
                        putFunction(out, describeMethod(utilName, method));
                    }
                }
            } catch (ClassNotFoundException e) {
                // 运行时同样会因找不到类而跳过
            }
        }

        if (selected.contains("OkHttpUtil")) {
            String callbackNote = "回调参数 (code, content, cookie, headers)";
            putFunction(out, handRegistered("get", "http",
                    Arrays.asList("url: string", "callback: function"), "无返回值",
                    "可选参数按类型识别:cookie / charset(string)、header(table);至少需要 url + callback。" + callbackNote));
            putFunction(out, handRegistered("post", "http",
                    Arrays.asList("url: string", "data: table", "callback: function"), "无返回值",
                    "data 为表单字段表;可选参数同 get;至少需要 url + data + callback。" + callbackNote));
            putFunction(out, handRegistered("download", "http",
                    Arrays.asList("url: string", "path: string", "callback: function"), "无返回值",
                    "path 相对项目目录解析;可选 cookie / header;至少需要 url + path + callback。" + callbackNote));
            putFunction(out, handRegistered("upload", "http",
                    Arrays.asList("url: string", "filePath: string", "callback: function"), "无返回值",
                    "表单字段名固定为 file,不支持附加字段;可选 cookie / header。" + callbackNote));
        }

        if (selected.contains("RecyclerAdapterUtil")) {
            putFunction(out, handRegistered("createRecyclerAdapter", "recycler",
                    Arrays.asList("data", "list_item", "method"), "LuaRecyclerAdapter",
                    "list_item 为列表项布局,method 为逐项绑定函数"));
            putFunction(out, handRegistered("notifyDataSetChanged", "recycler",
                    Arrays.asList("adapter"), "无返回值", null));
        }

        // 与 registerCommonFunctions 一致:只要 global_utils 非空就会注册
        putFunction(out, handRegistered("getLuaDir", "common",
                new ArrayList<>(), "string", "与所选工具类无关,始终注册;返回项目目录"));

        return new ArrayList<>(out.values());
    }

    /** 同名函数以后注册者为准(get/post/upload/download 会被 registerHttpFunctions 覆盖)。 */
    private static void putFunction(Map<String, FunctionInfo> out, FunctionInfo info) {
        FunctionInfo old = out.get(info.name);
        if (old != null) {
            info.overrides = old.source;
        }
        out.put(info.name, info);
    }

    /** 按与运行时相同的规则描述一个反射注册的静态方法。 */
    private static FunctionInfo describeMethod(String source, Method method) {
        Class<?>[] types = method.getParameterTypes();
        boolean contextInjected = types.length > 0 && Context.class.isAssignableFrom(types[0]);
        List<String> params = new ArrayList<>(types.length);
        for (int i = 0; i < types.length; i++) {
            params.add(paramLabel(types[i], i == 0 && contextInjected, i));
        }
        return new FunctionInfo(
                method.getName(),
                source,
                params,
                method.getReturnType().getSimpleName(),
                method.isVarArgs(),
                contextInjected,
                null);
    }

    /**
     * 形参标签 = "argN: 类型"。
     *
     * 不用 java.lang.reflect.Parameter / method.getParameters():它们从 API 26 才存在,
     * 而本模块 minSdk = 23,直接引用会让低版本设备在运行到此处时抛 NoClassDefFoundError。
     * 且本项目未开启 -parameters(Java)/ javaParameters(Kotlin),形参名本来就取不到,
     * 因此统一用 argN(由 parameterTypes 的下标得到)。
     */
    private static String paramLabel(Class<?> type, boolean injected, int index) {
        String name = "arg" + (index + 1);
        String typeName = type.isArray()
                ? type.getComponentType().getSimpleName() + "[]"
                : type.getSimpleName();
        if (injected) {
            // 首参只要是 Context 子类就会被框架注入。注意 Activity 也是 Context 子类,
            // 所以 ThemeUtil 那几个以 Activity 为首参的函数同样由框架注入真实 Activity。
            return name + ": " + typeName + "(自动注入)";
        }
        if (Context.class.isAssignableFrom(type)) {
            return name + ": " + typeName + "(需显式传入)";
        }
        return name + ": " + typeName;
    }

    /** 手工注册(非反射)的 Lua 全局函数描述。 */
    private static FunctionInfo handRegistered(String name, String source, List<String> params,
                                               String returnType, String note) {
        return new FunctionInfo(name, source, params, returnType, false, false, note);
    }

    /**
     * 把 Lua 送来的实参收窄/拓宽到目标形参类型。
     *
     * [LuaState.toJavaObject] 对数字统一返回 Long/Double,而反射调用只接受 Java 的
     * 拓宽转换:整数入参没问题(long 可拓宽到 float),但小数入参到 float/int/short
     * 形参属于收窄,会直接抛 "argument type mismatch"(例如 dp2px(16.5))。
     */
    private static Object coerce(Object value, Class<?> target) {
        if (value == null || target.isInstance(value)) {
            return value;
        }
        if (value instanceof Number number) {
            if (target == float.class || target == Float.class) return number.floatValue();
            if (target == double.class || target == Double.class) return number.doubleValue();
            if (target == int.class || target == Integer.class) return number.intValue();
            if (target == long.class || target == Long.class) return number.longValue();
            if (target == short.class || target == Short.class) return number.shortValue();
            if (target == byte.class || target == Byte.class) return number.byteValue();
            if (target == char.class || target == Character.class) return (char) number.intValue();
        }
        // 不再对 String 做 toString 兜底:那会把"传错类型"变成静默可用,
        // 掩盖项目里本该暴露的错误。数值收窄是必要的,类型不符则照旧抛给调用方。
        return value;
    }

    /** 一个会被注册到 Lua 全局表的函数(供 MCP 查看;不参与实际注册流程)。 */
    public static final class FunctionInfo {
        /** Lua 全局函数名。 */
        public final String name;
        /** 来源:工具类名,或 "http" / "recycler" / "common"。 */
        public final String source;
        /** 形参(简单类型名);首个 Context 形参标注为自动注入。 */
        public final List<String> params;
        /** 返回类型简单名。 */
        public final String returnType;
        /** 是否可变参数。 */
        public final boolean varArgs;
        /** 首个 Context 形参是否由框架自动注入,而非由 Lua 侧传入。 */
        public final boolean contextInjected;
        /** 覆盖了哪个来源的同名函数;未被覆盖时为 null(由 putFunction 填充)。 */
        public String overrides;
        /** 额外说明(可选参数、使用限制等)。 */
        public final String note;

        FunctionInfo(String name, String source, List<String> params, String returnType,
                     boolean varArgs, boolean contextInjected, String note) {
            this.name = name;
            this.source = source;
            this.params = params;
            this.returnType = returnType;
            this.varArgs = varArgs;
            this.contextInjected = contextInjected;
            this.note = note;
        }

        @Override
        public String toString() {
            return name + "(" + String.join(", ", params) + ") -> " + returnType
                    + " [" + source + "]"
                    + (overrides == null ? "" : " 覆盖 " + overrides);
        }
    }

    /**
     * 为HTTP函数获取完整文件路径的辅助方法
     */
    private String getFullPathForHttp(String path) {
        if (path.startsWith("http://") || path.startsWith("https://") || path.startsWith("/")) {
            return path;
        }
        return new File(luaDir, path).getAbsolutePath();
    }
}
