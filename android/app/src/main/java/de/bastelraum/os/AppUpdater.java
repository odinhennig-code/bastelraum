package de.bastelraum.os;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Lädt eine neue Bastelraum-APK und installiert sie über den Android-Paketinstaller. */
public class AppUpdater {

    public interface Listener { void status(String msg); }

    public static void install(final Context ctx, final String apkUrl, final Listener l) {
        new Thread(new Runnable() {
            @Override public void run() {
                PackageInstaller.Session session = null;
                try {
                    l.status("Lade neue Tablet-App …");
                    HttpURLConnection c = open(apkUrl);
                    int total = c.getContentLength();
                    PackageInstaller pi = ctx.getPackageManager().getPackageInstaller();
                    PackageInstaller.SessionParams p = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                    p.setAppPackageName(ctx.getPackageName());
                    if (total > 0) p.setSize(total);
                    int id = pi.createSession(p);
                    session = pi.openSession(id);
                    InputStream in = c.getInputStream();
                    OutputStream out = session.openWrite("bastelraum.apk", 0, total > 0 ? total : -1);
                    byte[] buf = new byte[65536];
                    int n; long done = 0; int lastPct = -1;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n); done += n;
                        if (total > 0) { int pct = (int) (done * 100 / total); if (pct / 10 != lastPct / 10) { lastPct = pct; l.status("Lade neue Tablet-App … " + pct + " %"); } }
                    }
                    session.fsync(out); out.close(); in.close();
                    Intent cb = new Intent(ctx, InstallReceiver.class);
                    int flags = PendingIntent.FLAG_UPDATE_CURRENT;
                    if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
                    PendingIntent pend = PendingIntent.getBroadcast(ctx, 7, cb, flags);
                    l.status("Installiere …");
                    session.commit(pend.getIntentSender());
                    session.close();
                } catch (Exception e) {
                    if (session != null) try { session.abandon(); } catch (Exception ignored) {}
                    l.status("Update fehlgeschlagen: " + e.getClass().getSimpleName());
                }
            }
        }).start();
    }

    private static HttpURLConnection open(String u) throws Exception {
        for (int i = 0; i < 6; i++) {
            HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            int code = c.getResponseCode();
            if (code >= 300 && code < 400) { u = new URL(new URL(u), c.getHeaderField("Location")).toString(); c.disconnect(); continue; }
            if (code != 200) throw new Exception("HTTP " + code);
            return c;
        }
        throw new Exception("Zu viele Weiterleitungen");
    }

    /** Rückmeldung vom Paketinstaller: Bestätigungsdialog zeigen oder Ergebnis melden. */
    public static class InstallReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context ctx, Intent i) {
            int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = (Intent) i.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) { confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); try { ctx.startActivity(confirm); } catch (Exception ignored) {} }
                MainActivity.reportUpdate("Bitte auf „Installieren“ tippen");
            } else if (st == PackageInstaller.STATUS_SUCCESS) {
                MainActivity.reportUpdate("Neue Tablet-App installiert");
            } else {
                String m = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                MainActivity.reportUpdate("Installation abgebrochen" + (m != null ? ": " + m : ""));
            }
        }
    }
}
