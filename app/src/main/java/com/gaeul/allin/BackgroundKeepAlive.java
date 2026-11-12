package com.gaeul.allin;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import com.gaeul.allin.json.JSONObject;

/** User-controlled vendor experiments. No permission or running state implies immunity to freezing. */
final class BackgroundKeepAlive {
    private static final String PREFS = "allin_background_experiment";
    private static volatile BackgroundKeepAlive current;
    private final Context context;
    private View overlay;
    private volatile boolean overlayAttached, audioPlaying;
    private boolean closed;
    private volatile String overlayError = "", audioError = "";
    private volatile AudioWorker audio;
    BackgroundKeepAlive(Context context) { this.context = context; current = this; }
    static boolean audioEnabled(Context c) { return enabled(c, "audio"); }
    private static boolean enabled(Context c, String key) {
        return c != null && c.getSharedPreferences(PREFS, 0).getBoolean(key, false);
    }
    static boolean setOption(Context c, String option, boolean value) {
        return c != null && ("overlay".equals(option) || "audio".equals(option))
                && c.getSharedPreferences(PREFS, 0).edit().putBoolean(option, value).commit();
    }
    static boolean overlayGranted(Context c) {
        try { return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(c); }
        catch (RuntimeException e) { return false; }
    }
    static JSONObject status(Context c) {
        JSONObject o = new JSONObject(); BackgroundKeepAlive s = current;
        try {
            o.put("overlayEnabled", enabled(c,"overlay")); o.put("audioEnabled", audioEnabled(c));
            o.put("overlayGranted", overlayGranted(c));
            o.put("overlayAttached", s != null && s.overlayAttached);
            o.put("audioPlaying", s != null && s.audioPlaying);
            o.put("overlayError", s == null ? "" : s.overlayError);
            o.put("audioError", s == null ? "" : s.audioError);
            o.put("experimental", true);
        } catch (Exception ignored) { }
        return o;
    }
    // All lifecycle methods run on the main thread; each audio worker owns its track.
    void refresh(boolean audioAllowed) {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("main thread required");
        if (enabled(context,"overlay") && overlayGranted(context)) attach();
        else { detach(); overlayError = enabled(context,"overlay") ? "需要授予悬浮窗权限" : ""; }
        if (audioEnabled(context) && audioAllowed) {
            if (audio == null) { audioError = ""; audio = new AudioWorker(); audio.start(); }
        } else {
            stopAudio(); audioError = audioEnabled(context) ? "媒体前台服务未启动" : "";
        }
    }
    private void attach() {
        if (overlay != null) return;
        try {
            WindowManager wm = (WindowManager)context.getSystemService(Context.WINDOW_SERVICE);
            View v = new View(context);
            // Actual faint pixel, not an entirely transparent background with View alpha.
            v.setBackgroundColor(Color.WHITE);
            WindowManager.LayoutParams p = new WindowManager.LayoutParams(1,1,
                    Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT);
            p.gravity = Gravity.TOP | Gravity.START; p.alpha = 0.01f;
            wm.addView(v,p); overlay = v; overlayAttached = true; overlayError = "";
        } catch (RuntimeException e) { overlayError = e.getClass().getSimpleName(); overlayAttached = false; }
    }
    private void detach() {
        if (overlay != null) {
            try { ((WindowManager)context.getSystemService(Context.WINDOW_SERVICE)).removeViewImmediate(overlay); }
            catch (RuntimeException e) { overlayError = e.getClass().getSimpleName(); }
            finally { overlay = null; overlayAttached = false; }
        }
    }
    private void stopAudio() { if (audio != null) audio.shutdown(); audioPlaying = false; }
    void close() { closed = true; detach(); stopAudio(); if (current == this) current = null; }
    private final class AudioWorker extends Thread {
        private volatile boolean stopped;
        AudioWorker() { super("allin-silent-audio"); setDaemon(true); }
        void shutdown() { stopped = true; interrupt(); }
        @Override public void run() {
            AudioTrack track = null;
            try {
                int size = AudioTrack.getMinBufferSize(8000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
                if (size <= 0) throw new IllegalStateException("unsupported audio format");
                track = new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                        .setAudioFormat(new AudioFormat.Builder().setSampleRate(8000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                        .setBufferSizeInBytes(Math.max(3200,size)).setTransferMode(AudioTrack.MODE_STREAM).build();
                if (stopped) return;
                track.play();
                byte[] silence = new byte[1600]; // 100 ms, mono PCM16 at 8 kHz.
                long next = android.os.SystemClock.elapsedRealtime();
                while (!stopped) {
                    int offset = 0;
                    while (!stopped && offset < silence.length) {
                        int n = track.write(silence,offset,silence.length-offset,AudioTrack.WRITE_NON_BLOCKING);
                        if (n < 0) throw new IllegalStateException("audio write " + n);
                        offset += n;
                        if (n == 0) Thread.sleep(10);
                    }
                    if (stopped) break;
                    new Handler(Looper.getMainLooper()).post(new Runnable() { public void run() {
                        if (!stopped && !closed && audio == AudioWorker.this) audioPlaying = true;
                    }});
                    next += 100;
                    long delay = next - android.os.SystemClock.elapsedRealtime();
                    if (delay > 0) Thread.sleep(delay); else next = android.os.SystemClock.elapsedRealtime();
                }
            } catch (InterruptedException ignored) { }
            catch (RuntimeException e) { if (!stopped && current == BackgroundKeepAlive.this) audioError = e.getClass().getSimpleName(); }
            finally {
                if (track != null) { try { track.stop(); } catch (RuntimeException ignored) { } track.release(); }
                // An old worker must not overwrite a replacement worker's state.
                new Handler(Looper.getMainLooper()).post(new Runnable() { public void run() {
                    if (audio == AudioWorker.this) {
                        audioPlaying = false; audio = null;
                        if (stopped && !closed) GatewayService.refreshKeepAlive(context);
                    }
                }});
            }
        }
    }
}
