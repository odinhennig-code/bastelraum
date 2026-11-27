package de.bastelraum.os;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Size;

import java.nio.ByteBuffer;
import java.util.Collections;

/**
 * Bewegungsmelder über die Frontkamera.
 * Alle 10 Sekunden ist die Kamera für 5 Sekunden an und vergleicht kleine, unscharfe
 * Helligkeits-Raster der Bilder. Es wird nichts gespeichert und nichts verschickt.
 */
public class MotionWatcher {

    public interface Listener { void onMotion(); }

    private static final int ON_MS = 5000;
    private static final int OFF_MS = 5000;
    private static final int GW = 24, GH = 18;          // Raster
    private static final int CELL_DIFF = 18;            // Helligkeitsunterschied pro Feld
    private static final float CELL_SHARE = 0.06f;      // so viel vom Bild muss sich ändern

    private final Context ctx;
    private final Listener listener;
    private HandlerThread thread;
    private Handler h;

    private CameraDevice cam;
    private CameraCaptureSession session;
    private ImageReader reader;
    private boolean enabled = false;
    private boolean running = false;
    private int[] prev;
    private int frames;

    public MotionWatcher(Context ctx, Listener l) { this.ctx = ctx.getApplicationContext(); this.listener = l; }

    private void ensureThread() {
        if (thread != null) return;
        thread = new HandlerThread("motion");
        thread.start();
        h = new Handler(thread.getLooper());
    }

    public void setEnabled(boolean on) {
        if (on == enabled) return;
        enabled = on;
        ensureThread();
        h.removeCallbacksAndMessages(null);
        if (on) h.postDelayed(cycle, 1500);
        else h.post(new Runnable() { @Override public void run() { stop(); } });
    }

    private final Runnable cycle = new Runnable() { @Override public void run() { start(); } };
    private final Runnable endWindow = new Runnable() {
        @Override public void run() { stop(); if (enabled) h.postDelayed(cycle, OFF_MS); }
    };

    private boolean hasPermission() {
        return Build.VERSION.SDK_INT < 23 || ctx.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    private String pickCamera(CameraManager cm) throws CameraAccessException {
        String any = null;
        for (String id : cm.getCameraIdList()) {
            Integer f = cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
            if (f != null && f == CameraCharacteristics.LENS_FACING_FRONT) return id;
            if (any == null) any = id;
        }
        return any;
    }

    private void start() {
        if (!enabled || running) return;
        if (!hasPermission()) { h.postDelayed(cycle, 30000); return; }
        try {
            CameraManager cm = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
            String id = pickCamera(cm);
            if (id == null) return;
            StreamConfigurationMap map = cm.getCameraCharacteristics(id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size best = null;
            if (map != null) {
                for (Size s : map.getOutputSizes(ImageFormat.YUV_420_888)) {
                    if (s.getWidth() < 160) continue;
                    if (best == null || s.getWidth() * s.getHeight() < best.getWidth() * best.getHeight()) best = s;
                }
            }
            if (best == null) best = new Size(320, 240);
            reader = ImageReader.newInstance(best.getWidth(), best.getHeight(), ImageFormat.YUV_420_888, 2);
            reader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                @Override public void onImageAvailable(ImageReader r) { analyze(r); }
            }, h);
            prev = null; frames = 0; running = true;
            cm.openCamera(id, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice c) {
                    if (!running) { c.close(); return; }
                    cam = c;
                    try {
                        c.createCaptureSession(Collections.singletonList(reader.getSurface()), new CameraCaptureSession.StateCallback() {
                            @Override public void onConfigured(CameraCaptureSession s) {
                                if (cam == null) return;
                                session = s;
                                try {
                                    CaptureRequest.Builder b = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                                    b.addTarget(reader.getSurface());
                                    s.setRepeatingRequest(b.build(), null, h);
                                } catch (Exception e) { stop(); }
                            }
                            @Override public void onConfigureFailed(CameraCaptureSession s) { stop(); }
                        }, h);
                    } catch (Exception e) { stop(); }
                }
                @Override public void onDisconnected(CameraDevice c) { c.close(); cam = null; stop(); }
                @Override public void onError(CameraDevice c, int err) { c.close(); cam = null; stop(); }
            }, h);
            h.postDelayed(endWindow, ON_MS);
        } catch (Exception e) {
            stop();
            if (enabled) h.postDelayed(cycle, 15000);
        }
    }

    private void analyze(ImageReader r) {
        Image img = null;
        try {
            img = r.acquireLatestImage();
            if (img == null || !running) return;
            Image.Plane p = img.getPlanes()[0];
            ByteBuffer buf = p.getBuffer();
            int rs = p.getRowStride(), ps = p.getPixelStride();
            int w = img.getWidth(), hh = img.getHeight();
            int[] cur = new int[GW * GH];
            for (int gy = 0; gy < GH; gy++) {
                for (int gx = 0; gx < GW; gx++) {
                    int sum = 0, n = 0;
                    int x0 = gx * w / GW, y0 = gy * hh / GH, x1 = (gx + 1) * w / GW, y1 = (gy + 1) * hh / GH;
                    for (int y = y0; y < y1; y += 3) {
                        for (int x = x0; x < x1; x += 3) {
                            int idx = y * rs + x * ps;
                            if (idx < buf.limit()) { sum += buf.get(idx) & 0xFF; n++; }
                        }
                    }
                    cur[gy * GW + gx] = n > 0 ? sum / n : 0;
                }
            }
            frames++;
            if (frames > 3 && prev != null) {
                int changed = 0;
                for (int i = 0; i < cur.length; i++) if (Math.abs(cur[i] - prev[i]) > CELL_DIFF) changed++;
                if (changed > cur.length * CELL_SHARE) {
                    h.removeCallbacks(endWindow);
                    stop();
                    listener.onMotion();
                    if (enabled) h.postDelayed(cycle, OFF_MS);
                    return;
                }
            }
            prev = cur;
        } catch (Exception ignored) {
        } finally {
            if (img != null) img.close();
        }
    }

    private void stop() {
        running = false;
        h.removeCallbacks(endWindow);
        try { if (session != null) session.close(); } catch (Exception ignored) {}
        try { if (cam != null) cam.close(); } catch (Exception ignored) {}
        try { if (reader != null) reader.close(); } catch (Exception ignored) {}
        session = null; cam = null; reader = null;
    }

    public void shutdown() {
        enabled = false;
        if (h != null) {
            h.removeCallbacksAndMessages(null);
            h.post(new Runnable() { @Override public void run() { stop(); thread.quitSafely(); } });
        }
    }
}
