package com.zen.home;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Full-screen rotating wallpaper drawn into its own surface at the panel's native
 * resolution (4K on a 4K TV), even though the Shield renders app UI at 1080p.
 * Bitmaps are decoded straight into GPU memory (Bitmap.Config.HARDWARE), and the
 * render thread only wakes for the crossfade, so the launcher idles at ~0% CPU.
 */
public final class WallpaperView extends SurfaceView implements SurfaceHolder.Callback {

    public interface Listener { void onWallpaperShown(int index); }

    static final long ROTATE_MS = 5 * 60 * 1000L;
    private static final long FADE_MS = 1800;
    private static final long FIRST_FADE_MS = 500;
    private static final long RESHARPEN_MS = 280;
    private static final int PREVIEW_SAMPLE = 4; // 3840x2160 -> 960x540, ~2 MB
    private static final float SETTLE_ZOOM = 0.035f;

    private final Object lock = new Object();
    private final HandlerThread thread = new HandlerThread("zen-wallpaper");
    private final Handler render;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final RectF dst = new RectF();
    private final List<String> files = new ArrayList<>();
    private final SharedPreferences prefs;

    private Listener listener;
    private boolean surfaceReady, running, transitioning, settle;
    private int surfW, surfH;
    private Bitmap cur, next;
    /** Small software copy of the current photo, drawn instantly when Home comes back. */
    private Bitmap preview;
    private int index;
    private long fadeStart, fadeDuration, nextChangeAt;

    public WallpaperView(Context ctx, SharedPreferences prefs) {
        super(ctx);
        this.prefs = prefs;
        getHolder().setFormat(PixelFormat.OPAQUE);
        getHolder().addCallback(this);
        thread.start();
        render = new Handler(thread.getLooper());
        try {
            String[] list = ctx.getAssets().list("wallpapers");
            if (list != null) {
                Arrays.sort(list);
                for (String f : list) if (f.endsWith(".jpg")) files.add(f);
            }
        } catch (IOException ignored) { }
        index = Math.max(0, prefs.getInt("wall_index", 0)) % Math.max(1, files.size());
        nextChangeAt = prefs.getLong("wall_next_at_wall", 0);
    }

    public void setListener(Listener l) { listener = l; }
    public int count() { return files.size(); }
    public String file(int i) { return files.isEmpty() ? "" : files.get(i % files.size()); }

    /** Render buffer size; pass the physical panel size for native 4K. */
    public void setBufferSize(int w, int h) { getHolder().setFixedSize(w, h); }

    public void start() {
        running = true;
        render.removeCallbacks(tick);
        render.post(this::restoreIfNeeded);
        render.post(tick);
    }

    public void stop() {
        running = false;
        render.removeCallbacks(tick);
        releaseIfHidden();
    }

    /**
     * Free the 4K GPU bitmap while Home is in the background; only the small preview stays.
     * The surface keeps showing its last frame if it survives, so nothing visible is lost.
     */
    public void releaseIfHidden() {
        render.post(() -> {
            if (running) return;
            render.removeCallbacks(frame);
            if (next != null) { next.recycle(); next = null; }
            transitioning = false;
            if (cur != null && cur != preview) cur.recycle();
            cur = null;
        });
    }

    // ---- SurfaceHolder.Callback ----

    @Override public void surfaceCreated(SurfaceHolder holder) {
        synchronized (lock) { surfaceReady = true; }
    }

