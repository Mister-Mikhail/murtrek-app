package ru.murtrek.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

/**
 * Обновление изнутри приложения: ищет последнюю сборку в релизах GitHub (в описании MURTREK_VERSION_CODE и MURTREK_SHA256),
 * скачивает APK, проверяет контрольную сумму и открывает системную установку поверх текущей версии.
 */
final class Updater {
    private static final class Info {
        int code;
        String name = "";
        String url = "";
        String sha = "";
        String notes = "";
    }

    private final Activity act;
    private volatile boolean busy = false;
    private Info pending; // ждёт, пока человек разрешит установку из приложения

    Updater(Activity act) {
        this.act = act;
    }

    void check(boolean manual) {
        if (busy) return;
        busy = true;
        new Thread(() -> {
            Info info = null;
            boolean failed = false;
            try {
                info = fetch();
            } catch (Exception e) {
                failed = true;
            }
            final Info fin = info;
            final boolean bad = failed;
            act.runOnUiThread(() -> {
                busy = false;
                if (bad) {
                    if (manual) toast("Не удалось проверить обновления");
                } else if (fin != null && fin.code > BuildConfig.VERSION_CODE) {
                    ask(fin);
                } else if (manual) {
                    toast("Установлена последняя версия: " + BuildConfig.VERSION_NAME);
                }
            });
        }).start();
    }

    private Info fetch() throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(BuildConfig.UPDATE_API).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(10000);
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setRequestProperty("User-Agent", "MurtrekApp/" + BuildConfig.VERSION_NAME);
        if (c.getResponseCode() != 200) throw new Exception("http " + c.getResponseCode());
        StringBuilder sb = new StringBuilder();
        try (InputStream in = c.getInputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) sb.append(new String(buf, 0, n, "UTF-8"));
        }
        JSONObject j = new JSONObject(sb.toString());
        Info i = new Info();
        i.name = j.optString("tag_name", "");
        JSONArray assets = j.optJSONArray("assets");
        if (assets != null) {
            for (int k = 0; k < assets.length(); k++) {
                JSONObject a = assets.getJSONObject(k);
                if (a.optString("name").endsWith(".apk")) {
                    i.url = a.optString("browser_download_url");
                    break;
                }
            }
        }
        StringBuilder notes = new StringBuilder();
        for (String line : j.optString("body", "").split("\\r?\\n")) {
            if (line.startsWith("MURTREK_VERSION_CODE=")) {
                try { i.code = Integer.parseInt(line.substring(21).trim()); } catch (NumberFormatException ignored) { }
            } else if (line.startsWith("MURTREK_SHA256=")) {
                i.sha = line.substring(15).trim();
            } else if (!line.trim().isEmpty() && notes.length() < 500) {
                notes.append(line.trim()).append('\n');
            }
        }
        i.notes = notes.toString().trim();
        return i.url.isEmpty() ? null : i;
    }

    private void ask(Info info) {
        new AlertDialog.Builder(act)
                .setTitle("Доступна новая версия")
                .setMessage("Версия " + info.name + " (у вас " + BuildConfig.VERSION_NAME + ")." + (info.notes.isEmpty() ? "" : "\n\n" + info.notes))
                .setPositiveButton("Обновить", (d, w) -> start(info))
                .setNegativeButton("Позже", null)
                .show();
    }

    private void start(Info info) {
        if (Build.VERSION.SDK_INT >= 26 && !act.getPackageManager().canRequestPackageInstalls()) {
            pending = info;
            new AlertDialog.Builder(act)
                    .setTitle("Нужно разрешение")
                    .setMessage("Чтобы приложение могло обновляться само, разрешите установку из этого приложения. Включите переключатель и вернитесь назад.")
                    .setPositiveButton("Открыть настройки", (d, w) -> act.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + act.getPackageName()))))
                    .setNegativeButton("Отмена", (d, w) -> pending = null)
                    .show();
            return;
        }
        download(info);
    }

    /** Вызывается при возврате в приложение: если человек дал разрешение, продолжаем обновление. */
    void resumeAfterPermission() {
        if (pending != null && (Build.VERSION.SDK_INT < 26 || act.getPackageManager().canRequestPackageInstalls())) {
            Info p = pending;
            pending = null;
            download(p);
        }
    }

    @SuppressWarnings("deprecation")
    private void download(Info info) {
        ProgressDialog pd = new ProgressDialog(act);
        pd.setTitle("Обновление " + info.name);
        pd.setMessage("Скачиваю…");
        pd.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        pd.setMax(100);
        pd.setCancelable(false);
        pd.show();
        new Thread(() -> {
            try {
                File dir = new File(act.getCacheDir(), "updates");
                dir.mkdirs();
                File[] old = dir.listFiles();
                if (old != null) for (File f : old) f.delete();
                File apk = new File(dir, "murtrek-update.apk");
                HttpURLConnection c = (HttpURLConnection) new URL(info.url).openConnection();
                c.setConnectTimeout(10000);
                c.setReadTimeout(20000);
                c.setRequestProperty("User-Agent", "MurtrekApp/" + BuildConfig.VERSION_NAME);
                if (c.getResponseCode() != 200) throw new Exception("http " + c.getResponseCode());
                long total = c.getContentLengthLong(), got = 0;
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(apk)) {
                    byte[] buf = new byte[65536];
                    int n, last = -1;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        md.update(buf, 0, n);
                        got += n;
                        if (total > 0) {
                            int pct = (int) (got * 100 / total);
                            if (pct != last) {
                                last = pct;
                                act.runOnUiThread(() -> pd.setProgress(pct));
                            }
                        }
                    }
                }
                StringBuilder hex = new StringBuilder();
                for (byte b : md.digest()) hex.append(String.format("%02x", b));
                if (!info.sha.isEmpty() && !info.sha.equalsIgnoreCase(hex.toString())) {
                    apk.delete();
                    throw new Exception("sha");
                }
                act.runOnUiThread(() -> {
                    pd.dismiss();
                    install(apk);
                });
            } catch (Exception e) {
                act.runOnUiThread(() -> {
                    pd.dismiss();
                    toast("sha".equals(e.getMessage()) ? "Файл обновления повреждён, попробуйте позже" : "Не удалось скачать обновление");
                });
            }
        }).start();
    }

    private void install(File apk) {
        try {
            Uri uri = FileProvider.getUriForFile(act, act.getPackageName() + ".files", apk);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            act.startActivity(i);
        } catch (Exception e) {
            toast("Не удалось открыть установку");
        }
    }

    private void toast(String t) {
        Toast.makeText(act, t, Toast.LENGTH_LONG).show();
    }
}
