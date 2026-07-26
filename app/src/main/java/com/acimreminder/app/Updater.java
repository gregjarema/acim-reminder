package com.acimreminder.app;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Checks GitHub for a newer build, fetches it, and offers to install it.
 *
 * The one thing this can't do is install silently. Android only lets a system
 * or device-owner app do that; everything else has to hand the APK to the
 * package installer, which asks you to confirm. So "automatic" here means the
 * checking and the downloading happen on their own, and all that's left is one
 * tap on the notification.
 *
 * This also depends on the app being signed with a stable key — see
 * app/build.gradle. An APK signed by a different key can't replace an installed
 * one no matter how it's delivered.
 */
public final class Updater {

    private static final String RELEASES =
            "https://api.github.com/repos/gregjarema/acim-reminder/releases/latest";

    private static final int NOTIF_ID = 5001;
    /** Remembers the build we've already downloaded, so we fetch it once. */
    private static final String KEY_FETCHED = "update_fetched_build";

    /** What the newest release is, or null if we couldn't tell. */
    public static final class Release {
        public final int buildNumber;      // parsed from the "build-41" tag
        public final String tag;
        public final String apkUrl;

        Release(int buildNumber, String tag, String apkUrl) {
            this.buildNumber = buildNumber;
            this.tag = tag;
            this.apkUrl = apkUrl;
        }
    }

    /** Blocking; call from a background thread. Returns null on any failure. */
    public static Release latest() {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(RELEASES).openConnection();
            c.setRequestProperty("Accept", "application/vnd.github+json");
            c.setConnectTimeout(15000);
            c.setReadTimeout(15000);
            if (c.getResponseCode() != 200) return null;

            StringBuilder sb = new StringBuilder();
            try (InputStream in = c.getInputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) sb.append(new String(buf, 0, n, "UTF-8"));
            }
            JSONObject o = new JSONObject(sb.toString());
            String tag = o.optString("tag_name", "");
            int build = parseBuild(tag);
            if (build <= 0) return null;

            String apk = null;
            JSONArray assets = o.optJSONArray("assets");
            for (int i = 0; assets != null && i < assets.length(); i++) {
                JSONObject a = assets.getJSONObject(i);
                if (a.optString("name", "").endsWith(".apk")) {
                    apk = a.optString("browser_download_url", null);
                    break;
                }
            }
            return apk == null ? null : new Release(build, tag, apk);
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** "build-41" -> 41. The release tag is the run number, which only grows. */
    static int parseBuild(String tag) {
        try {
            java.util.regex.Matcher m =
                    java.util.regex.Pattern.compile("(\\d+)").matcher(tag == null ? "" : tag);
            return m.find() ? Integer.parseInt(m.group(1)) : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * The build number this APK came from. CI stamps it into versionName as the
     * release tag, so the two can be compared without a second source of truth.
     */
    public static int installedBuild(Context ctx) {
        try {
            String name = ctx.getPackageManager()
                    .getPackageInfo(ctx.getPackageName(), 0).versionName;
            java.util.regex.Matcher m =
                    java.util.regex.Pattern.compile("build-(\\d+)").matcher(name == null ? "" : name);
            if (m.find()) return Integer.parseInt(m.group(1));
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        return -1;
    }

    /**
     * Check, download if newer, and post the notification that installs it.
     * Blocking; call from a background thread.
     */
    public static void checkAndFetch(Context ctx) {
        Release r = latest();
        if (r == null) return;

        int installed = installedBuild(ctx);
        // If we can't tell what we're running, do nothing rather than nag on
        // every check.
        if (installed < 0 || r.buildNumber <= installed) return;

        File apk = new File(ctx.getCacheDir(), "update.apk");
        int fetched = ctx.getSharedPreferences(OnboardingActivity.PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_FETCHED, -1);
        if (fetched != r.buildNumber || !apk.exists()) {
            if (!download(r.apkUrl, apk)) return;
            ctx.getSharedPreferences(OnboardingActivity.PREFS, Context.MODE_PRIVATE)
                    .edit().putInt(KEY_FETCHED, r.buildNumber).apply();
        }
        notifyReady(ctx, r, apk);
    }

    private static boolean download(String url, File dest) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setInstanceFollowRedirects(true);
            c.setConnectTimeout(20000);
            c.setReadTimeout(60000);
            if (c.getResponseCode() != 200) return false;
            try (InputStream in = c.getInputStream();
                 FileOutputStream out = new FileOutputStream(dest)) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            }
            return dest.length() > 0;
        } catch (Exception e) {
            dest.delete();
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** The install intent, handed the APK through a FileProvider uri. */
    public static Intent installIntent(Context ctx, File apk) {
        Uri uri = FileProvider.getUriForFile(ctx, ctx.getPackageName() + ".updates", apk);
        return new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    private static void notifyReady(Context ctx, Release r, File apk) {
        Notify.ensureChannels(ctx);
        PendingIntent pi = PendingIntent.getActivity(ctx, 0, installIntent(ctx, apk),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, Notify.CH_MEDITATION)
                .setSmallIcon(R.drawable.ic_stat_bell)
                .setContentTitle("Update ready")
                .setContentText("Build " + r.buildNumber + " downloaded — tap to install.")
                .setAutoCancel(true)
                .setContentIntent(pi);
        try {
            NotificationManagerCompat.from(ctx).notify(NOTIF_ID, b.build());
        } catch (SecurityException ignored) {
        }
    }

    private Updater() {}
}
