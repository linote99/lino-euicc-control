package kano.euiccctl;

import javax.net.ssl.HttpsURLConnection;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.security.cert.X509Certificate;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * lpac 的 HttpInterface 实现（Java 侧）：把 lpac 与 SM-DP+ 的 HTTPS 交互
 * 交给 HttpsURLConnection 完成。
 *
 * 对应 Kotlin 接口（libs/lpac-jni/.../HttpInterface.kt）：
 *   data class HttpResponse(val rcode: Int, val data: ByteArray)
 *   fun transmit(url: String, tx: ByteArray, headers: Array<String>): HttpResponse
 *
 * 说明：lpac 的 C 库负责 SGP.22 的 ES9+ 协议逻辑（含 JSON 组装与解析、
 * 证书校验），本类只做"把字节 POST 出去、把响应字节拿回来"这一层。
 */
class LpacHttp {

    /** HTTP 调用记录（诊断用）：便于确认 lpac 是否真的发起了 ES9+ 请求 */
    static final java.util.List<String> CALLS = java.util.Collections.synchronizedList(new java.util.ArrayList<String>());


    /** 创建实现 HttpInterface 的代理对象 */
    Object newProxy(final Class<?> httpResponseClass, final ClassLoader lpacLoader) {
        return Proxy.newProxyInstance(
                lpacLoader,
                new Class<?>[]{ LpacApdu.findInterface("net.typeblog.lpac_jni.HttpInterface", lpacLoader) },
                new InvocationHandler() {
                    public Object invoke(Object proxy, Method m, Object[] args) throws Throwable {
                        String n = m.getName();
                        if ("transmit".equals(n)) {
                            return transmit(httpResponseClass,
                                    (String) args[0], (byte[]) args[1], (Object[]) args[2]);
                        }
                        if ("toString".equals(n)) return "LpacHttpProxy";
                        if ("hashCode".equals(n)) return System.identityHashCode(proxy);
                        if ("equals".equals(n))   return proxy == args[0];
                        throw new UnsupportedOperationException("HttpInterface." + n);
                    }
                });
    }

    private Object transmit(Class<?> respClass, String url, byte[] tx, Object[] headers) throws Exception {
        int txLen = tx == null ? 0 : tx.length;
        CALLS.add("URL=" + url + " TX=" + txLen);
        if (headers != null) {
            StringBuilder hb0 = new StringBuilder("  HDRS[" + headers.length + "] ");
            for (Object h : headers) hb0.append(String.valueOf(h)).append(" | ");
            CALLS.add(hb0.toString());
        }
        if (tx != null && tx.length > 0) {
            CALLS.add("  TXBODY=" + new String(tx, "UTF-8").replace('\n', ' '));
        }

        // 用 SSLSocket 手写 HTTP/1.1 请求，而不是 HttpsURLConnection。
        //
        // 原因（实测对比，同一份 175 字节请求体）：
        //   HttpsURLConnection + 自定义 SSLSocketFactory -> HTTP 500（空响应）
        //   SSL 层绕过后（设备自带 curl 或 SSLSocket 手写） -> HTTP 200 + 正常 ES9+ JSON
        //   Java 层与 curl 的唯一差别集中在 TLS 协商细节（HttpsURLConnection 的
        //   SSLSocketFactory 可能未发送 SNI 或协商出服务器不接受的套件），
        //   手写 socket 可完全控制 SNI 与 HTTP 报文，行为与 curl 一致。
        javax.net.ssl.SSLSocket sock = null;
        try {
            java.net.URI uri = new java.net.URI(url);
            String host = uri.getHost();
            int port = uri.getPort() > 0 ? uri.getPort() : 443;
            String path = uri.getRawPath();
            if (uri.getRawQuery() != null) path += "?" + uri.getRawQuery();

            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, GsmaTrust.trustManagers(), new java.security.SecureRandom());
            javax.net.ssl.SSLSocketFactory sf = sc.getSocketFactory();
            sock = (javax.net.ssl.SSLSocket) sf.createSocket();
            sock.connect(new java.net.InetSocketAddress(host, port), 30000);
            // 显式启用 SNI 与主机名校验（curl 默认行为）
            try {
                javax.net.ssl.SNIHostName sni = new javax.net.ssl.SNIHostName(host);
                javax.net.ssl.SSLParameters p = sock.getSSLParameters();
                p.setServerNames(java.util.Collections.singletonList(sni));
                p.setEndpointIdentificationAlgorithm("HTTPS");
                sock.setSSLParameters(p);
            } catch (Throwable ignored) {}
            sock.startHandshake();

            StringBuilder req = new StringBuilder();
            req.append("POST ").append(path.isEmpty() ? "/" : path).append(" HTTP/1.1\r\n");
            req.append("Host: ").append(host).append("\r\n");
            req.append("User-Agent: gsma-rsp-lpad\r\n");
            req.append("X-Admin-Protocol: gsma/rsp/v2.2.0\r\n");
            req.append("Content-Type: application/json\r\n");
            req.append("Accept: application/json\r\n");
            req.append("Accept-Encoding: identity\r\n");
            if (headers != null) {
                for (int i = 0; i + 1 < headers.length; i += 2) {
                    String k = String.valueOf(headers[i]);
                    // 上面已固定的头不重复发
                    if (k.equalsIgnoreCase("User-Agent") || k.equalsIgnoreCase("X-Admin-Protocol")
                            || k.equalsIgnoreCase("Content-Type") || k.equalsIgnoreCase("Accept")
                            || k.equalsIgnoreCase("Accept-Encoding") || k.equalsIgnoreCase("Host")
                            || k.equalsIgnoreCase("Content-Length")) continue;
                    req.append(k).append(": ").append(String.valueOf(headers[i + 1])).append("\r\n");
                }
            }
            req.append("Content-Length: ").append(txLen).append("\r\n");
            req.append("Connection: close\r\n\r\n");

            java.io.OutputStream os = sock.getOutputStream();
            os.write(req.toString().getBytes("ISO-8859-1"));
            if (txLen > 0) os.write(tx);
            os.flush();

            java.io.InputStream in = sock.getInputStream();
            java.io.ByteArrayOutputStream raw = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) raw.write(buf, 0, n);
            byte[] all = raw.toByteArray();

