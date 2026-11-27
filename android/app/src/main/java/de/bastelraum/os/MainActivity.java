package de.bastelraum.os;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
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
import android.widget.TextView;

import org.json.JSONObject;

import java.util.ArrayList;

public class MainActivity extends Activity {

    private static final String URL = "https://odinhennig-code.github.io/bastelraum/";
    private static final String VERSION = "1.1";

    private WebView web;
    private FrameLayout recovery;
    private TextView recText;
    private final Handler ui = new Handler(Looper.getMainLooper());

    /* ---------- Notfall-Menü ---------- */
    private static final String[] ITEMS = {
            "Zurück zum Dashboard",
            "Dashboard neu laden",
            "Nach Updates suchen",
            "Android-Einstellungen öffnen",
            "App beenden → normales Android",
            "Bildschirm ausschalten",
            "Tablet neu starten"
    };
    private int sel = 0;
    private boolean recOpen = false;
    private boolean bootDone = false;
    private int bootLines = 0;
    private boolean cursorOn = true;
    private String statusLine = "";
    private final ArrayList<Long> upPresses = new ArrayList<>();
    private boolean longFired = false;
    private final Runnable longPress = new Runnable() {
        @Override public void run() { longFired = true; confirm(); }
    };
    private final Runnable blink = new Runnable() {
        @Override public void run() {
            cursorOn = !cursorOn;
            if (recOpen) { draw(); ui.postDelayed(this, 500); }
        }
    };

    /* ---------- Spracheingabe ---------- */
    private SpeechRecognizer speech;
    private String speechTarget = "";

    private boolean pageFailed = false;

    @SuppressLint({"SetJavaScriptEnabled", "ClickableViewAccessibility"})
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

