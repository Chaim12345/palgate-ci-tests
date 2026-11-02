package com.palgate.encoderprobe;

// Standalone synthetic encoder probe (CI diagnostic, no user data, no network).
// Replicates AARE's exact MediaCodec surface-encoder usage on a virtual
// display, in variants, to discriminate a codec-stack wedge from app-usage
// effects. Feeds SYNTHETIC color content only. Results: logcat tag EncProbe
// (RESULT lines) + appended probe_results.txt in app external files dir.

import android.app.Activity;
import android.app.Presentation;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Bundle;
import android.view.Choreographer;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
import android.view.Display;
import android.view.View;
import android.view.WindowManager;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class ProbeActivity extends Activity {
    private static final String T = "EncProbe";
    private static final int W = 1280, H = 720, RUN_MS = 8000;

    private final StringBuilder report = new StringBuilder();

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        new Thread(new Runnable() { @Override public void run() { runAll(); } }, "probe").start();
    }

    private void runAll() {
        // V1: AARE-exact format incl. prepend-sps-pps + static content (draws
        // once, like AARE's Compose home screen).
        runVariant("v1-aare-exact-static", true, true, false);
        // V2: AARE fallback (no prepend) + static content.
        runVariant("v2-aare-fallback-static", false, true, false);
        // V3: AARE fallback + animated content (continuous input frames).
        runVariant("v3-fallback-animated", false, true, true);
        // V4: minimal format (required keys only) + animated content.
        runVariant("v4-minimal-animated", false, false, true);
        Log.i(T, "PROBE-DONE");
        appendReport();
        runOnUiThread(new Runnable() { @Override public void run() { finish(); } });
    }

    private void runVariant(String name, boolean prepend, boolean extras, boolean animated) {
        Log.i(T, "PROBE-BEGIN " + name);
        MediaCodec enc = null;
        VirtualDisplay vd = null;
        Presentation pres = null;
        CountDownLatch firstOut = new CountDownLatch(1);
        long[] outBuf = {0}, outBytes = {0};
        boolean[] csd = {false};
        int[] drawn = {0};
        boolean configOk = false;
        String configErr = "";
        HandlerThread codecThread = new HandlerThread("codec");
        codecThread.start();
        Handler codecH = new Handler(codecThread.getLooper());
        try {
            MediaFormat f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, W, H);
            f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            f.setInteger(MediaFormat.KEY_BIT_RATE, 3317760);
            f.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR);
            f.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
            f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);
            if (extras) {
                // Exactly AARE's extras (from its rejected-config log line).
                f.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline);
                f.setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31);
                f.setInteger("latency", 1);
                f.setInteger("priority", 0);
                f.setInteger("max-bframes", 0);
                f.setLong("repeat-previous-frame-after", 200000);
            }
            if (prepend) f.setInteger("prepend-sps-pps-to-idr-frames", 1);

            enc = MediaCodec.createByCodecName("c2.android.avc.encoder");
            MediaCodec codec = enc;
            codec.setCallback(new MediaCodec.Callback() {
                @Override public void onInputBufferAvailable(MediaCodec c, int i) {}
                @Override public void onOutputBufferAvailable(MediaCodec c, int i, MediaCodec.BufferInfo bi) {
                    outBuf[0]++;
                    outBytes[0] += bi.size;
                    if ((bi.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) csd[0] = true;
                    ByteBuffer bb = c.getOutputBuffer(i);
                    if (bb != null && bi.size >= 8 && (bi.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        firstOut.countDown();
                    }
                    c.releaseOutputBuffer(i, false);
                }
                @Override public void onError(MediaCodec c, MediaCodec.CodecException e) {
                    Log.e(T, "PROBE-ERR " + name + " codec error: " + e.getDiagnosticInfo());
                }
                @Override public void onOutputFormatChanged(MediaCodec c, MediaFormat mf) {
                    Log.i(T, "PROBE-EVT " + name + " output format changed: " + mf);
                }
            }, codecH);
            try {
                codec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                configOk = true;
            } catch (Exception e) {
                configErr = String.valueOf(e.getMessage());
                Log.e(T, "PROBE-EVT " + name + " configure rejected: " + e);
            }
            android.view.Surface inputSurface = null;
            if (configOk) {
                inputSurface = codec.createInputSurface();
                codec.start();
                final android.view.Surface fs = inputSurface;
                final boolean anim = animated;
                final int[] d = drawn;
                CountDownLatch ui = new CountDownLatch(1);
                final VirtualDisplay[] vdBox = new VirtualDisplay[1];
                final Presentation[] presBox = new Presentation[1];
                runOnUiThread(new Runnable() { @Override public void run() {
                    DisplayManager dm = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
                    vdBox[0] = dm.createVirtualDisplay("encprobe-" + name, W, H, 160,
                            fs, DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC);
                    BarsView view = new BarsView(ProbeActivity.this, anim, d);
                    presBox[0] = new Presentation(ProbeActivity.this, vdBox[0].getDisplay());
                    presBox[0].setContentView(view);
                    try { presBox[0].show(); } catch (WindowManager.BadTokenException e) {
                        Log.e(T, "PROBE-ERR " + name + " presentation: " + e);
                    }
                    ui.countDown();
                } });
                ui.await(10, TimeUnit.SECONDS);
                vd = vdBox[0]; pres = presBox[0];
                long deadline = System.currentTimeMillis() + RUN_MS;
                while (System.currentTimeMillis() < deadline) {
                    Thread.sleep(200);
                }
            }
            boolean gotFrame = firstOut.await(1, TimeUnit.SECONDS);
            Log.i(T, "RESULT " + name
                    + " config_ok=" + configOk
                    + (configOk ? "" : " config_err=" + configErr)
                    + " drawn=" + drawn[0]
                    + " out_buffers=" + outBuf[0]
                    + " out_bytes=" + outBytes[0]
                    + " csd=" + csd[0]
                    + " got_non_csd_frame=" + gotFrame);
            report.append("RESULT ").append(name)
                    .append(" config_ok=").append(configOk)
                    .append(configOk ? "" : " config_err=" + configErr)
                    .append(" drawn=").append(drawn[0])
                    .append(" out_buffers=").append(outBuf[0])
                    .append(" out_bytes=").append(outBytes[0])
                    .append(" csd=").append(csd[0])
                    .append(" got_non_csd_frame=").append(gotFrame).append('\n');
            appendReport(); // persist incrementally
        } catch (Exception e) {
            Log.e(T, "PROBE-ERR " + name + " fatal: " + e);
            report.append("RESULT ").append(name).append(" fatal=").append(e).append('\n');
            appendReport();
        } finally {
            final Presentation p = pres; final VirtualDisplay v = vd; final MediaCodec c = enc;
            runOnUiThread(new Runnable() { @Override public void run() {
                try { if (p != null) p.dismiss(); } catch (Exception ignored) {}
                try { if (v != null) v.release(); } catch (Exception ignored) {}
            } });
            try { Thread.sleep(300); } catch (InterruptedException ignored) {}
            try { if (c != null) { c.stop(); c.release(); } } catch (Exception ignored) {}
            codecThread.quitSafely();
        }
    }

    private void appendReport() {
        try {
            File f = new File(getExternalFilesDir(null), "probe_results.txt");
            FileOutputStream fos = new FileOutputStream(f, false);
            fos.write(report.toString().getBytes());
            fos.close();
        } catch (Exception e) {
            Log.e(T, "report write failed: " + e);
        }
    }

    // Synthetic animated color bars; animated mode invalidates every vsync.
    static class BarsView extends View implements Choreographer.FrameCallback {
        private final Paint paint = new Paint();
        private final boolean animated;
        private final int[] drawn;
        private int hue = 0;
        BarsView(Context c, boolean animated, int[] drawn) {
            super(c); this.animated = animated; this.drawn = drawn;
        }
        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            if (animated) Choreographer.getInstance().postFrameCallback(this);
        }
        @Override public void doFrame(long t) {
            hue = (hue + 3) % 360;
            invalidate();
            Choreographer.getInstance().postFrameCallback(this);
        }
        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            drawn[0]++;
            int w = getWidth() / 8;
            for (int i = 0; i < 8; i++) {
                paint.setColor(Color.HSVToColor(new float[]{(hue + i * 45) % 360, 0.8f, 0.9f}));
                c.drawRect(i * w, 0, (i + 1) * w, getHeight(), paint);
            }
            paint.setColor(Color.WHITE);
            paint.setTextSize(60);
            c.drawText("EncProbe frame " + drawn[0], 40, getHeight() - 60, paint);
        }
    }
}