    @Override public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
        synchronized (lock) { surfW = w; surfH = h; }
        render.post(() -> {
            if (!restoreIfNeeded()) drawFrame();
        });
    }

    /** Returning home: show the low-res preview at once, then sharpen to 4K. */
    private boolean restoreIfNeeded() {
        if (cur != null || transitioning) return false;
        synchronized (lock) { if (!surfaceReady || surfW == 0) return false; }
        cur = preview;
        drawFrame();
        fadeTo(index, preview != null ? RESHARPEN_MS : FIRST_FADE_MS, false, false);
        return true;
    }

    @Override public void surfaceDestroyed(SurfaceHolder holder) {
        // Blocks until the render thread has posted any frame it is holding.
        synchronized (lock) { surfaceReady = false; }
    }

    // ---- rotation ----

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            if (transitioning) return;
            long now = System.currentTimeMillis();
            if (nextChangeAt == 0) scheduleNext(now);
            if (now >= nextChangeAt && cur != null && surfaceReady && files.size() > 1) {
                fadeTo((index + 1) % files.size(), FADE_MS, true, true);
            } else {
                render.postDelayed(this, Math.max(1000, nextChangeAt - now));
            }
        }
    };

    private void scheduleNext(long now) {
        nextChangeAt = now + ROTATE_MS;
        prefs.edit().putLong("wall_next_at_wall", nextChangeAt).apply();
    }

    private void fadeTo(int newIndex, long duration, boolean advance, boolean settleZoom) {
        if (files.isEmpty()) return;
        Bitmap b = decode(files.get(newIndex), 0);
        if (b == null) return;
        boolean changed = advance || newIndex != index || preview == null;
        next = b;
        index = newIndex;
        transitioning = true;
        settle = settleZoom;
        fadeDuration = duration;
        fadeStart = SystemClock.uptimeMillis();
        if (advance) prefs.edit().putInt("wall_index", index).apply();
        if (changed) {
            final int shown = index;
            ui.post(() -> { if (listener != null) listener.onWallpaperShown(shown); });
        }
        render.post(frame);
    }

    private final Runnable frame = new Runnable() {
        @Override public void run() {
            drawFrame();
            if (fadeProgress() < 1f) {
                render.postDelayed(this, 16);
            } else {
                boolean newPhoto = settle || preview == null;
                if (cur != null && cur != preview) cur.recycle();
                cur = next;
                next = null;
                transitioning = false;
                drawFrame();
                if (newPhoto) {
                    if (preview != null) preview.recycle();
                    preview = decode(files.get(index), PREVIEW_SAMPLE);
                    scheduleNext(System.currentTimeMillis());
                }
                render.removeCallbacks(tick);
                if (running) render.postDelayed(tick, Math.max(1000, nextChangeAt - System.currentTimeMillis()));
            }
        }
    };

    private float fadeProgress() {
        if (!transitioning) return 1f;
        float t = (SystemClock.uptimeMillis() - fadeStart) / (float) fadeDuration;
        return Math.max(0f, Math.min(1f, t));
    }

    /** sample == 0: full-quality GPU bitmap for the panel; otherwise a small software preview. */
    private Bitmap decode(String name, int sample) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        if (sample > 0) {
            o.inPreferredConfig = Bitmap.Config.ARGB_8888;
            o.inSampleSize = sample;
        } else {
            o.inPreferredConfig = Bitmap.Config.HARDWARE;
            int w;
            synchronized (lock) { w = surfW; }
            o.inSampleSize = (w > 0 && w <= 1920) ? 2 : 1;
        }
        try (InputStream in = getContext().getAssets().open("wallpapers/" + name)) {
            return BitmapFactory.decodeStream(in, null, o);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private void drawFrame() {
        synchronized (lock) {
            if (!surfaceReady || surfW == 0) return;
            Canvas c;
            try {
                c = getHolder().lockHardwareCanvas();
            } catch (IllegalStateException | IllegalArgumentException e) {
                return;
            }
            if (c == null) return;
            try {
                c.drawColor(Color.BLACK);
                if (cur != null) {
                    paint.setAlpha(255);
                    dst.set(0, 0, surfW, surfH);
                    c.drawBitmap(cur, null, dst, paint);
                }
                if (next != null) {
                    float p = fadeProgress();
                    float e = 1f - (1f - p) * (1f - p); // ease-out
                    float zoom = settle ? 1f + SETTLE_ZOOM * (1f - e) : 1f;
                    float dw = surfW * (zoom - 1f) / 2f, dh = surfH * (zoom - 1f) / 2f;
                    dst.set(-dw, -dh, surfW + dw, surfH + dh);
                    paint.setAlpha((int) (255 * e));
                    c.drawBitmap(next, null, dst, paint);
                }
            } finally {
                getHolder().unlockCanvasAndPost(c);
            }
        }
    }
}