        recovery = new FrameLayout(this);
        recovery.setBackgroundColor(Color.BLACK);
        recovery.setVisibility(View.GONE);
        recText = new TextView(this);
        recText.setTypeface(Typeface.MONOSPACE);
        recText.setTextColor(Color.parseColor("#39FF7A"));
        recText.setTextSize(20);
        recText.setLineSpacing(0, 1.25f);
        recText.setPadding(70, 50, 70, 50);
        recText.setGravity(Gravity.TOP | Gravity.START);
        recText.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) {
                if (e.getAction() != MotionEvent.ACTION_UP || !bootDone) return true;
                Layout l = recText.getLayout();
                if (l == null) return true;
                int line = l.getLineForVertical((int) e.getY() - recText.getTotalPaddingTop());
                int idx = line - MENU_START;
                if (idx >= 0 && idx < ITEMS.length) {
                    if (idx == sel) confirm(); else { sel = idx; draw(); }
                }
                return true;
            }
        });
        recovery.addView(recText, new FrameLayout.LayoutParams(-1, -1));
        root.addView(recovery, new FrameLayout.LayoutParams(-1, -1));

        setContentView(root);
        askPermissions();
        web.loadUrl(URL);
    }

    private void askPermissions() {
        if (Build.VERSION.SDK_INT >= 23) {
            ArrayList<String> need = new ArrayList<>();
            for (String p : new String[]{Manifest.permission.RECORD_AUDIO, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION}) {
                if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) need.add(p);
            }
            if (!need.isEmpty()) requestPermissions(need.toArray(new String[0]), 1);
        }
    }

    /* ---------- Vollbild, Android ausblenden ---------- */
    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    @Override protected void onResume() { super.onResume(); hideSystemUi(); if (pageFailed) web.loadUrl(URL); }
    @Override public void onWindowFocusChanged(boolean f) { super.onWindowFocusChanged(f); if (f) hideSystemUi(); }

    /* ---------- Offline ---------- */
    private void showOffline() {
        pageFailed = true;
        String html = "<html><body style='margin:0;height:100vh;background:#07080C;color:#fff;font-family:sans-serif;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:14px'>"
                + "<div style='font-size:28px;font-weight:600'>Keine Verbindung</div>"
                + "<div style='opacity:.65;font-size:17px'>Das Dashboard versucht es gleich nochmal …</div></body></html>";
        web.loadDataWithBaseURL(null, html, "text/html", "utf-8", null);
        ui.postDelayed(new Runnable() { @Override public void run() { if (pageFailed) web.loadUrl(URL); } }, 15000);
    }

    /* ---------- Tasten ---------- */
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
                    if (!longFired && bootDone) { sel = (sel + ITEMS.length - 1) % ITEMS.length; statusLine = ""; draw(); }
                }
                return true;
            }
            if (code == KeyEvent.KEYCODE_VOLUME_DOWN) {
                if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0 && bootDone) {
                    sel = (sel + 1) % ITEMS.length; statusLine = ""; draw();
                }
                return true;
            }
            if (code == KeyEvent.KEYCODE_BACK) return true;
        } else {
            if (code == KeyEvent.KEYCODE_VOLUME_UP && e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
                long now = System.currentTimeMillis();
                upPresses.add(now);
                while (!upPresses.isEmpty() && now - upPresses.get(0) > 1500) upPresses.remove(0);
                if (upPresses.size() >= 3) { upPresses.clear(); openRecovery(); return true; }
            }
            if (code == KeyEvent.KEYCODE_BACK) return true;
        }
        return super.dispatchKeyEvent(e);
    }

    /* ---------- Notfall-Menü zeichnen ---------- */
    private static final int MENU_START = 8;
    private static final String[] BOOT = {
            "BASTELRAUM OS  ::  RECOVERY v" + VERSION,
            "==================================================",
            "",
            "> Gerät .......... " + Build.MANUFACTURER + " " + Build.MODEL,
            "> Android ........ " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")",
            "> Dashboard ...... läuft",
            "--------------------------------------------------",
            ""
    };

    private void openRecovery() {
        recOpen = true; bootDone = false; bootLines = 0; sel = 0; statusLine = "";
        recovery.setVisibility(View.VISIBLE);
        recovery.setAlpha(0f);
        recovery.animate().alpha(1f).setDuration(250).start();
        draw();
        ui.removeCallbacks(blink);
        ui.postDelayed(blink, 500);
        bootStep();
    }

    private void bootStep() {
        if (!recOpen) return;
        if (bootLines < BOOT.length + ITEMS.length) {
            bootLines++;
            draw();
            ui.postDelayed(new Runnable() { @Override public void run() { bootStep(); } }, bootLines < BOOT.length ? 90 : 55);
        } else {
            bootDone = true;
            draw();
        }
    }

    private void closeRecovery() {
        recOpen = false;
        ui.removeCallbacks(blink);
        recovery.animate().alpha(0f).setDuration(200).withEndAction(new Runnable() {
            @Override public void run() { recovery.setVisibility(View.GONE); }
        }).start();
        hideSystemUi();
    }

    private void draw() {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        int green = Color.parseColor("#39FF7A");
        int dim = Color.parseColor("#1F8A45");
        int amber = Color.parseColor("#FFC56B");
        for (int i = 0; i < BOOT.length && i < bootLines; i++) {
            int st = sb.length();
            sb.append(BOOT[i]).append("\n");
            if (i == 0) sb.setSpan(new ForegroundColorSpan(amber), st, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            else if (i != 3 && i != 4 && i != 5) sb.setSpan(new ForegroundColorSpan(dim), st, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        int shown = Math.max(0, bootLines - BOOT.length);
        for (int i = 0; i < ITEMS.length && i < shown; i++) {
            int st = sb.length();
            boolean on = bootDone && i == sel;
            String line = (on ? " >> " : "    ") + ITEMS[i] + "  ";
            sb.append(line);
            if (on) {
                sb.setSpan(new BackgroundColorSpan(green), st, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new ForegroundColorSpan(Color.BLACK), st, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            sb.append("\n");
        }
        if (bootDone) {
            int st = sb.length();
            sb.append("\n--------------------------------------------------\n");
            sb.append("[LAUTER] hoch    [LEISER] runter    [LAUTER halten] OK\n");
            sb.setSpan(new ForegroundColorSpan(dim), st, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (!statusLine.isEmpty()) {
                int s2 = sb.length();
                sb.append("\n").append(statusLine).append("\n");
                sb.setSpan(new ForegroundColorSpan(amber), s2, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        sb.append(cursorOn ? "\n█" : "\n ");
        recText.setText(sb);
    }

    /* ---------- Aktionen ---------- */
    private void confirm() {
        if (!bootDone) return;
        final int i = sel;
        statusLine = "> " + ITEMS[i] + " ... OK";
        draw();
        ui.postDelayed(new Runnable() { @Override public void run() { doAction(i); } }, 600);
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
                    dpm.reboot(admin);
                } else {
                    statusLine = "! Neustart geht nur im Geräte-Chef-Modus (einmalig per PC einrichten)";
                    draw();
                }
                break;
        }
    }

    private boolean safeStart(Intent in) {
        try { in.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(in); return true; }
        catch (Exception ex) { return false; }
    }

    /* ---------- Brücke zum Dashboard (JavaScript: window.BastelOS) ---------- */
    private class Bridge {
        @JavascriptInterface public boolean isApp() { return true; }
        @JavascriptInterface public String version() { return VERSION; }
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
                else action = Settings.ACTION_SETTINGS;
                if (!safeStart(new Intent(action))) safeStart(new Intent(Settings.ACTION_SETTINGS));
            } });
        }
        @JavascriptInterface public void startSpeech(final String inputId) {
            ui.post(new Runnable() { @Override public void run() { listen(inputId); } });
        }
    }

    private void sendSpeech(String id, String text, String err) {
        String js = "window.onNativeSpeech && window.onNativeSpeech(" + JSONObject.quote(id) + "," + JSONObject.quote(text == null ? "" : text) + "," + (err == null ? "null" : JSONObject.quote(err)) + ")";
        web.evaluateJavascript(js, null);
    }

    private void listen(String inputId) {
        speechTarget = inputId;
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { sendSpeech(inputId, null, "Spracheingabe ist auf diesem Tablet nicht verfügbar"); return; }
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            askPermissions(); sendSpeech(inputId, null, "Bitte das Mikrofon erlauben"); return;
        }
        if (speech != null) speech.destroy();
        speech = SpeechRecognizer.createSpeechRecognizer(this);
        speech.setRecognitionListener(new RecognitionListener() {
            @Override public void onResults(Bundle r) {
                ArrayList<String> m = r.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                sendSpeech(speechTarget, m != null && !m.isEmpty() ? m.get(0) : "", null);
            }
            @Override public void onError(int error) {
                sendSpeech(speechTarget, null, error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ? "Nichts verstanden – nochmal versuchen" : "Spracheingabe gerade nicht möglich");
            }
            @Override public void onReadyForSpeech(Bundle p) {}
            @Override public void onBeginningOfSpeech() {}
            @Override public void onRmsChanged(float v) {}
            @Override public void onBufferReceived(byte[] b) {}
            @Override public void onEndOfSpeech() {}
            @Override public void onPartialResults(Bundle p) {}
            @Override public void onEvent(int t, Bundle p) {}
        });
        Intent in = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        in.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        in.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "de-DE");
        in.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        speech.startListening(in);
    }

    @Override
    protected void onDestroy() {
        if (speech != null) speech.destroy();
        super.onDestroy();
    }
}
