package fr.fonote;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Keeps the app level with the version its server hands out.
 *
 * <p>The server's {@code /v1/health} says two things under {@code app}: the oldest version code
 * it still syncs with, and the APK it publishes, if any. Below the first, sync pauses — the
 * notes wait on the device, like offline, and leave once the app is updated. The second is
 * offered, never forced: an update installed halfway through a match would cost the match.
 *
 * <p>Installing goes through {@link PackageInstaller}: the APK is streamed into a session as it
 * is hashed, and the session is committed only if size and hash are the ones announced. Android
 * only installs over an app signed with the same key. It needs Fonote allowed to install apps,
 * which the activity asks for before anything is downloaded; from Android 12, an app updating
 * itself with that permission goes through without a confirmation, and older versions ask. The
 * process is then replaced, and the reader opens the new version: Android starts no activity
 * from the background.
 */
final class Updater {
    enum Status { CURRENT, AVAILABLE, REQUIRED }

    static final int IDLE = -1, INSTALLING = 101;
    /** Where the update stands in this process: idle, a download percentage, or in Android's hands. */
    static int progress = IDLE;
    /** Told whenever {@link #progress} moves. Both live on the main thread. */
    static Runnable changed = () -> {};

    private Updater() {}

    /** Keeps what a health answer says about versions. A server that says nothing clears it. */
    static void remember(SharedPreferences prefs, JSONObject health) {
        JSONObject app = health.optJSONObject("app");
        if (app == null) prefs.edit().remove("app_release").apply();
        else prefs.edit().putString("app_release", app.toString()).apply();
    }

    /** Another server is another set of versions: what the previous one said no longer holds. */
    static void forget(SharedPreferences prefs) { prefs.edit().remove("app_release").apply(); }

    /** The last {@code app} answer kept, or null before any server has given one. */
    static JSONObject release(SharedPreferences prefs) {
        try { return new JSONObject(prefs.getString("app_release", "")); }
        catch (Exception none) { return null; }
    }

    /** The published version, if it is newer than this one. */
    static JSONObject latest(JSONObject app) {
        JSONObject latest = app == null ? null : app.optJSONObject("latest");
        return latest != null && latest.optInt("code") > BuildConfig.VERSION_CODE ? latest : null;
    }

    static Status status(JSONObject app) {
        if (app != null && BuildConfig.VERSION_CODE < app.optInt("minimum")) return Status.REQUIRED;
        return latest(app) != null ? Status.AVAILABLE : Status.CURRENT;
    }

