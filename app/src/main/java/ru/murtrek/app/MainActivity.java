package ru.murtrek.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Message;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.Locale;

/** Тонкая оболочка: сайт MURTREK в WebView, скачивание файлов, окно оплаты, обновление приложения изнутри. */
public class MainActivity extends Activity {
    private static final int REQ_FILE = 71;
    private static final int REQ_STORAGE = 72;
    private static final long RECHECK_MS = 30 * 60 * 1000L;

    private FrameLayout root;
    private WebView web;
    private LinearLayout popupBox;
    private WebView popup;
    private ValueCallback<Uri[]> fileCb;
    private Updater updater;
    private long lastUpdateCheck = 0;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#0B1016"));
        setContentView(root);

        web = newWebView(false);
        root.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        web.addJavascriptInterface(new Bridge(), "MurtrekNative");
        if (state != null) web.restoreState(state);
        else web.loadUrl(BuildConfig.SITE_URL);

        updater = new Updater(this);
        if (Build.VERSION.SDK_INT <= 28 && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
        }
        web.postDelayed(() -> checkUpdates(false), 4000); // сначала даём сайту загрузиться
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (web != null) web.saveState(out);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.onResume();
        updater.resumeAfterPermission();
        if (System.currentTimeMillis() - lastUpdateCheck > RECHECK_MS) checkUpdates(false);
    }

    @Override
    protected void onPause() {
        if (web != null) web.onPause();
        CookieManager.getInstance().flush();
        super.onPause();
    }

    void checkUpdates(boolean manual) {
        lastUpdateCheck = System.currentTimeMillis();
        updater.check(manual);
    }

    private boolean ownHost(String host) {
        if (host == null) return false;
        host = host.toLowerCase(Locale.ROOT);
        return host.equals("murtrek.com") || host.equals("murtrek.ru") || host.endsWith(".murtrek.com") || host.endsWith(".murtrek.ru");
    }

    @SuppressLint("SetJavaScriptEnabled")
    private WebView newWebView(boolean isPopup) {
        WebView w = new WebView(this);
        w.setBackgroundColor(Color.parseColor("#0B1016"));
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setSupportMultipleWindows(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setUserAgentString(s.getUserAgentString() + " MurtrekApp/" + BuildConfig.VERSION_NAME);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true);

        w.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                String sch = u.getScheme() == null ? "" : u.getScheme();
                boolean http = sch.equals("http") || sch.equals("https");
                if (http && (isPopup || ownHost(u.getHost()))) return false; // окно оплаты ходит по банкам, основное окно только по нашим адресам
                if (sch.equals("about") || sch.equals("blob") || sch.equals("data") || sch.equals("javascript")) return false;
                openExternal(u);
                return true;
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                if (!isPopup && url != null && url.startsWith("https://") && ownHost(Uri.parse(url).getHost())) v.evaluateJavascript(DOWNLOAD_JS, null);
            }

            @Override
            public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                if (!isPopup && r.isForMainFrame()) showOffline(v);
            }
        });

        w.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (fileCb != null) fileCb.onReceiveValue(null);
                fileCb = cb;
                try {
                    startActivityForResult(params.createIntent(), REQ_FILE);
                } catch (ActivityNotFoundException e) {
                    fileCb = null;
                    toast("Не нашёл приложение для выбора файла");
                    return false;
                }
                return true;
            }

            @Override
            public boolean onCreateWindow(WebView v, boolean dialog, boolean gesture, Message msg) {
                closePopup();
                popup = newWebView(true);
                popupBox = new LinearLayout(MainActivity.this);
                popupBox.setOrientation(LinearLayout.VERTICAL);
                popupBox.setBackgroundColor(Color.parseColor("#0B1016"));
                TextView bar = new TextView(MainActivity.this);
                bar.setText("✕  Закрыть");
                bar.setTextColor(Color.WHITE);
                bar.setTypeface(Typeface.DEFAULT_BOLD);
                bar.setGravity(Gravity.CENTER_VERTICAL);
                bar.setPadding(40, 0, 40, 0);
                bar.setBackgroundColor(Color.parseColor("#1A2330"));
                bar.setOnClickListener(x -> closePopup());
                popupBox.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (48 * getResources().getDisplayMetrics().density)));
                popupBox.addView(popup, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
                root.addView(popupBox, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                WebView.WebViewTransport t = (WebView.WebViewTransport) msg.obj;
                t.setWebView(popup);
                msg.sendToTarget();
                return true;
            }

            @Override
            public void onCloseWindow(WebView v) {
                if (v == popup) closePopup();
            }
        });

        w.setDownloadListener((url, ua, cd, mime, len) -> download(url, ua, cd, mime));
        return w;
    }

    private void closePopup() {
        if (popupBox != null) {
            root.removeView(popupBox);
            popupBox = null;
        }
        if (popup != null) {
            popup.destroy();
            popup = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (popupBox != null) closePopup();
        else if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req == REQ_FILE) {
            if (fileCb != null) {
                fileCb.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res, data));
                fileCb = null;
            }
            return;
        }
        super.onActivityResult(req, res, data);
    }

    private void openExternal(Uri u) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, u));
        } catch (ActivityNotFoundException e) {
            toast("Нечем открыть ссылку");
        }
    }

    private void showOffline(WebView v) {
        String html = "<html><body style='margin:0;background:#0B1016;color:#f4f7fb;font-family:sans-serif;display:flex;align-items:center;justify-content:center;height:100vh;text-align:center'>"
                + "<div style='padding:24px'><div style='font-size:22px;font-weight:700;margin-bottom:10px'>Нет связи с сервером</div>"
                + "<div style='opacity:.7;margin-bottom:22px'>Проверьте интернет и попробуйте ещё раз</div>"
                + "<a href='" + BuildConfig.SITE_URL + "' style='display:inline-block;background:#ff5a1f;color:#1a0e08;font-weight:700;padding:14px 28px;border-radius:12px;text-decoration:none'>Повторить</a></div></body></html>";
        v.loadDataWithBaseURL(BuildConfig.SITE_URL, html, "text/html", "UTF-8", null);
    }

    // ---------- скачивание ----------
    private void download(String url, String ua, String cd, String mime) {
        if (url.startsWith("blob:") || url.startsWith("data:")) { // запасной путь: имя файла из ссылки не узнать
            String name = "murtrek-" + System.currentTimeMillis() + (mime != null && mime.contains("png") ? ".png" : mime != null && mime.contains("mp4") ? ".mp4" : "");
            String js = "fetch(" + jsStr(url) + ").then(r=>r.blob()).then(b=>{var f=new FileReader();f.onload=function(){var s=String(f.result);MurtrekNative.saveFile(" + jsStr(name) + ",b.type||'application/octet-stream',s.slice(s.indexOf(',')+1))};f.readAsDataURL(b)})";
            web.evaluateJavascript(js, null);
            return;
        }
        try {
            String name = URLUtil.guessFileName(url, cd, mime);
            DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
            String cookie = CookieManager.getInstance().getCookie(url);
            if (cookie != null) r.addRequestHeader("Cookie", cookie);
            if (ua != null) r.addRequestHeader("User-Agent", ua);
            if (mime != null && !mime.isEmpty()) r.setMimeType(mime);
            r.setTitle(name);
            r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
            ((DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE)).enqueue(r);
            toast("Скачиваю: " + name);
        } catch (Exception e) {
            toast("Не удалось скачать файл");
        }
    }

    private static String jsStr(String s) {
        return "'" + s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "") + "'";
    }

    private void saveBytes(String name, String mime, byte[] data) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Downloads.DISPLAY_NAME, name);
                cv.put(MediaStore.Downloads.MIME_TYPE, mime);
                cv.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                Uri u = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (u == null) throw new Exception("insert");
                try (OutputStream o = getContentResolver().openOutputStream(u)) {
                    o.write(data);
                }
            } else {
                File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                dir.mkdirs();
                try (FileOutputStream o = new FileOutputStream(new File(dir, name))) {
                    o.write(data);
                }
            }
            runOnUiThread(() -> toast("Сохранено в «Загрузки»: " + name));
        } catch (Exception e) {
            runOnUiThread(() -> toast("Не удалось сохранить файл"));
        }
    }

    private void toast(String t) {
        Toast.makeText(this, t, Toast.LENGTH_LONG).show();
    }

    /** То, что сайт может вызвать как MurtrekNative.*. Доступно только страницам нашего сайта. */
    private class Bridge {
        @JavascriptInterface
        public String version() {
            return BuildConfig.VERSION_NAME + "+" + BuildConfig.VERSION_CODE;
        }

        @JavascriptInterface
        public void checkUpdate() {
            runOnUiThread(() -> checkUpdates(true));
        }

        @JavascriptInterface
        public void saveFile(String name, String mime, String b64) {
            String safe = name == null || name.isEmpty() ? "murtrek" : name.replaceAll("[\\\\/:*?\"<>|]", "_");
            saveBytes(safe, mime == null || mime.isEmpty() ? "application/octet-stream" : mime, Base64.decode(b64, Base64.DEFAULT));
        }
    }

    /** Скачивание по ссылкам blob:/data: (их WebView сам не умеет): перехватываем нажатие и отдаём файл приложению. */
    private static final String DOWNLOAD_JS = "(function(){if(window.__mtNative)return;window.__mtNative=1;"
            + "document.addEventListener('click',function(e){var a=e.target.closest&&e.target.closest('a[download]');if(!a)return;var h=a.href||'';"
            + "if(h.indexOf('blob:')!==0&&h.indexOf('data:')!==0)return;e.preventDefault();e.stopPropagation();"
            + "var n=a.getAttribute('download')||'murtrek';"
            + "fetch(h).then(function(r){return r.blob()}).then(function(b){var f=new FileReader();f.onload=function(){var s=String(f.result);"
            + "MurtrekNative.saveFile(n,b.type||'application/octet-stream',s.slice(s.indexOf(',')+1))};f.readAsDataURL(b)})},true)})();";
}