            // 解析状态行与 body
            int hdrEnd = indexOf(all, new byte[]{13, 10, 13, 10}, 0);
            if (hdrEnd < 0) {
                CALLS.add("  -> MALFORMED 响应 " + all.length + " 字节");
                return buildResponse(respClass, 0, new byte[0]);
            }
            String headStr = new String(all, 0, hdrEnd, "ISO-8859-1");
            int code = 0;
            try {
                String[] parts = headStr.split("\r\n")[0].split(" ");
                if (parts.length >= 2) code = Integer.parseInt(parts[1]);
            } catch (Throwable ignored) {}

            byte[] body = java.util.Arrays.copyOfRange(all, hdrEnd + 4, all.length);
            // 若服务器用 chunked，做一次简易解码
            if (headStr.toLowerCase().contains("transfer-encoding: chunked")) {
                body = dechunk(body);
            }
            CALLS.add("  -> HTTP " + code + " RX=" + body.length);
            if (body.length > 0 && body.length < 400) {
                CALLS.add("  RESP=" + new String(body, "UTF-8").replace('\n', ' '));
            }
            return buildResponse(respClass, code, body);
        } catch (Throwable e) {
            CALLS.add("  -> NETFAIL " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return buildResponse(respClass, 0, new byte[0]);
        } finally {
            if (sock != null) try { sock.close(); } catch (Throwable ignored) {}
        }
    }

    private static int indexOf(byte[] hay, byte[] needle, int from) {
        outer:
        for (int i = from; i + needle.length <= hay.length; i++) {
            for (int j = 0; j < needle.length; j++) if (hay[i + j] != needle[j]) continue outer;
            return i;
        }
        return -1;
    }

    /** 简易 chunked 解码 */
    private static byte[] dechunk(byte[] b) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int i = 0;
        while (i < b.length) {
            int eol = indexOf(b, new byte[]{13, 10}, i);
            if (eol < 0) break;
            String sz = new String(b, i, eol - i, "ISO-8859-1").trim();
            int semi = sz.indexOf(';');
            if (semi > 0) sz = sz.substring(0, semi);
            int len;
            try { len = Integer.parseInt(sz, 16); } catch (Throwable t) { break; }
            if (len == 0) break;
            int st = eol + 2;
            if (st + len > b.length) len = b.length - st;
            out.write(b, st, len);
            i = st + len + 2;
        }
        return out.toByteArray();
    }

    private static Object buildResponse(Class<?> cls, int code, byte[] data) throws Exception {
        Constructor<?> ctor = cls.getConstructor(int.class, byte[].class);
        return ctor.newInstance(code, data);
    }

    private static byte[] readAll(InputStream in) throws Exception {
        if (in == null) return new byte[0];
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toByteArray();
    }
}
