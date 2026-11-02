package com.amarisgo.flashcards;

import android.content.ContentValues;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.View;
import android.view.WindowInsetsController;
import android.webkit.JavascriptInterface;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 暴露给页面的 {@code window.Android} 对象。
 *
 * <p>页面（index.html）本身已经预留了 {@code window.Android} 的用法，这里只是把它补齐：
 * <ul>
 *   <li>{@code setStatusBar(color, lightIcons)} —— 同步状态栏配色（应用自带深浅色主题）</li>
 *   <li>{@code saveFile(name, content, type)} —— 把导出的 JSON/TXT 写进系统「下载」目录</li>
 *   <li>{@code davRequest(method, url, headersJson, body)} —— 原生发起 WebDAV 请求，
 *       绕开 WebView 的跨域限制，并支持 {@code PROPFIND} 这类 HttpURLConnection 不允许的方法</li>
 * </ul>
 *
 * <p>这些方法都由 WebView 的 JS 桥线程调用，不在 UI 线程上，因此可以安全地做阻塞式网络 I/O；
 * 涉及 UI 的部分再切回主线程。
 */
final class NativeBridge {

    private final MainActivity activity;

    NativeBridge(MainActivity activity) {
        this.activity = activity;
    }

    /** 同步状态栏配色。页面在 applyTheme() 时调用。 */
    @JavascriptInterface
    public void setStatusBar(final String color, final boolean lightIcons) {
        final int c = parseColor(color, 0xFFFAF8F5);
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                // 窗口背景填成应用底色，状态栏区域（edge-to-edge 下是透明的）就能与应用无缝衔接
                activity.getWindow().setBackgroundDrawable(new ColorDrawable(c));
                activity.getWindow().setStatusBarColor(c);
                applyLightIcons(lightIcons);
            }
        });
    }

    private static int parseColor(String color, int fallback) {
        try {
            return Color.parseColor(color);
        } catch (Exception e) {
            return fallback;
        }
    }

    private void applyLightIcons(boolean lightIcons) {
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = activity.getWindow().getInsetsController();
            if (controller == null) {
                return;
            }
            int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            controller.setSystemBarsAppearance(lightIcons ? mask : 0, mask);
        } else {
            View decor = activity.getWindow().getDecorView();
            int flags = decor.getSystemUiVisibility();
            if (lightIcons) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            } else {
                flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            }
            decor.setSystemUiVisibility(flags);
        }
    }

    /**
     * 把内容写到系统「下载」目录。
     *
     * <p>Android 10（API 29）起走 MediaStore，无需任何存储权限；
     * 更早的版本写到应用专属目录，同样不需要权限。
     */
    @JavascriptInterface
    public void saveFile(String name, String content, String type) {
        try {
            byte[] data = (content == null ? "" : content).getBytes(StandardCharsets.UTF_8);
            String fileName = (name == null || name.trim().isEmpty()) ? "flashcards.json" : name.trim();

            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
                values.put(MediaStore.Downloads.MIME_TYPE,
                        (type == null || type.isEmpty()) ? "application/json" : type);
                values.put(MediaStore.Downloads.IS_PENDING, 1);
                Uri uri = activity.getContentResolver()
                        .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri == null) {
                    throw new IOException("无法创建下载文件");
                }
                OutputStream os = activity.getContentResolver().openOutputStream(uri);
                if (os == null) {
                    throw new IOException("无法写入下载文件");
                }
                try {
                    os.write(data);
                    os.flush();
                } finally {
                    os.close();
                }
                values.clear();
                values.put(MediaStore.Downloads.IS_PENDING, 0);
                activity.getContentResolver().update(uri, values, null, null);
            } else {
                File dir = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                if (dir == null) {
                    dir = activity.getFilesDir();
                }
                if (!dir.exists() && !dir.mkdirs()) {
                    throw new IOException("无法创建目录");
                }
                File out = new File(dir, fileName);
                OutputStream os = new FileOutputStream(out);
                try {
                    os.write(data);
                    os.flush();
                } finally {
                    os.close();
                }
            }
        } catch (final Exception e) {
            // 页面侧会提示「已保存」，失败时由原生补一条提示，避免用户以为存好了
            final String msg = "保存失败：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            activity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(activity, msg, Toast.LENGTH_LONG).show();
                }
            });
        }
    }

    /**
     * 原生发起 WebDAV 请求，返回 JSON 字符串：
     * <pre>{"status":207,"body":"..."}  成功
     * {"error":"..."}                 失败</pre>
     */
    @JavascriptInterface
    public String davRequest(String method, String url, String headersJson, String body) {
        JSONObject out = new JSONObject();
        try {
            Map<String, String> headers = new LinkedHashMap<>();
            if (headersJson != null && !headersJson.isEmpty()) {
                JSONObject parsed = new JSONObject(headersJson);
                for (Iterator<String> it = parsed.keys(); it.hasNext(); ) {
                    String key = it.next();
                    headers.put(key, parsed.optString(key, ""));
                }
            }
            String verb = (method == null || method.trim().isEmpty()) ? "GET" : method.trim();
            SimpleHttp.Resp resp = SimpleHttp.request(verb, url, headers, body);
            out.put("status", resp.status);
            out.put("body", resp.body == null ? "" : resp.body);
        } catch (Throwable e) {
            try {
                out.put("error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            } catch (Exception ignored) {
                return "{\"error\":\"unknown\"}";
            }
        }
        return out.toString();
    }
}
