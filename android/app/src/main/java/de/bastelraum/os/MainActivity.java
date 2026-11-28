package de.bastelraum.os;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final String URL = "https://odinhennig-code.github.io/bastelraum/";
    private static final String VERSION = "1.8";

    private WebView web;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean pageFailed = false;

    /* ================= Recovery ================= */
    private static final String[] ITEMS = {
            "Zurück zum Dashboard",
            "Dashboard neu laden",
            "Nach Updates suchen",
            "Android-Einstellungen öffnen",
            "App beenden (normales Android)",
            "Bildschirm ausschalten",
            "Tablet neu starten"
    };
    private static final int SEL_BG = Color.parseColor("#FF8F00");
    private static final int ITEM_FG = Color.parseColor("#E6E6E6");
    private static final int HEAD_FG = Color.parseColor("#9E9E9E");

    private LinearLayout recovery;
    private volatile boolean adminOpen = false;
    private boolean adminLong = false;
    private final Runnable adminLongPress = new Runnable() { @Override public void run() { adminLong = true; adminKey("ok"); } };
    private final TextView[] itemViews = new TextView[ITEMS.length];
    private TextView recLog;
    private int sel = 0;
    private boolean recOpen = false;
    private final ArrayList<Long> upPresses = new ArrayList<>();
    private boolean longFired = false;
    private final Runnable longPress = new Runnable() {
        @Override public void run() { longFired = true; confirm(); }
    };

    /* ================= Sprache ================= */
    private SpeechRecognizer speech;
    private String mode = "idle";          // idle | wake | cmd
    private String speechTarget = "";
    private boolean wakeEnabled = false;
    private long lastRms = 0;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    /* ================= Bewegungsmelder ================= */
    private MotionWatcher motion;
    private boolean motionWanted = false;

    private final Runnable wakeRunner = new Runnable() { @Override public void run() { startWake(); } };

    @SuppressLint({"SetJavaScriptEnabled"})
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_FULLSCREEN);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setGeolocationEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setUserAgentString(s.getUserAgentString() + " BastelraumOS/" + VERSION);
        web.addJavascriptInterface(new Bridge(), "BastelOS");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedError(WebView v, WebResourceRequest req, WebResourceError err) {
                if (req.isForMainFrame()) showOffline();
            }
            @Override
            public void onPageFinished(WebView v, String url) {
                if (url != null && url.startsWith("http")) pageFailed = false;
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest r) {
                ui.post(new Runnable() { @Override public void run() { r.grant(r.getResources()); } });
            }
            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback cb) {
                cb.invoke(origin, true, true);
            }
        });
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));

        buildRecovery();
        root.addView(recovery, new FrameLayout.LayoutParams(-1, -1));

        setContentView(root);
        askPermissions();

        initTts();

        motion = new MotionWatcher(this, new MotionWatcher.Listener() {
            @Override public void onMotion() {
                ui.post(new Runnable() { @Override public void run() { js("window.onMotion && window.onMotion()"); } });
            }
        });

        web.loadUrl(URL);
    }

    /* Schönere Stimme: SherpaTTS (Piper-Stimmen, kostenlos, offline) wird bevorzugt, wenn installiert */
    private static final String[] NICE_ENGINES = {"org.woheller69.ttsengine", "com.k2fsa.sherpa.onnx.tts.engine"};
    private void initTts() {
        String engine = null;
        for (String pkg : NICE_ENGINES) {
            try { getPackageManager().getPackageInfo(pkg, 0); engine = pkg; break; } catch (Exception ignored) {}
        }
        TextToSpeech.OnInitListener l = new TextToSpeech.OnInitListener() {
            @Override public void onInit(int st) {
                if (st == TextToSpeech.SUCCESS) {
                    tts.setLanguage(Locale.GERMANY);
                    pickMaleVoice();
                    tts.setSpeechRate(1.0f);
                    ttsReady = true;
                }
            }
        };
        if (tts != null) { try { tts.shutdown(); } catch (Exception ignored) {} }
        ttsReady = false;
        tts = engine != null ? new TextToSpeech(this, l, engine) : new TextToSpeech(this, l);
    }

    /* Männliche deutsche Stimme suchen (Google-Stimmen „deg“/„nfh“, Thorsten bei SherpaTTS) */
    private void pickMaleVoice() {
        try {
            java.util.Set<android.speech.tts.Voice> all = tts.getVoices();
            if (all == null) return;
            android.speech.tts.Voice best = null; int bestScore = -99;
            for (android.speech.tts.Voice v : all) {
                if (v.getLocale() == null || !"de".equals(v.getLocale().getLanguage())) continue;
                String n = v.getName().toLowerCase(Locale.ROOT);
                int sc = 0;
                if (n.contains("thorsten") || n.contains("-deg-") || n.contains("-nfh-") || (n.contains("male") && !n.contains("female"))) sc += 10;
                if (n.contains("female") || n.contains("-deb-") || n.contains("-dea-") || n.contains("eva") || n.contains("kerstin") || n.contains("ramona")) sc -= 5;
                if (v.isNetworkConnectionRequired()) sc += 2;      // Online-Stimmen klingen natürlicher
                if (v.getQuality() >= android.speech.tts.Voice.QUALITY_HIGH) sc += 2;
                if ("DE".equals(v.getLocale().getCountry())) sc += 1;
                if (sc > bestScore) { bestScore = sc; best = v; }
            }
            if (best != null) tts.setVoice(best);
            if (bestScore < 10) tts.setPitch(0.85f); else tts.setPitch(1.0f);
        } catch (Exception ignored) {}
    }

    private void askPermissions() {
        if (Build.VERSION.SDK_INT >= 23) {
            ArrayList<String> need = new ArrayList<>();
            for (String p : new String[]{Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION}) {
                if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) need.add(p);
            }
            if (!need.isEmpty()) requestPermissions(need.toArray(new String[0]), 1);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        if (wakeEnabled) restartWake(500);
    }

    /* ---------- Vollbild ---------- */
    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    @Override protected void onResume() {
        super.onResume();
        hideSystemUi();
        if (pageFailed) web.loadUrl(URL);
        else web.evaluateJavascript("window.bastelCheckUpdate && window.bastelCheckUpdate()", null);
        if (wakeEnabled) restartWake(800);
        if (tts != null && !ttsReady) initTts();
        if (motion != null) motion.setEnabled(motionWanted);
    }

    @Override protected void onPause() {
        super.onPause();
        ui.removeCallbacks(wakeRunner);
        if ("wake".equals(mode) && speech != null) { speech.cancel(); mode = "idle"; }
        unmuteBeeps();
        if (motion != null) motion.setEnabled(false);
    }

    @Override public void onWindowFocusChanged(boolean f) { super.onWindowFocusChanged(f); if (f) hideSystemUi(); }

    private void showOffline() {
        pageFailed = true;
        String html = "<html><body style='margin:0;height:100vh;background:#07080C;color:#fff;font-family:sans-serif;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:14px'>"
                + "<div style='font-size:28px;font-weight:600'>Keine Verbindung</div>"
                + "<div style='opacity:.65;font-size:17px'>Das Dashboard versucht es gleich nochmal …</div></body></html>";
        web.loadDataWithBaseURL(null, html, "text/html", "utf-8", null);
        ui.postDelayed(new Runnable() { @Override public void run() { if (pageFailed) web.loadUrl(URL); } }, 15000);
    }

    /* ================= Tasten ================= */
    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        if (recOpen) {
            if (code == KeyEvent.KEYCODE_VOLUME_UP) {
                if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
                    longFired = false;
                    ui.postDelayed(longPress, 700);
                } else if (e.getAction() == KeyEvent.ACTION_UP) {
                    ui.removeCallbacks(longPress);
                    if (!longFired) { sel = (sel + ITEMS.length - 1) % ITEMS.length; paintMenu(); }
                }
                return true;
            }
            if (code == KeyEvent.KEYCODE_VOLUME_DOWN) {
                if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
                    sel = (sel + 1) % ITEMS.length; paintMenu();
                }
                return true;
            }
            if (code == KeyEvent.KEYCODE_BACK) return true;
        } else if (adminOpen) {
            if (code == KeyEvent.KEYCODE_VOLUME_UP) {
                if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) { adminLong = false; ui.postDelayed(adminLongPress, 700); }
                else if (e.getAction() == KeyEvent.ACTION_UP) { ui.removeCallbacks(adminLongPress); if (!adminLong) adminKey("up"); }
                return true;
            }
            if (code == KeyEvent.KEYCODE_VOLUME_DOWN) {
                if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) adminKey("down");
                return true;
            }
            if (code == KeyEvent.KEYCODE_BACK) { if (e.getAction() == KeyEvent.ACTION_UP) adminKey("back"); return true; }
        } else {
            if (code == KeyEvent.KEYCODE_VOLUME_UP && e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
                long now = System.currentTimeMillis();
                upPresses.add(now);
                while (!upPresses.isEmpty() && now - upPresses.get(0) > 1500) upPresses.remove(0);
                if (upPresses.size() >= 3) { upPresses.clear(); openAdminOrRecovery(); return true; }
            }
            if (code == KeyEvent.KEYCODE_BACK) return true;
        }
        return super.dispatchKeyEvent(e);
    }

    /* ================= Recovery-Bildschirm (wie die echte Android-Recovery) ================= */
    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private TextView headLine(String t, int color, float size) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextColor(color);
        v.setTextSize(size);
        v.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        v.setPadding(0, dp(1), 0, dp(1));
        return v;
    }

    private void buildRecovery() {
        recovery = new LinearLayout(this);
        recovery.setOrientation(LinearLayout.VERTICAL);
        recovery.setBackgroundColor(Color.BLACK);
        recovery.setPadding(dp(28), dp(22), dp(28), dp(18));
        recovery.setVisibility(View.GONE);
        recovery.setClickable(true);

        recovery.addView(headLine("Bastelraum Recovery", Color.WHITE, 17));
        recovery.addView(headLine(Build.FINGERPRINT, HEAD_FG, 13));
        recovery.addView(headLine("Version " + Build.VERSION.RELEASE + " (" + Build.DISPLAY + ")", HEAD_FG, 13));
        recovery.addView(headLine("Gerät: " + Build.MANUFACTURER + " " + Build.MODEL + "  ·  Dashboard-App " + VERSION, HEAD_FG, 13));
        recovery.addView(headLine("Lauter/Leiser zum Auswählen. Lauter gedrückt halten zum Bestätigen.", HEAD_FG, 13));

        View gap = new View(this);
        recovery.addView(gap, new LinearLayout.LayoutParams(-1, dp(16)));

        for (int i = 0; i < ITEMS.length; i++) {
            final int idx = i;
            TextView v = new TextView(this);
            v.setText(ITEMS[i]);
            v.setTextSize(19);
            v.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            v.setPadding(dp(10), dp(9), dp(10), dp(9));
            v.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) {
                    if (sel == idx) confirm(); else { sel = idx; paintMenu(); }
                }
            });
            itemViews[i] = v;
            recovery.addView(v, new LinearLayout.LayoutParams(-1, -2));
        }

        View fill = new View(this);
        recovery.addView(fill, new LinearLayout.LayoutParams(-1, 0, 1f));

        recLog = headLine("", HEAD_FG, 13);
        recovery.addView(recLog, new LinearLayout.LayoutParams(-1, -2));
    }

    private void paintMenu() {
        for (int i = 0; i < ITEMS.length; i++) {
            boolean on = i == sel;
            itemViews[i].setBackgroundColor(on ? SEL_BG : Color.TRANSPARENT);
            itemViews[i].setTextColor(on ? Color.BLACK : ITEM_FG);
        }
    }

    private void log(String line) {
        CharSequence old = recLog.getText();
        String t = (old.length() > 0 ? old + "\n" : "") + line;
        String[] lines = t.split("\n");
        if (lines.length > 6) {
            StringBuilder sb = new StringBuilder();
            for (int i = lines.length - 6; i < lines.length; i++) { if (sb.length() > 0) sb.append("\n"); sb.append(lines[i]); }
            t = sb.toString();
        }
        recLog.setText(t);
    }

    private void openRecovery() {
        recOpen = true; sel = 0;
        recLog.setText("");
        log("Supported API: 3");
        DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
        log("-- Geräte-Admin: " + (dpm.isAdminActive(new ComponentName(this, AdminReceiver.class)) ? "aktiv" : "aus")
                + "   Geräte-Chef: " + (dpm.isDeviceOwnerApp(getPackageName()) ? "ja" : "nein"));
        paintMenu();
        recovery.setVisibility(View.VISIBLE);
        ui.removeCallbacks(wakeRunner);
        if (speech != null && "wake".equals(mode)) { speech.cancel(); mode = "idle"; }
    }

    private void adminKey(String k) {
        web.evaluateJavascript("window.onAdminKey && window.onAdminKey('" + k + "')", null);
    }

    /* Admin-Menü liegt im Dashboard (lässt sich live updaten). Fehlt es, kommt die alte Recovery. */
    private void openAdminOrRecovery() {
        if (pageFailed) { openRecovery(); return; }
        web.evaluateJavascript("(window.openAdmin ? (window.openAdmin(), 'ok') : 'no')", new android.webkit.ValueCallback<String>() {
            @Override public void onReceiveValue(String v) { if (v == null || !v.contains("ok")) openRecovery(); }
        });
    }

    private void closeRecovery() {
        recOpen = false;
        recovery.setVisibility(View.GONE);
        hideSystemUi();
        if (wakeEnabled) restartWake(800);
    }

    private void confirm() {
        final int i = sel;
        log("-- " + ITEMS[i] + " ...");
        ui.postDelayed(new Runnable() { @Override public void run() { doAction(i); } }, 450);
    }

    private void doAction(int i) {
        DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
        ComponentName admin = new ComponentName(this, AdminReceiver.class);
        switch (i) {
            case 0:
                closeRecovery();
                break;
            case 1:
                web.clearCache(true);
                web.loadUrl(URL + "?t=" + System.currentTimeMillis());
                closeRecovery();
                break;
            case 2:
                web.evaluateJavascript("window.bastelCheckUpdate && window.bastelCheckUpdate()", null);
                closeRecovery();
                break;
            case 3:
                closeRecovery();
                safeStart(new Intent(Settings.ACTION_SETTINGS));
                break;
            case 4:
                closeRecovery();
                if (!safeStart(new Intent(Settings.ACTION_HOME_SETTINGS))) safeStart(new Intent(Settings.ACTION_SETTINGS));
                break;
            case 5:
                if (dpm.isAdminActive(admin)) {
                    closeRecovery();
                    dpm.lockNow();
                } else {
                    Intent add = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
                    add.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin);
                    add.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Damit das Dashboard den Bildschirm ausschalten darf.");
                    closeRecovery();
                    safeStart(add);
                }
                break;
            case 6:
                if (Build.VERSION.SDK_INT >= 24 && dpm.isDeviceOwnerApp(getPackageName())) {
                    log("Rebooting...");
                    dpm.reboot(admin);
                } else {
                    log("E: Neustart nur im Geräte-Chef-Modus möglich.");
                }
                break;
        }
    }

    private static String esc(String t) { return t == null ? "" : t.replace("\\", "\\\\").replace("\"", "\\\""); }

    private boolean safeStart(Intent in) {
        try { in.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(in); return true; }
        catch (Exception ex) { return false; }
    }

    /* ================= Brücke zum Dashboard ================= */
    private class Bridge {
        @JavascriptInterface public boolean isApp() { return true; }
        @JavascriptInterface public String version() { return VERSION; }
        @JavascriptInterface public void setAdminOpen(final boolean on) {
            adminOpen = on;
            ui.post(new Runnable() { @Override public void run() {
                if (on) { ui.removeCallbacks(wakeRunner); if (speech != null && "wake".equals(mode)) { speech.cancel(); mode = "idle"; } }
                else if (wakeEnabled) restartWake(800);
            } });
        }
        @JavascriptInterface public void systemAction(final int i) {
            ui.post(new Runnable() { @Override public void run() { doAction(i); } });
        }
        @JavascriptInterface public String deviceInfo() {
            DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
            boolean adm = dpm.isAdminActive(new ComponentName(MainActivity.this, AdminReceiver.class));
            boolean own = dpm.isDeviceOwnerApp(getPackageName());
            return "{\"model\":\"" + esc(Build.MANUFACTURER + " " + Build.MODEL) + "\",\"android\":\"" + esc(Build.VERSION.RELEASE) + "\",\"sdk\":" + Build.VERSION.SDK_INT
                + ",\"build\":\"" + esc(Build.DISPLAY) + "\",\"fp\":\"" + esc(Build.FINGERPRINT) + "\",\"admin\":" + adm + ",\"owner\":" + own + ",\"app\":\"" + VERSION + "\"}";
        }
        @JavascriptInterface public void openRecovery() {
            ui.post(new Runnable() { @Override public void run() { MainActivity.this.openRecovery(); } });
        }
        @JavascriptInterface public void setBrightness(final float v) {
            ui.post(new Runnable() { @Override public void run() {
                WindowManager.LayoutParams lp = getWindow().getAttributes();
                lp.screenBrightness = v < 0 ? WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE : Math.max(0.01f, Math.min(1f, v));
                getWindow().setAttributes(lp);
            } });
        }
        @JavascriptInterface public void openSettings(final String which) {
            ui.post(new Runnable() { @Override public void run() {
                String action;
                if ("wifi".equals(which)) action = Settings.ACTION_WIFI_SETTINGS;
                else if ("bluetooth".equals(which)) action = Settings.ACTION_BLUETOOTH_SETTINGS;
                else if ("sound".equals(which)) action = Settings.ACTION_SOUND_SETTINGS;
                else if ("display".equals(which)) action = Settings.ACTION_DISPLAY_SETTINGS;
                else if ("tts".equals(which)) { if (safeStart(new Intent("com.android.settings.TTS_SETTINGS"))) { ui.postDelayed(new Runnable() { @Override public void run() { initTts(); } }, 1000); return; } action = Settings.ACTION_SETTINGS; }
                else action = Settings.ACTION_SETTINGS;
                if (!safeStart(new Intent(action))) safeStart(new Intent(Settings.ACTION_SETTINGS));
            } });
        }
        @JavascriptInterface public void startSpeech(final String inputId) {
            ui.post(new Runnable() { @Override public void run() { listenCommand(inputId); } });
        }
        @JavascriptInterface public void setWakeWord(final boolean on) {
            ui.post(new Runnable() { @Override public void run() {
                wakeEnabled = on;
                if (on) restartWake(1500);
                else { ui.removeCallbacks(wakeRunner); if ("wake".equals(mode) && speech != null) { speech.cancel(); mode = "idle"; } }
            } });
        }
        @JavascriptInterface public void setRadio(final boolean on) {
            ui.post(new Runnable() { @Override public void run() {
                radioOn = on;
                if (on) {
                    unmuteBeeps();
                    ui.removeCallbacks(wakeRunner);
                    if ("wake".equals(mode) && speech != null) { try { speech.cancel(); } catch (Exception ignored) {} mode = "idle"; }
                } else if (wakeEnabled) restartWake(1500);
            } });
        }
        @JavascriptInterface public void setMotionWatch(final boolean on) {
            ui.post(new Runnable() { @Override public void run() { motionWanted = on; if (motion != null) motion.setEnabled(on); } });
        }
        @JavascriptInterface public void speak(final String text) {
            ui.post(new Runnable() { @Override public void run() {
                if (ttsReady) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "luigi");
            } });
        }
    }

    private void js(String code) { web.evaluateJavascript(code, null); }
    private String q(String s) { return JSONObject.quote(s == null ? "" : s); }

    /* ================= Spracherkennung: „Hey Luigi“ + Befehle ================= */
    private void ensureRecognizer() {
        if (speech != null) return;
        speech = SpeechRecognizer.createSpeechRecognizer(this);
        speech.setRecognitionListener(new RecognitionListener() {
            @Override public void onResults(Bundle r) {
                ArrayList<String> m = r.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                String text = m != null && !m.isEmpty() ? m.get(0) : "";
                if ("cmd".equals(mode)) {
                    mode = "idle";
                    js("window.onNativeSpeech && window.onNativeSpeech(" + q(speechTarget) + "," + q(text) + ",null)");
                    restartWake(2500);
                } else if ("wake".equals(mode)) {
                    mode = "idle";
                    String hit = null;
                    if (m != null) for (String cand : m) { String rest = afterWakeWord(cand); if (rest != null) { hit = rest; break; } }
                    if (hit != null) {
                        js("window.onWakeWord && window.onWakeWord(" + q(hit) + ")");
                    } else {
                        restartWake(150);
                    }
                }
            }
            @Override public void onPartialResults(Bundle p) {
                if (!"cmd".equals(mode)) return;
                cmdHeard = true;
                ArrayList<String> m = p.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (m != null && !m.isEmpty()) js("window.onNativePartial && window.onNativePartial(" + q(speechTarget) + "," + q(m.get(0)) + ")");
            }
            @Override public void onError(int error) {
                if ("cmd".equals(mode)) {
                    boolean quiet = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT;
                    boolean glitch = error == SpeechRecognizer.ERROR_CLIENT || error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY;
                    if ((glitch || (quiet && !cmdHeard)) && retryCmd()) return;   // Fehlstart: still weiter zuhören
                    mode = "idle";
                    String msg = quiet ? (cmdHeard ? "nicht verstanden" : "nichts gehört") : "Spracheingabe gerade nicht möglich";
                    js("window.onNativeSpeech && window.onNativeSpeech(" + q(speechTarget) + ",''," + q(msg) + ")");
                    restartWake(2500);
                } else {
                    mode = "idle";
                    restartWake(error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ? 2000 : 300);
                }
            }
            @Override public void onRmsChanged(float v) {
                if (!"cmd".equals(mode)) return;
                long now = System.currentTimeMillis();
                if (now - lastRms < 80) return;
                lastRms = now;
                js("window.onNativeRms && window.onNativeRms(" + v + ")");
            }
            @Override public void onReadyForSpeech(Bundle p) {}
            @Override public void onBeginningOfSpeech() { if ("cmd".equals(mode)) cmdHeard = true; }
            @Override public void onBufferReceived(byte[] b) {}
            @Override public void onEndOfSpeech() { muteFor(1200); }
            @Override public void onEvent(int t, Bundle p) {}
        });
    }

    /** Text nach „Luigi“, oder null, wenn Luigi nicht gerufen wurde. */
    private String afterWakeWord(String said) {
        String s = said.toLowerCase(Locale.GERMAN);
        String[] keys = {"luigi", "luiggi", "louigi", "luidschi", "lujdschi", "luici", "lu igi", "lui gi"};
        for (String k : keys) {
            int at = s.indexOf(k);
            if (at >= 0) return said.substring(Math.min(said.length(), at + k.length())).replaceFirst("^[\\s,.!?]+", "");
        }
        return null;
    }

    private Intent recIntent(boolean partial) {
        Intent in = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        in.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        in.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "de-DE");
        in.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);
        in.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, partial);
        in.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
        in.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1800);
        in.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500);
        in.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 2500);
        return in;
    }

    private boolean canListen() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return false;
        return Build.VERSION.SDK_INT < 23 || checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private void restartWake(long delay) {
        ui.removeCallbacks(wakeRunner);
        if (wakeEnabled) ui.postDelayed(wakeRunner, delay);
    }

    private void startWake() {
        if (!wakeEnabled || recOpen || radioOn || "cmd".equals(mode) || !canListen()) return;
        if (tts != null && tts.isSpeaking()) { restartWake(800); return; }
        ensureRecognizer();
        muteBeeps();
        mode = "wake";
        try { speech.startListening(recIntent(false)); } catch (Exception e) { mode = "idle"; restartWake(2000); }
    }

    private void listenCommand(String inputId) {
        speechTarget = inputId;
        if (!canListen()) {
            askPermissions();
            js("window.onNativeSpeech && window.onNativeSpeech(" + q(inputId) + ",''," + q("Bitte das Mikrofon erlauben") + ")");
            return;
        }
        ui.removeCallbacks(wakeRunner);
        ensureRecognizer();
        boolean wasWaking = "wake".equals(mode);
        try { speech.cancel(); } catch (Exception ignored) {}
        muteBeeps();
        mode = "cmd"; cmdHeard = false; cmdRetries = 0;
        ui.postDelayed(new Runnable() { @Override public void run() { startCmd(); } }, wasWaking ? 350 : 60);
    }

    private boolean cmdHeard = false;
    private int cmdRetries = 0;
    private void startCmd() {
        if (!"cmd".equals(mode)) return;
        try { speech.startListening(recIntent(true)); }
        catch (Exception e) { retryCmd(); }
    }
    private boolean retryCmd() {
        if (cmdRetries >= 2) return false;
        cmdRetries++;
        try { speech.cancel(); } catch (Exception ignored) {}
        ui.postDelayed(new Runnable() { @Override public void run() { startCmd(); } }, 400);
        return true;
    }

    /* Die Erkennungs-Pieptöne stummschalten: kurz um Start und Ende der Erkennung herum.
       Läuft das Radio, wird nichts stumm geschaltet (dann geht der Piep im Radio unter). */
    private boolean radioOn = false;
    private boolean muted = false;
    private final Runnable unmuteRunner = new Runnable() { @Override public void run() { unmuteBeeps(); } };
    private void muteBeeps() { muteFor(900); }
    private void muteFor(long ms) {
        ui.removeCallbacks(unmuteRunner);
        if (!radioOn && !muted && Build.VERSION.SDK_INT >= 23) {
            try {
                AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0);
                am.adjustStreamVolume(AudioManager.STREAM_SYSTEM, AudioManager.ADJUST_MUTE, 0);
                am.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, AudioManager.ADJUST_MUTE, 0);
                muted = true;
            } catch (Exception ignored) {}
        }
        ui.postDelayed(unmuteRunner, ms);
    }
    private void unmuteBeeps() {
        ui.removeCallbacks(unmuteRunner);
        if (!muted || Build.VERSION.SDK_INT < 23) return;
        try {
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0);
            am.adjustStreamVolume(AudioManager.STREAM_SYSTEM, AudioManager.ADJUST_UNMUTE, 0);
            am.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, AudioManager.ADJUST_UNMUTE, 0);
        } catch (Exception ignored) {}
        muted = false;
    }

    @Override
    protected void onDestroy() {
        unmuteBeeps();
        if (speech != null) speech.destroy();
        if (tts != null) tts.shutdown();
        if (motion != null) motion.shutdown();
        super.onDestroy();
    }
}
