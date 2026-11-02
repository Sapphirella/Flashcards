package com.amarisgo.flashcards;

import android.content.res.AssetManager;
import android.net.Uri;
import android.webkit.WebResourceResponse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 把 APK 里的 assets 通过一个虚拟的 https 源提供给 WebView。
 *
 * <p>为什么不直接用 {@code file:///android_asset/index.html}：
 * file:// 是一个「不透明源」，各浏览器/WebView 对它的 localStorage 支持并不一致，
 * 而 localStorage 是本应用唯一的存储手段。改用虚拟的 https 源后，
 * 页面拥有一个正常、隔离、可持久化的同源环境，行为与网页版完全一致。
 *
 * <p>虚拟域名 {@code flashcards.local} 永远不会真的走 DNS —— 所有请求都在
 * {@link #serve(Uri)} 里被截获并直接从 assets 读取。
 */
final class AssetServer {

    static final String SCHEME = "https";
    static final String HOST = "flashcards.local";
    static final String ORIGIN = SCHEME + "://" + HOST;

    /** assets 里 web/ 目录的根。 */
    private static final String ASSET_ROOT = "web";

    private AssetServer() {
    }

    static boolean isAppOrigin(Uri uri) {
        return uri != null
                && SCHEME.equalsIgnoreCase(uri.getScheme())
                && HOST.equalsIgnoreCase(uri.getHost());
    }

    /**
     * @return 命中虚拟源时返回对应资源；不是本应用的源时返回 null，交给 WebView 自行处理。
     */
    static WebResourceResponse serve(AssetManager assets, Uri uri) {
        if (!isAppOrigin(uri)) {
            return null;
        }

        String path = uri.getPath();
        if (path == null || path.isEmpty() || "/".equals(path)) {
            path = "/index.html";
        }
        // 防止路径穿越
        if (path.contains("..") || path.contains("//")) {
            return notFound();
        }

        String assetPath = ASSET_ROOT + path;
        try {
            InputStream in = assets.open(assetPath);
            String mime = mimeOf(path);
            boolean textual = mime.startsWith("text/")
                    || mime.contains("javascript")
                    || mime.contains("json")
                    || mime.contains("svg");
            Map<String, String> headers = new HashMap<>();
            headers.put("Cache-Control", "no-cache");
            return new WebResourceResponse(mime, textual ? "utf-8" : null, 200, "OK", headers, in);
        } catch (IOException e) {
            return notFound();
        }
    }

    private static WebResourceResponse notFound() {
        return new WebResourceResponse(
                "text/plain", "utf-8", 404, "Not Found",
                Collections.<String, String>emptyMap(),
                new ByteArrayInputStream("Not found".getBytes(StandardCharsets.UTF_8)));
    }

    private static String mimeOf(String path) {
        String p = path.toLowerCase(Locale.US);
        if (p.endsWith(".html") || p.endsWith(".htm")) {
            return "text/html";
        }
        if (p.endsWith(".css")) {
            return "text/css";
        }
        if (p.endsWith(".js")) {
            return "text/javascript";
        }
        if (p.endsWith(".json")) {
            return "application/json";
        }
        if (p.endsWith(".woff2")) {
            return "font/woff2";
        }
        if (p.endsWith(".woff")) {
            return "font/woff";
        }
        if (p.endsWith(".ttf")) {
            return "font/ttf";
        }
        if (p.endsWith(".svg")) {
            return "image/svg+xml";
        }
        if (p.endsWith(".png")) {
            return "image/png";
        }
        if (p.endsWith(".jpg") || p.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (p.endsWith(".webp")) {
            return "image/webp";
        }
        if (p.endsWith(".ico")) {
            return "image/x-icon";
        }
        return "application/octet-stream";
    }
}
