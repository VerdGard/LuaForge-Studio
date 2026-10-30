package com.androlua;

import android.webkit.MimeTypeMap;

import com.androlua.util.AsyncTaskX;
import com.luajava.LuaException;
import com.luajava.LuaObject;
import com.luajava.LuaState;
import com.luajava.LuaString;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

public class Http {

    private static HashMap<String, String> sHeader;

    static {
        try {
            SSLContext sslcontext = null;
            sslcontext = SSLContext.getInstance("SSL");
            sslcontext.init(null, new TrustManager[]{new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {

                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {

                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            }}, new java.security.SecureRandom());

            HostnameVerifier ignoreHostnameVerifier = new HostnameVerifier() {
                public boolean verify(String s, SSLSession sslsession) {
                    //这块也不用有啥逻辑，确认结果是true就行
                    return true;
                }
            };
            HttpsURLConnection.setDefaultHostnameVerifier(ignoreHostnameVerifier);
            HttpsURLConnection.setDefaultSSLSocketFactory(sslcontext.getSocketFactory());
        } catch (NoSuchAlgorithmException | KeyManagementException e) {
            e.printStackTrace();
        }


    }

    public static void setHeader(HashMap<String, String> header) {
        sHeader = header;
    }

    public static void setUserAgent(String userAgent) {
        if (sHeader == null)
            sHeader = new HashMap<>();
        sHeader.put("User-Agent", userAgent);
    }

    public static void setReferer(String referer) {
        if (sHeader == null)
            sHeader = new HashMap<>();
        sHeader.put("Referer", referer);
    }

    public static void setCookie(String cookie) {
        if (sHeader == null)
            sHeader = new HashMap<>();
        sHeader.put("Cookie", cookie);
    }

   /* static {
        setUserAgent("Mozilla/5.0 (Linux; Android 8.0.0) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/63.0.3239.111 Mobile Safari/537.36 EdgA/41.0.0.1722");
    }*/

    public static HashMap<String, String> getHeader() {
        return sHeader;
    }

    public static HttpTask get(String url, LuaObject callback) {
        Http.HttpTask task = new HttpTask(url, "GET", null, null, null, callback);
        task.execute();
        return task;
    }

    public static HttpTask get(String url, HashMap<String, String> header, LuaObject callback) {
        Http.HttpTask task = new HttpTask(url, "GET", null, null, header, callback);
        task.execute();
        return task;
    }

    public static HttpTask get(String url, String cookie, HashMap<String, String> header, LuaObject callback) {
        Http.HttpTask task = cookie.matches("[\\w\\-\\.:]+") && Charset.isSupported(cookie) ? new HttpTask(url, "GET", null, cookie, header, callback) : new HttpTask(url, "GET", cookie, null, header, callback);
        task.execute();
        return task;
    }

    public static HttpTask get(String url, String cookie, LuaObject callback) {
        Http.HttpTask task = cookie.matches("[\\w\\-\\.:]+") && Charset.isSupported(cookie) ? new HttpTask(url, "GET", null, cookie, null, callback) : new HttpTask(url, "GET", cookie, null, null, callback);
        task.execute();
        return task;
    }

    public static HttpTask get(String url, String cookie, String charset, LuaObject callback) {
        Http.HttpTask task = new HttpTask(url, "GET", cookie, charset, null, callback);
        task.execute();
        return task;
    }

    public static HttpTask get(String url, String cookie, String charset, HashMap<String, String> header, LuaObject callback) {
        Http.HttpTask task = new HttpTask(url, "GET", cookie, charset, header, callback);
        task.execute();
        return task;
    }

    public static HttpTask download(String url, String data, LuaObject callback) {
        Http.HttpTask task = new HttpTask(url, "GET", null, null, null, callback);
        task.execute(data);
        return task;
    }

    public static HttpTask download(String url, String data, HashMap<String, String> header, LuaObject callback) {
        Http.HttpTask task = new HttpTask(url, "GET", null, null, header, callback);
        task.execute(data);
        return task;
    }

    public static HttpTask download(String url, String data, String cookie, LuaObject callback) {
        Http.HttpTask task = new HttpTask(url, "GET", cookie, null, null, callback);
        task.execute(data);
        return task;
    }

    public static HttpTask download(String url, String data, String cookie, HashMap<String, String> header, LuaObject callback) {
        Http.HttpTask task = new HttpTask(url, "GET", cookie, null, header, callback);
        task.execute(data);
        return task;
    }


    public static HttpTask delete(String url, LuaObject callback) {
        Http.HttpTask task = new HttpTask(url, "DELETE", null, null, null, callback);
        task.execute();
        return task;
    }

    public static HttpTask delete(String url, HashMap<String, String> header, LuaObject callback) {
        Http.HttpTask task = new HttpTask(url, "DELETE", null, null, header, callback);
        task.execute();
        return task;
    }

    public static HttpTask delete(String url, String cookie, HashMap<String, String> header, LuaObject callback) {
        Http.HttpTask task = cookie.matches("[\\w\\-\\.:]+") && Charset.isSupported(cookie) ? new HttpTask(url, "DELETE", null, cookie, header, callback) : new HttpTask(url, "DELETE", cookie, null, header, callback);
        task.execute();
        return task;
    }

    public static HttpTask delete(String url, String cookie, LuaObject callback) {
        Http.HttpTask task = cookie.matches("[\\w\\-\\.:]+") && Charset.isSupported(cookie) ? new HttpTask(url, "DELETE", null, cookie, null, callback) : new HttpTask(url, "DELETE", cookie, null, null, callback);
        task.execute();
        return task;
    }

    public static HttpTask delete(String url, String cookie, String charset, LuaObject callback) {
        Http.HttpTask task = new HttpTask(url, "DELETE", cookie, charset, null, callback);
        task.execute();
        return task;
    }

    public static HttpTask delete(String url, String cookie, String charset, HashMap<String, String> header, LuaObject callback) {
        Http.HttpTask task = new HttpTask(url, "DELETE", cookie, charset, header, callback);
        task.execute();
        return task;
    }


    public static HttpTask post(String url, String data, LuaObject callback) {
        Http.HttpTask task = new HttpTask(url, "POST", null, null, null, callback);
        task.execute(data);
        return task;
    }

    public static HttpTask post(String url, String data, HashMap<String, String> header, LuaObject callback) {
        Http.HttpTask task = new HttpTask(url, "POST", null, null, header, callback);
        task.execute(data);
        return task;
    }

    public static HttpTask post(String url, String data, String cookie, LuaObject callback) {
        Http.HttpTask task = cookie.matches("[\\w\\-.:]+") && Charset.isSupported(cookie) ? new HttpTask(url, "POST", null, cookie, null, callback) : new HttpTask(url, "POST", cookie, null, null, callback);
        task.execute(data);
        return task;
    }

    public static HttpTask post(String url, String data, String cookie, HashMap<String, String> header, LuaObject callback) {
        Http.HttpTask task = cookie.matches("[\\w\\-.:]+") && Charset.isSupported(cookie) ? new HttpTask(url, "POST", null, cookie, header, callback) : new HttpTask(url, "POST", cookie, null, header, callback);
        task.execute(data);
        return task;
    }

    public static HttpTask post(String url, String data, String cookie, String charset, LuaObject callback) {
        Http.HttpTask task = new HttpTask(url, "POST", cookie, charset, null, callback);
        task.execute(data);
        return task;
    }

    public static HttpTask post(String url, String data, String cookie, String charset, HashMap<String, String> header, LuaObject callback) {
        Http.HttpTask task = new HttpTask(url, "POST", cookie, charset, header, callback);
        task.execute(data);
        return task;
    }

    public static HttpTask post(String url, HashMap<String, String> data, LuaObject callback) {
        return post(url, formatMap(data), callback);
    }

    public static HttpTask post(String url, HashMap<String, String> data, String cookie, LuaObject callback) {
        return post(url, formatMap(data), cookie, callback);
    }

    public static HttpTask post(String url, HashMap<String, String> data, String cookie, HashMap<String, String> header, LuaObject callback) {
        return post(url, formatMap(data), cookie, header, callback);
    }

    public static HttpTask post(String url, HashMap<String, String> data, String cookie, String charset, LuaObject callback) {
        return post(url, formatMap(data), cookie, charset, callback);
    }

    public static HttpTask post(String url, HashMap<String, String> data, String cookie, String charset, HashMap<String, String> header, LuaObject callback) {
        return post(url, formatMap(data), cookie, charset, header, callback);
    }

    private static String formatMap(HashMap<String, String> data) {
        StringBuilder buf = new StringBuilder();
        for (Map.Entry<String, String> entry : data.entrySet()) {
            buf.append(entry.getKey()).append("=").append(entry.getValue()).append("&");
        }
        if (!data.isEmpty())
            buf.deleteCharAt(buf.length() - 1);
        return buf.toString();
    }


    private final static String boundary = "----q1w2e3r4t5y6u7i8o9p0a1s2d3f4g5h6j7k8l9z0x1c2v3b4n5m6";

    public static HttpTask post(String url, HashMap<String, String> data, HashMap<String, String> file, LuaObject callback) {
        return post(url, data, file, null, null, null, callback);
    }

    public static HttpTask post(String url, HashMap<String, String> data, HashMap<String, String> file, String cookie, LuaObject callback) {
        return post(url, data, file, cookie, new HashMap<String, String>(), callback);
    }

    public static HttpTask post(String url, HashMap<String, String> data, HashMap<String, String> file, HashMap<String, String> header, LuaObject callback) {
        return post(url, data, file, null, header, callback);
    }

    public static HttpTask post(String url, HashMap<String, String> data, HashMap<String, String> file, String cookie, HashMap<String, String> header, LuaObject callback) {
        return cookie.matches("[\\w\\-.:]+") && Charset.isSupported(cookie) ? post(url, data, file, cookie, null, header, callback) : post(url, data, file, null, cookie, header, callback);
    }

    public static HttpTask post(String url, HashMap<String, String> data, HashMap<String, String> file, String cookie, String charset, LuaObject callback) {
        return post(url, data, file, cookie, charset, null, callback);
    }

    public static HttpTask post(String url, HashMap<String, String> data, HashMap<String, String> file, String cookie, String charset, HashMap<String, String> header, LuaObject callback) {
        if (header == null)
            header = new HashMap<>();
        header.put("Content-Type", "multipart/form-data;boundary=" + boundary);
        Http.HttpTask task = new HttpTask(url, "POST", cookie, charset, header, callback);
        task.execute(new Object[]{formatMultiDate(data, file, charset)});
        return task;
    }

    private static String getType(String file) {
        int lastDot = file.lastIndexOf(46);
        if (lastDot >= 0) {
            String extension = file.substring(lastDot + 1);
            String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
            if (mime != null) {
                return mime;
            }
        }
        return "application/octet-stream";
    }

    private static byte[] formatMultiDate(HashMap<String, String> data, HashMap<String, String> file, String charset) {
        if (charset == null)
            charset = "UTF-8";
        ByteArrayOutputStream buff = new ByteArrayOutputStream();
        for (Map.Entry<String, String> entry : data.entrySet()) {
            try {
                buff.write(String.format("--%s\r\nContent-Disposition:form-data;name=\"%s\"\r\n\r\n%s\r\n", boundary, entry.getKey(), entry.getValue()).getBytes(charset));
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        for (Map.Entry<String, String> entry : file.entrySet()) {
            try {
                buff.write(String.format("--%s\r\nContent-Disposition:form-data;name=\"%s\";filename=\"%s\"\r\nContent-Type:%s\r\n\r\n", boundary, entry.getKey(), entry.getValue(), getType(entry.getValue())).getBytes(charset));
                buff.write(LuaUtil.readAll(new FileInputStream(entry.getValue())));
                buff.write("\r\n".getBytes(charset));
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        try {
            buff.write(String.format("--%s--\r\n", boundary).getBytes(charset));
        } catch (IOException e) {
            e.printStackTrace();
        }

        return buff.toByteArray();
    }


    public static HttpTask put(String url, String data, LuaObject callback) {
        Http.HttpTask task = new HttpTask(url, "PUT", null, null, null, callback);
        task.execute(data);
        return task;
    }

    public static HttpTask put(String url, String data, HashMap<String, String> header, LuaObject callback) {
        Http.HttpTask task = new HttpTask(url, "PUT", null, null, header, callback);
        task.execute(data);
        return task;
    }

    public static HttpTask put(String url, String data, String cookie, LuaObject callback) {
        Http.HttpTask task = cookie.matches("[\\w\\-\\.:]+") && Charset.isSupported(cookie) ? new HttpTask(url, "PUT", null, cookie, null, callback) : new HttpTask(url, "PUT", cookie, null, null, callback);
        task.execute(data);
        return task;
    }

    public static HttpTask put(String url, String data, String cookie, HashMap<String, String> header, LuaObject callback) {
        Http.HttpTask task = cookie.matches("[\\w\\-\\.:]+") && Charset.isSupported(cookie) ? new HttpTask(url, "PUT", null, cookie, header, callback) : new HttpTask(url, "PUT", cookie, null, header, callback);
        task.execute(data);
        return task;
    }

    public static HttpTask put(String url, String data, String cookie, String charset, LuaObject callback) {
        Http.HttpTask task = new HttpTask(url, "PUT", cookie, charset, null, callback);
        task.execute(data);
        return task;
    }

    public static HttpTask put(String url, String data, String cookie, String charset, HashMap<String, String> header, LuaObject callback) {
        Http.HttpTask task = new HttpTask(url, "PUT", cookie, charset, header, callback);
        task.execute(data);
        return task;
    }


    public static class HttpTask extends AsyncTaskX<Object, Object, Object> {

        private final String mUrl;

        private final LuaObject mCallback;

        private byte[] mData;

        /** 请求体预览上限(16KB),只用于展示,不影响真实发送内容。 */
        private static final int MAX_BODY_PREVIEW = 16 * 1024;

        private String mCharset;

        private String mOutCharset;

        private final String mCookie;

        private final HashMap<String, String> mHeader;

        private final String mMethod;


        public HttpTask(String url, String method, String cookie, String charset, HashMap<String, String> header, LuaObject callback) {
            mUrl = url;
            mMethod = method;
            mCookie = cookie;
            mCharset = charset;
            mOutCharset = charset;
            mHeader = header;
            mCallback = callback;
        }


        @Override
        protected Object doInBackground(Object[] p1) {
            // TODO: Implement this method
            try {
                URL url = new URL(mUrl);

                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(30000);
                HttpURLConnection.setFollowRedirects(true);
                conn.setDoInput(true);
                conn.setRequestProperty("Accept-Language", "zh-cn,zh;q=0.5");

                if (mCharset == null)
                    mCharset = "UTF-8";
                conn.setRequestProperty("Accept-Charset", mCharset);


                if (sHeader != null) {
                    Set<Map.Entry<String, String>> entries = sHeader.entrySet();
                    for (Map.Entry<String, String> entry : entries) {
                        conn.setRequestProperty(entry.getKey(), entry.getValue());
                    }
                }

                if (mHeader != null) {
                    Set<Map.Entry<String, String>> entries = mHeader.entrySet();
                    for (Map.Entry<String, String> entry : entries) {
                        conn.setRequestProperty(entry.getKey(), entry.getValue());
                    }
                }

                if (mCookie != null)
                    conn.setRequestProperty("Cookie", mCookie);

                if (mMethod != null)
                    conn.setRequestMethod(mMethod);

                if (!"GET".equals(mMethod) && p1.length != 0) {
                    mData = formatData(p1);

                    conn.setDoOutput(true);
                    conn.setRequestProperty("Content-length", "" + mData.length);
                }

                // 仅截取前 MAX_BODY_PREVIEW 字节做预览,避免大请求体白拷贝一份
                String bodyPreview = null;
                if (mData != null && mData.length > 0) {
                    try {
                        int previewLen = Math.min(mData.length, MAX_BODY_PREVIEW);
                        bodyPreview = new String(mData, 0, previewLen,
                                mCharset == null ? "UTF-8" : mCharset);
                        if (mData.length > previewLen)
                            bodyPreview = bodyPreview + "\n... (已截断,共 " + mData.length + " 字节)";
                    } catch (Exception ignored) {
                    }
                }
                if (!com.luaforge.studio.utils.NetworkGate.allow(
                        mUrl, mMethod, mHeader, bodyPreview,
                        conn.getRequestProperty("Content-Type"), "http")) {
                    return new Object[]{-1, "网络请求已被用户拒绝: " + mUrl};
                }

                conn.connect();

                //download
                if ("GET".equals(mMethod) && p1.length != 0) {
                    File f = new File((String) p1[0]);
                    if (!f.getParentFile().exists())
                        //noinspection ResultOfMethodCallIgnored
                        f.getParentFile().mkdirs();
                    FileOutputStream os = new FileOutputStream(f);
                    InputStream is = conn.getInputStream();
                    LuaUtil.copyFile(is, os);
                    return new Object[]{conn.getResponseCode(), p1[0], conn.getHeaderFields()};
                }

                //post upload
                if (p1.length != 0) {
                    OutputStream os = conn.getOutputStream();
                    os.write(mData);
                }

                int code = conn.getResponseCode();
                Map<String, List<String>> hs = conn.getHeaderFields();
                String encoding = conn.getContentEncoding();
                List<String> cs = hs.get("Set-Cookie");
                StringBuilder cok = new StringBuilder();
                if (cs != null) {
                    for (String s : cs) {
                        cok.append(s).append(";");
                    }
                }

                List<String> ct = hs.get("Content-Type");
                if (ct != null) {
                    for (String s : ct) {
                        int idx = s.indexOf("charset");
                        if (idx != -1) {
                            idx = s.indexOf("=", idx);
                            if (idx != -1) {
                                int idx2 = s.indexOf(";", idx);
                                if (idx2 == -1)
                                    idx2 = s.length();
                                mCharset = s.substring(idx + 1, idx2);
                                mOutCharset = mCharset;
                                break;
                            }
                        }
                    }
                }

                if (mOutCharset == null) {
                    try {
                        InputStream is = conn.getInputStream();
                        byte[] reader = LuaUtil.readAll(is);
                        is.close();
                        LuaString buf = new LuaString(reader);
                        return new Object[]{code, buf, cok.toString(), hs};
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
                StringBuilder buf = new StringBuilder();
                try {
                    InputStream is = conn.getInputStream();
                    BufferedReader reader = new BufferedReader(new InputStreamReader(is, mCharset));
                    String line = reader.readLine();
                    if (line != null)
                        buf.append(line);
                    while ((line = reader.readLine()) != null && !isCancelled())
                        buf.append('\n').append(line);
                    is.close();
                } catch (Exception e) {
                    e.printStackTrace();
                }
                InputStream is = conn.getErrorStream();
                if (is != null) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(is, mCharset));
                    String line = reader.readLine();
                    if (line != null)
                        buf.append(line);
                    while ((line = reader.readLine()) != null && !isCancelled())
                        buf.append('\n').append(line);
                    is.close();
                }
                return new Object[]{code, new String(buf), cok.toString(), hs};
            } catch (Exception e) {
                e.printStackTrace();
                return new Object[]{-1, e.getMessage()};
            }

        }

        private byte[] formatData(Object[] p1) throws IOException {
            // TODO: Implement this method
            byte[] bs = null;
            if (p1.length == 1) {
                Object obj = p1[0];
                if (obj instanceof String)
                    bs = ((String) obj).getBytes(mCharset);
                else if (obj.getClass().getComponentType() == byte.class)
                    bs = (byte[]) obj;
                else if (obj instanceof File)
                    bs = LuaUtil.readAll(new FileInputStream((File) obj));
                else if (obj instanceof Map)
                    bs = formatData((Map<String, String>) obj);
            }
            return bs;
        }

        private byte[] formatData(Map<String, String> obj) throws UnsupportedEncodingException {
            // TODO: Implement this method
            StringBuilder buf = new StringBuilder();
            Set<Map.Entry<String, String>> entries = obj.entrySet();
            for (Map.Entry<String, String> entry : entries) {
                buf.append(entry.getKey()).append("=").append(entry.getValue()).append("&");
            }
            return buf.toString().getBytes(mCharset);
        }


        public boolean cancel() {
            // TODO: Implement this method
            return super.cancel(true);
        }


        @Override
        protected void onPostExecute(Object result) {
            // TODO: Implement this method
            if (isCancelled())
                return;

            // onPostExecute 在**主线程**执行(AsyncTaskX.InternalHandler -> finish),
            // 而发起请求的 Lua 会话可能在网络往返期间就已经被销毁并 close()
            // 了它的 LuaState。此时 mCallback 所属的 state 已不可用,任何 native
            // 调用都会抛 IllegalStateException("LuaState already closed") ——
            // 崩在这里等于把主线程打掉,进程直接消失。
            if (mCallback == null) {
                return;
            }
            if (mCallback.isClosed()) {
                android.util.Log.i("lua", "Http: 请求完成但所属 LuaState 已关闭,丢弃回调: " + mUrl);
                return;
            }

            boolean failed = false;
            String errMsg = null;
            try {
                mCallback.call((Object[]) result);
            } catch (LuaException e) {
                failed = true;
                errMsg = describe(e);
                android.util.Log.i("lua", "Http 回调出错: " + errMsg);
            } catch (IllegalStateException e) {
                // 判活与实际调用之间 state 被关闭
                android.util.Log.i("lua", "Http: 回调期间 LuaState 被关闭,丢弃: " + mUrl);
                return;
            } catch (Throwable t) {
                // onPostExecute 位于主线程消息派发路径上,任何 Throwable 逃出去
                // 都会沿 Handler.dispatchMessage 终结进程。
                failed = true;
                errMsg = describe(t);
                android.util.Log.i("lua", "Http 回调异常: " + errMsg);
            }

            if (failed) {
                logErrorToLua(errMsg);
            }
        }

        /**
         * 把回调错误转成字符串,自身绝不抛。
         */
        private String describe(Throwable t) {
            try {
                String m = t.getMessage();
                return m != null ? m : t.toString();
            } catch (Throwable ignored) {
                return t.getClass().getName();
            }
        }

        /**
         * 尽力把错误写进 Lua 的 print。
         *
         * <p>这里原本是 {@code mCallback.getLuaState().getLuaObject("print").call(...)}:
         * <ul>
         *   <li>{@code getLuaObject} 的第一步就是 {@code pushGlobalTable()}
         *       —— 恰好就是崩溃堆栈里的那一帧;state 已关闭时它抛
         *       {@link IllegalStateException},而外层只 catch 了 {@link LuaException},
         *       于是异常直接逃出 onPostExecute 打掉主线程;</li>
         *   <li>即使 state 还活着,脚本没定义 print 时 {@code getLuaObject} 会得到
         *       nil,对 nil 调 {@code call()} 抛的是 LuaException,同样会漏出去。</li>
         * </ul>
         * 现在:先判活,整个上报过程可失败、失败即静默。
         */
        private void logErrorToLua(String msg) {
            try {
                LuaState L = mCallback.getLuaState();
                if (L == null || L.isClosed()) {
                    return;
                }
                LuaObject print = L.getLuaObject("print");
                if (print != null && !print.isNil() && !print.isClosed()) {
                    print.call(msg);
                }
            } catch (Throwable ignored) {
                // 上报失败不影响主线程
            }
        }

    }
}
