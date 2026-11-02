package com.amarisgo.flashcards;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.webkit.ConsoleMessage;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

/**
 * 闪卡应用的主界面：一个全屏 WebView，加载打包进 APK 的 index.html。
 *
 * <p>关键点：
 * <ul>
 *   <li>页面通过虚拟 https 源 {@code https://flashcards.local/} 加载，
 *       以获得正常的同源环境（localStorage 是应用唯一的存储手段）。</li>
 *   <li>系统栏（状态栏 / 导航栏）区域用窗口背景色填充，内容按系统栏内边距避让。</li>
 *   <li>返回键先交给页面关闭浮层（弹窗 / 星标抽屉 / 卡组菜单），没有浮层可关时才退出。</li>
 * </ul>
 */
public class MainActivity extends Activity {

    private static final int REQ_FILE_CHOOSER = 1001;

    private WebView web;
    private ValueCallback<Uri[]> filePathCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setBackgroundDrawable(new ColorDrawable(0xFFFAF8F5));

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFFFAF8F5);

        web = new WebView(this);
        web.setBackgroundColor(0xFFFAF8F5);
        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(root);

        setupWebView();
        applyInsets(root);

        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState);
        } else {
            web.loadUrl(AssetServer.ORIGIN + "/index.html");
        }
    }

    private void setupWebView() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        // localStorage 是应用唯一的存储手段，必须开启
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        // 页面走虚拟 https 源，不需要访问真实文件
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        // 字号由应用自己的「设置 → 字号」控制，避免系统缩放把布局撑破
        s.setTextZoom(100);
        s.setUseWideViewPort(false);
        s.setLoadWithOverviewMode(false);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        if (Build.VERSION.SDK_INT >= 29) {
            // 应用自己实现了深浅色主题，不能让 WebView 再做一次强制反色
            s.setForceDark(WebSettings.FORCE_DARK_OFF);
        }
        if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true);
        }

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return AssetServer.serve(getAssets(), request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (AssetServer.isAppOrigin(uri)) {
                    return false;
                }
                // 应用外的链接交给系统浏览器，不在 WebView 里打开
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                } catch (ActivityNotFoundException ignored) {
                    // 没有可用的浏览器时忽略
                }
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                }
                filePathCallback = callback;
                Intent intent = params.createIntent();
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                try {
                    startActivityForResult(Intent.createChooser(intent, "选择要导入的文件"),
                            REQ_FILE_CHOOSER);
                } catch (ActivityNotFoundException e) {
                    filePathCallback = null;
                    return false;
                }
                return true;
            }

            @Override
            public boolean onConsoleMessage(ConsoleMessage cm) {
                return super.onConsoleMessage(cm);
            }
        });

        web.addJavascriptInterface(new NativeBridge(this), "Android");
    }

    /**
     * 让内容避开状态栏与导航栏。
     *
     * <p>API 35 起系统强制 edge-to-edge，窗口会铺到系统栏下面，此时内边距非零；
     * 更早的系统版本窗口本身已经被系统内缩，内边距为 0。两种情况都能得到正确结果，
     * 不会出现重复留白。
     */
    private void applyInsets(final View root) {
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                if (Build.VERSION.SDK_INT >= 30) {
                    android.graphics.Insets bars = insets.getInsets(
                            WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                    v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                } else {
                    v.setPadding(
                            insets.getSystemWindowInsetLeft(),
                            insets.getSystemWindowInsetTop(),
                            insets.getSystemWindowInsetRight(),
                            insets.getSystemWindowInsetBottom());
                }
                return insets;
            }
        });
        root.requestApplyInsets();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_FILE_CHOOSER) {
            if (filePathCallback != null) {
                Uri[] result = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
                filePathCallback.onReceiveValue(result);
                filePathCallback = null;
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onBackPressed() {
        if (web == null) {
            super.onBackPressed();
            return;
        }
        // 先问页面有没有浮层要关（弹窗 → 星标抽屉 → 卡组菜单）
        web.evaluateJavascript(
                "(function(){try{return (window.Flashcards && window.Flashcards.back()) ? 1 : 0}"
                        + "catch(e){return 0}})()",
                new ValueCallback<String>() {
                    @Override
                    public void onReceiveValue(String value) {
                        if (value == null || !value.contains("1")) {
                            if (web.canGoBack()) {
                                web.goBack();
                            } else {
                                finish();
                            }
                        }
                    }
                });
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (web != null) {
            web.saveState(outState);
        }
    }

    @Override
    protected void onPause() {
        if (web != null) {
            web.onPause();
            web.pauseTimers();
        }
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) {
            web.resumeTimers();
            web.onResume();
        }
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            ViewGroup parent = (ViewGroup) web.getParent();
            if (parent != null) {
                parent.removeView(web);
            }
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