    /**
     * Downloads what the server publishes now and hands it to Android. The health answer is
     * asked again first: the version offered on screen may have been replaced since.
     */
    static void install(Context context, SharedPreferences prefs, String base) {
        if (progress != IDLE) return;
        Context app = context.getApplicationContext();
        Handler main = new Handler(Looper.getMainLooper());
        progress = 0; changed.run();
        new Thread(() -> {
            PackageInstaller installer = app.getPackageManager().getPackageInstaller();
            int session = -1;
            try {
                JSONObject health = new JSONObject(text(open(base, "/v1/health")));
                remember(prefs, health);
                JSONObject latest = latest(health.optJSONObject("app"));
                if (latest == null) throw new IOException("aucune version plus récente sur le serveur");
                PackageInstaller.SessionParams params =
                    new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                params.setAppPackageName(app.getPackageName());
                params.setSize(latest.getLong("size"));
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                    params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
                session = installer.createSession(params);
                try (PackageInstaller.Session open = installer.openSession(session)) {
                    download(open, base, latest, percent -> main.post(() -> { progress = percent; changed.run(); }));
                    Intent result = new Intent(app, Result.class);
                    int flags = PendingIntent.FLAG_UPDATE_CURRENT
                        | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? PendingIntent.FLAG_MUTABLE : 0);
                    open.commit(PendingIntent.getBroadcast(app, session, result, flags).getIntentSender());
                }
                main.post(() -> { progress = INSTALLING; changed.run(); });
            } catch (Exception failure) {
                if (session >= 0) installer.abandonSession(session);
                String reason = failure instanceof IOException && failure.getMessage() != null
                    ? failure.getMessage() : "serveur injoignable";
                main.post(() -> {
                    progress = IDLE; changed.run();
                    Toast.makeText(app, "Mise à jour impossible : " + reason + ".", Toast.LENGTH_LONG).show();
                });
            }
        }, "fonote-update").start();
    }

    private interface Progress { void at(int percent); }

    private static void download(PackageInstaller.Session session, String base, JSONObject latest,
                                 Progress report) throws Exception {
        long size = latest.getLong("size"), written = 0;
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        HttpURLConnection connection = open(base, "/v1/app/fonote.apk");
        try (InputStream in = connection.getInputStream();
             OutputStream out = session.openWrite("fonote.apk", 0, size)) {
            byte[] buffer = new byte[1 << 16];
            int shown = -1;
            for (int read; (read = in.read(buffer)) > 0; ) {
                written += read;
                if (written > size) throw new IOException("fichier différent de la version annoncée");
                out.write(buffer, 0, read); digest.update(buffer, 0, read);
                int percent = (int) (written * 100 / size);
                if (percent != shown) report.at(shown = percent);
            }
            session.fsync(out);
        } finally { connection.disconnect(); }
        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest()) hex.append(String.format("%02x", b));
        if (written != size) throw new IOException("téléchargement interrompu");
        if (!hex.toString().equals(latest.optString("sha256")))
            throw new IOException("fichier différent de la version annoncée");
    }

    private static HttpURLConnection open(String base, String path) throws IOException {
        URL endpoint = new URL(base + path);
        if (!endpoint.getProtocol().equals("http") && !endpoint.getProtocol().equals("https"))
            throw new IOException("adresse HTTP ou HTTPS attendue");
        HttpURLConnection connection = (HttpURLConnection) endpoint.openConnection();
        connection.setConnectTimeout(10000); connection.setReadTimeout(30000);
        connection.setInstanceFollowRedirects(false);
        int code = connection.getResponseCode();
        if (code != 200) { connection.disconnect(); throw new IOException("réponse HTTP " + code); }
        return connection;
    }

    private static String text(HttpURLConnection connection) throws IOException {
        try (InputStream in = connection.getInputStream()) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            for (int read; (read = in.read(buffer)) > 0; ) out.write(buffer, 0, read);
            return out.toString(StandardCharsets.UTF_8.name());
        } finally { connection.disconnect(); }
    }

    /**
     * Android's answer to a committed session. Asking the reader comes back here first, as an
     * intent to start; then the outcome. A success is rarely heard: the process is replaced.
     */
    public static final class Result extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {
            int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                    ? intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class)
                    : intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) {
                    context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    return;
                }
            }
            progress = IDLE; changed.run();
            String message;
            switch (status) {
                case PackageInstaller.STATUS_SUCCESS: return;
                case PackageInstaller.STATUS_FAILURE_ABORTED: message = "Mise à jour annulée."; break;
                case PackageInstaller.STATUS_FAILURE_CONFLICT:
                    message = "Mise à jour refusée : la version publiée n’est pas signée avec la même clé que celle-ci."; break;
                case PackageInstaller.STATUS_FAILURE_STORAGE: message = "Mise à jour impossible : espace de stockage insuffisant."; break;
                case PackageInstaller.STATUS_FAILURE_INCOMPATIBLE: message = "Mise à jour impossible : version incompatible avec cet appareil."; break;
                case PackageInstaller.STATUS_FAILURE_BLOCKED: message = "Mise à jour bloquée par l’appareil."; break;
                default: message = "Mise à jour impossible.";
            }
            Toast.makeText(context, message, Toast.LENGTH_LONG).show();
        }
    }
}
