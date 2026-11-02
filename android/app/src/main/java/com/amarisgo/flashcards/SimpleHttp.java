package com.amarisgo.flashcards;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * 极简 HTTP/1.1 客户端。
 *
 * <p>为什么不直接用 HttpURLConnection：
 * <ul>
 *   <li>{@code HttpURLConnection.setRequestMethod()} 只允许 GET/POST/HEAD/OPTIONS/PUT/DELETE/TRACE，
 *       而 WebDAV 常用 {@code PROPFIND}，用它会抛 ProtocolException。</li>
 *   <li>在 WebView 里，跨域请求会被 CORS 拦下（DAV 服务器通常不带 CORS 头）。
 *       改由原生发起请求就没有同源策略问题。</li>
 * </ul>
 *
 * <p>因此这里直接用 Socket 手写 HTTP/1.1，请求方法不受限制。
 * 支持 Content-Length / chunked / 直读到 EOF 三种响应体形式，以及最多 5 次重定向。
 */
final class SimpleHttp {

    /** 单次请求的响应。 */
    static final class Resp {
        int status;
        String body = "";
        final Map<String, String> headers = new LinkedHashMap<>();
    }

    private SimpleHttp() {
    }

    static Resp request(String method, String urlStr, Map<String, String> headers, String body)
            throws IOException {
        URL url = new URL(urlStr);
        int hops = 0;
        while (true) {
            Resp r = once(method, url, headers, body);
            int st = r.status;
            boolean redirect = (st == 301 || st == 302 || st == 303 || st == 307 || st == 308);
            if (redirect && hops++ < 5) {
                String loc = r.headers.get("location");
                if (loc == null || loc.isEmpty()) {
                    return r;
                }
                URL next = new URL(url, loc);
                // 303 以及非 GET 的 301/302，按浏览器惯例降级为 GET
                if (st == 303 || ((st == 301 || st == 302)
                        && !"GET".equals(method) && !"HEAD".equals(method))) {
                    method = "GET";
                    body = null;
                }
                url = next;
                continue;
            }
            return r;
        }
    }

    private static Resp once(String method, URL url, Map<String, String> headers, String body)
            throws IOException {
        boolean https = "https".equalsIgnoreCase(url.getProtocol());
        int port = url.getPort() > 0 ? url.getPort() : (https ? 443 : 80);

        Socket sock;
        if (https) {
            SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
            sock = factory.createSocket(url.getHost(), port);
            ((SSLSocket) sock).startHandshake();
        } else {
            sock = new Socket(url.getHost(), port);
        }

        try {
            sock.setSoTimeout(30000);
            OutputStream os = sock.getOutputStream();

            String path = url.getFile();
            if (path == null || path.isEmpty()) {
                path = "/";
            }

            StringBuilder sb = new StringBuilder();
            sb.append(method).append(' ').append(path).append(" HTTP/1.1\r\n");
            sb.append("Host: ").append(url.getHost());
            if (port != (https ? 443 : 80)) {
                sb.append(':').append(port);
            }
            sb.append("\r\n");
            sb.append("User-Agent: Flashcards-Android\r\n");
            sb.append("Accept: */*\r\n");
            sb.append("Connection: close\r\n");
            // 故意不发 Accept-Encoding：避免服务端压缩响应，省掉解压逻辑
            if (body != null) {
                sb.append("Content-Length: ")
                        .append(body.getBytes(StandardCharsets.UTF_8).length).append("\r\n");
            }
            if (headers != null) {
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    String k = e.getKey();
                    String v = e.getValue();
                    if (k == null || v == null) {
                        continue;
                    }
                    // 这些头由本类统一管理，避免重复或被覆盖
                    if (k.equalsIgnoreCase("Host") || k.equalsIgnoreCase("Connection")
                            || k.equalsIgnoreCase("Content-Length") || k.equalsIgnoreCase("User-Agent")
                            || k.equalsIgnoreCase("Accept-Encoding")) {
                        continue;
                    }
                    sb.append(k).append(": ").append(sanitize(v)).append("\r\n");
                }
            }
            sb.append("\r\n");

            os.write(sb.toString().getBytes(StandardCharsets.ISO_8859_1));
            if (body != null) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
            os.flush();

            return readResponse(sock.getInputStream());
        } finally {
            try {
                sock.close();
            } catch (IOException ignored) {
                // 忽略关闭异常
            }
        }
    }

    /** 去掉头部值里的换行，防止请求头注入。 */
    private static String sanitize(String v) {
        return v.replace("\r", "").replace("\n", "");
    }

    private static Resp readResponse(InputStream raw) throws IOException {
        BufferedInputStream in = new BufferedInputStream(raw, 8192);

        String statusLine = readLine(in);
        if (statusLine == null || statusLine.isEmpty()) {
            throw new IOException("服务器没有返回状态行");
        }

        Resp resp = new Resp();
        int sp1 = statusLine.indexOf(' ');
        if (sp1 < 0) {
            throw new IOException("无法解析状态行: " + statusLine);
        }
        int sp2 = statusLine.indexOf(' ', sp1 + 1);
        String code = statusLine.substring(sp1 + 1, sp2 > 0 ? sp2 : statusLine.length()).trim();
        try {
            resp.status = Integer.parseInt(code);
        } catch (NumberFormatException e) {
            throw new IOException("无法解析状态码: " + code);
        }

        String transferEncoding = null;
        String contentLength = null;
        while (true) {
            String line = readLine(in);
            if (line == null || line.isEmpty()) {
                break;
            }
            int colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String key = line.substring(0, colon).trim().toLowerCase(Locale.US);
            String value = line.substring(colon + 1).trim();
            resp.headers.put(key, value);
            if (key.equals("transfer-encoding")) {
                transferEncoding = value.toLowerCase(Locale.US);
            } else if (key.equals("content-length")) {
                contentLength = value;
            }
        }

        byte[] body;
        if (contentLength != null) {
            int len;
            try {
                len = Integer.parseInt(contentLength.trim());
            } catch (NumberFormatException e) {
                len = -1;
            }
            body = len >= 0 ? readFully(in, len) : readAll(in);
        } else if (transferEncoding != null && transferEncoding.contains("chunked")) {
            body = readChunked(in);
        } else {
            body = readAll(in);
        }

        resp.body = new String(body, StandardCharsets.UTF_8);
        return resp;
    }

    /** 读一行（以 \r\n 或 \n 结尾），返回 null 表示流已结束。 */
    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(128);
        int c = in.read();
        while (c != -1) {
            if (c == '\n') {
                break;
            }
            if (c != '\r') {
                buf.write(c);
            }
            c = in.read();
        }
        if (c == -1 && buf.size() == 0) {
            return null;
        }
        return new String(buf.toByteArray(), StandardCharsets.ISO_8859_1);
    }

    private static byte[] readFully(InputStream in, int len) throws IOException {
        byte[] buf = new byte[Math.max(0, len)];
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) {
                break;
            }
            off += n;
        }
        return buf;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static byte[] readChunked(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (true) {
            String sizeLine = readLine(in);
            if (sizeLine == null) {
                break;
            }
            int semi = sizeLine.indexOf(';');
            if (semi >= 0) {
                sizeLine = sizeLine.substring(0, semi);
            }
            int size;
            try {
                size = Integer.parseInt(sizeLine.trim(), 16);
            } catch (NumberFormatException e) {
                break;
            }
            if (size == 0) {
                readLine(in);
                break;
            }
            out.write(readFully(in, size));
            readLine(in); // 丢掉 chunk 结尾的空行
        }
        return out.toByteArray();
    }
}
