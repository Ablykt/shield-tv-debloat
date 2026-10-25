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
import java.util.List;

/**
 * Full-screen rotating wallpaper drawn into its own surface at the panel's native
 * resolution (4K on a 4K TV), even though the Shield renders app UI at 1080p.
 * Bitmaps are decoded straight into GPU memory (Bitmap.Config.HARDWARE), and the
 * render thread only wakes for the crossfade, so the launcher idles at ~0% CPU.
 * Photos come from Wallpapers: the weekly download, or the bundled set.
 */
public final class WallpaperView extends SurfaceView implements SurfaceHolder.Callback {

    public interface Listener { void onWallpaperShown(Wallpapers.Entry wallpaper); }

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
    private final SharedPreferences prefs;
    /** Render thread only (after the constructor). A new weekly set waits in pending. */
    private List<Wallpapers.Entry> walls;
    private List<Wallpapers.Entry> pending;
    private long loadedAt;

    private Listener listener;
    private boolean surfaceReady, running, transitioning, settle, previewStale;
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
        loadedAt = Wallpapers.fetchedAt(ctx);
        walls = Wallpapers.load(ctx);
        index = Math.max(0, prefs.getInt("wall_index", 0)) % Math.max(1, walls.size());
        nextChangeAt = prefs.getLong("wall_next_at_wall", 0);
    }

    public void setListener(Listener l) { listener = l; }
    /**
     * Picks up a new weekly set (call on start and when WallpaperFetcher reports one). It takes
     * over at the next rotation, starting with its first photo, so the current one isn't cut short.
     */
    public void reloadIfChanged() {
        long at = Wallpapers.fetchedAt(getContext());
        if (at == loadedAt) return;
        loadedAt = at;
        final Context ctx = getContext().getApplicationContext();
        render.post(() -> {
            List<Wallpapers.Entry> l = Wallpapers.load(ctx);
            if (!l.isEmpty()) pending = l;
        });
    }

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
        long fade = preview != null ? RESHARPEN_MS : FIRST_FADE_MS;
        if (!fadeTo(index, fade, false, false) && pending != null) {
            // The photo we were on is gone (two refreshes while Home was hidden): switch sets now.
            walls = pending;
            pending = null;
            fadeTo(0, fade, true, false);
        }
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
            if (now >= nextChangeAt && cur != null && surfaceReady && (walls.size() > 1 || pending != null)) {
                int target = walls.isEmpty() ? 0 : (index + 1) % walls.size();
                if (pending != null) {
                    walls = pending;
                    pending = null;
                    target = 0;
                }
                if (!fadeTo(target, FADE_MS, true, true)) {
                    index = target; // unreadable photo: skip it
                    render.postDelayed(this, 10_000);
                }
            } else {
                render.postDelayed(this, Math.max(1000, nextChangeAt - now));
            }
        }
    };

    private void scheduleNext(long now) {
        nextChangeAt = now + ROTATE_MS;
        prefs.edit().putLong("wall_next_at_wall", nextChangeAt).apply();
    }

    private boolean fadeTo(int newIndex, long duration, boolean advance, boolean settleZoom) {
        if (walls.isEmpty()) return false;
        newIndex %= walls.size();
        Bitmap b = decode(walls.get(newIndex), 0);
        if (b == null) return false;
        boolean changed = advance || newIndex != index || preview == null;
        previewStale |= changed;
        next = b;
        index = newIndex;
        transitioning = true;
        settle = settleZoom;
        fadeDuration = duration;
        fadeStart = SystemClock.uptimeMillis();
        if (advance) prefs.edit().putInt("wall_index", index).apply();
        if (changed) {
            final Wallpapers.Entry shown = walls.get(index);
            ui.post(() -> { if (listener != null) listener.onWallpaperShown(shown); });
        }
        render.post(frame);
        return true;
    }

    private final Runnable frame = new Runnable() {
        @Override public void run() {
            drawFrame();
            if (fadeProgress() < 1f) {
                render.postDelayed(this, 16);
            } else {
                boolean newPhoto = previewStale;
                previewStale = false;
                if (cur != null && cur != preview) cur.recycle();
                cur = next;
                next = null;
                transitioning = false;
                drawFrame();
                if (newPhoto) {
                    if (preview != null) preview.recycle();
                    preview = decode(walls.get(index), PREVIEW_SAMPLE);
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
    private Bitmap decode(Wallpapers.Entry wall, int sample) {
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
        try (InputStream in = Wallpapers.open(getContext(), wall.path)) {
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
