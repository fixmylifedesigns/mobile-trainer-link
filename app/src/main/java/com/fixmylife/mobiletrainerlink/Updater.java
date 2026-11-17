package com.fixmylife.mobiletrainerlink;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
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
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Installs the newest GitHub release. Releases are tagged build-N and the APK's
 * versionCode is the same N.
 */
final class Updater {

    static final String RELEASES_API =
            "https://api.github.com/repos/fixmylifedesigns/mobile-trainer-link/releases/latest";
    private static final String TAG = "Updater";
    private static final String KEY_URL = "pending_url";
    private static final String KEY_BUILD = "pending_build";

    private final Activity activity;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SharedPreferences prefs;

    Updater(Activity activity) {
        this.activity = activity;
        this.prefs = activity.getSharedPreferences("updater", Context.MODE_PRIVATE);
    }

    private long currentBuild() {
        try {
            return activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0).getLongVersionCode();
        } catch (Exception e) {
            return 0L;
        }
    }

    private void toast(String msg) {
        main.post(() -> {
            if (!activity.isFinishing()) Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show();
        });
    }

    /** Finishes an update that was waiting on the install permission. */
    void resumePending() {
        String url = prefs.getString(KEY_URL, null);
        if (url == null) return;
        long build = prefs.getLong(KEY_BUILD, 0L);
        if (!activity.getPackageManager().canRequestPackageInstalls()) return;
        prefs.edit().remove(KEY_URL).remove(KEY_BUILD).apply();
        download(build, url);
    }

    void check(boolean silent) {
        new Thread(() -> {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(RELEASES_API).openConnection();
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                conn.setRequestProperty("Accept", "application/vnd.github+json");
                String body;
                try (InputStream in = conn.getInputStream()) {
                    body = new String(readAll(in), StandardCharsets.UTF_8);
                }
                conn.disconnect();

                JSONObject json = new JSONObject(body);
                Matcher m = Pattern.compile("\\d+").matcher(json.optString("tag_name"));
                long remote = m.find() ? Long.parseLong(m.group()) : 0L;
                String apkUrl = "";
                JSONArray assets = json.optJSONArray("assets");
                if (assets != null) {
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject a = assets.getJSONObject(i);
                        if (a.optString("name").endsWith(".apk")) {
                            apkUrl = a.optString("browser_download_url");
                            break;
                        }
                    }
                }
                long current = currentBuild();
                final String url = apkUrl;
                main.post(() -> {
                    if (activity.isFinishing()) return;
                    if (remote > current && !url.isEmpty()) prompt(remote, url);
                    else if (!silent) Toast.makeText(activity, "Up to date (build " + current + ")", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                Log.w(TAG, "update check failed", e);
                if (!silent) toast("Update check failed");
            }
        }).start();
    }

    private void prompt(long build, String url) {
        new AlertDialog.Builder(activity)
                .setTitle("Update available")
                .setMessage("Build " + build + " is ready to install. Download it now?")
                .setPositiveButton("Update", (d, w) -> start(build, url))
                .setNegativeButton("Later", null)
                .show();
    }

    private void start(long build, String url) {
        if (!activity.getPackageManager().canRequestPackageInstalls()) {
            prefs.edit().putString(KEY_URL, url).putLong(KEY_BUILD, build).apply();
            Toast.makeText(activity, "Allow Mobile Trainer Link to install apps, then come back",
                    Toast.LENGTH_LONG).show();
            try {
                activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + activity.getPackageName())));
            } catch (Exception e) {
                toast("Enable install permission in Settings > Apps > Mobile Trainer Link");
            }
            return;
        }
        download(build, url);
    }

    private void download(long build, String url) {
        toast("Downloading build " + build + "…");
        new Thread(() -> {
            File done = null;
            try {
                File dir = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                File target = new File(dir, "MobileTrainerLink-" + build + ".apk");
                if (target.exists()) target.delete();

                String link = url;
                HttpURLConnection conn;
                int hops = 0;
                while (true) {
                    conn = (HttpURLConnection) new URL(link).openConnection();
                    conn.setConnectTimeout(15000);
                    conn.setReadTimeout(30000);
                    conn.setInstanceFollowRedirects(false);
                    int code = conn.getResponseCode();
                    if (code >= 300 && code < 400 && hops < 5) {
                        String loc = conn.getHeaderField("Location");
                        conn.disconnect();
                        if (loc == null) throw new IllegalStateException("redirect without location");
                        link = loc;
                        hops++;
                        continue;
                    }
                    if (code != 200) throw new IllegalStateException("HTTP " + code);
                    break;
                }
                try (InputStream in = conn.getInputStream(); OutputStream out = new FileOutputStream(target)) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                conn.disconnect();
                if (target.length() < 50_000) throw new IllegalStateException("file too small");
                done = target;
            } catch (Exception e) {
                Log.w(TAG, "download failed", e);
                toast("Download failed: " + e.getMessage());
            }
            final File file = done;
            if (file != null) main.post(() -> install(file));
        }).start();
    }

    private void install(File file) {
        try {
            Uri uri = FileProvider.getUriForFile(activity, activity.getPackageName() + ".fileprovider", file);
            Intent intent = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
        } catch (Exception e) {
            toast("Could not open installer: " + e.getMessage());
        }
    }

    private static byte[] readAll(InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }
}
