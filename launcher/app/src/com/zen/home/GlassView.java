package com.zen.home;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * "Liquid Glass" dock background in the style of iOS 26, for Android 11 (no RuntimeShader).
 *
 * The glass is clear rather than frosted: the wallpaper behind it shows through almost sharp,
 * but the rounded rim acts as a thick lens. Near the edge the backdrop is pulled in from
 * outside the panel and squeezed, with red, green and blue bent by slightly different
 * amounts (chromatic fringing), then lit by a specular rim. All of that is baked per pixel
 * on a background thread whenever the wallpaper changes; per frame we only draw one bitmap
 * plus a moving rim glint that follows the focused app.
 */
final class GlassView extends View {

    /** Lens band width as a fraction of panel height, and how far it reaches outside. */
    private static final float BAND = 0.30f;
    private static final float REFRACT = 0.95f;

    /** One background thread bakes every glass element on screen, one at a time. */
    private static final ExecutorService BAKER = Executors.newSingleThreadExecutor();

    private final float fixedRadius, density;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Paint bmpPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint tint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint overlay = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rim = new RectF();
    private final Rect dst = new Rect();

    private Bitmap glass, previous;
    private float mix = 1f, clarity = 1f, lightX = -1f;
    private int topScrimEnd, bottomScrimStart = Integer.MAX_VALUE;
    private float topScrimMax, bottomScrimMax;
    private String wallpaper;
    private long pendingFade;
    private int bakeGen;
    private ValueAnimator fadeAnim, clarityAnim, lightAnim;

    /**
     * @param radius corner radius in px, or 0 for a capsule (fully round ends)
     * @param overlayColor drawn over the glass to keep text on it readable (0 for none)
     */
    GlassView(Context ctx, float radius, float density, int overlayColor) {
        super(ctx);
        this.fixedRadius = radius;
        this.density = density;
        tint.setColor(0x59101014);
        overlay.setColor(overlayColor);
        glint.setStyle(Paint.Style.STROKE);
        glint.setStrokeWidth(Math.max(1.5f, 1.4f * density));
    }

    private float radius() {
        return fixedRadius > 0 ? fixedRadius : getHeight() / 2f;
    }

    /** New wallpaper (a Wallpapers.Entry path); re-bakes the glass and crossfades. */
    void setWallpaper(String path, long fadeMs) {
        wallpaper = path;
        pendingFade = fadeMs;
        bake();
    }

    /**
     * The dark gradients drawn over the wallpaper (top one fading out by topEnd, bottom one
     * starting at bottomStart), so the glass refracts what is actually visible behind it.
     */
    void setScrims(int topEnd, float topMax, int bottomStart, float bottomMax) {
        topScrimEnd = topEnd;
        topScrimMax = topMax;
        bottomScrimStart = bottomStart;
        bottomScrimMax = bottomMax;
    }

    /** 1 = clear glass over the wallpaper (home); 0 = dark smoked glass (while the grid is open). */
    void setClarity(float target) {
        if (clarityAnim != null) clarityAnim.cancel();
        clarityAnim = ValueAnimator.ofFloat(clarity, target).setDuration(320);
        clarityAnim.addUpdateListener(a -> { clarity = (float) a.getAnimatedValue(); invalidate(); });
        clarityAnim.start();
    }

    /** Moves the rim's specular glint to sit above x (panel coordinates), like light on glass. */
    void setLight(float x) {
        if (lightX < 0) { lightX = x; invalidate(); return; }
        if (lightAnim != null) lightAnim.cancel();
        lightAnim = ValueAnimator.ofFloat(lightX, x).setDuration(380);
        lightAnim.setInterpolator(new DecelerateInterpolator(2f));
        lightAnim.addUpdateListener(a -> { lightX = (float) a.getAnimatedValue(); invalidate(); });
        lightAnim.start();
    }

    /** No preferred size: glass takes the size of whatever it sits behind (e.g. a pill's label). */
    @Override protected void onMeasure(int w, int h) {
        setMeasuredDimension(resolveSize(0, w), resolveSize(0, h));
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        if (w > 0 && h > 0) post(this::bake);
    }

    // ------------------------------------------------------------------ drawing

    @Override protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        float radius = radius();
        dst.set(0, 0, w, h);
        if (clarity < 1f) c.drawRoundRect(0, 0, w, h, radius, radius, tint);
        int a = (int) (255 * clarity);
        if (previous != null && mix < 1f) {
            bmpPaint.setAlpha(a);
            c.drawBitmap(previous, null, dst, bmpPaint);
        }
        if (glass != null) {
            bmpPaint.setAlpha((int) (a * (previous != null ? mix : 1f)));
            c.drawBitmap(glass, null, dst, bmpPaint);
        }
        if (overlay.getColor() != 0) c.drawRoundRect(0, 0, w, h, radius, radius, overlay);
        // Moving glint: brightest on the rim right above the focused app.
        float lx = lightX < 0 ? w * 0.3f : lightX;
        float span = Math.max(w * 0.22f, Math.min(160 * density, w * 0.6f));
        glint.setShader(new LinearGradient(lx - span, 0, lx + span, 0,
                new int[] { 0x00FFFFFF, 0xE6FFFFFF, 0x00FFFFFF }, null, Shader.TileMode.CLAMP));
        float inset = glint.getStrokeWidth() / 2f;
        rim.set(inset, inset, w - inset, h - inset);
        c.drawRoundRect(rim, radius - inset, radius - inset, glint);
    }

    // ------------------------------------------------------------------ baking

    private void bake() {
        final int w = getWidth(), h = getHeight();
        final String path = wallpaper;
        if (w == 0 || h == 0 || path == null) return;
        // The panel's resting place on screen (layout position, ignoring scroll animations).
        int x = 0, y = 0;
        for (View v = this; v != null && v != getRootView(); v = (View) v.getParent()) {
            x += v.getLeft();
            y += v.getTop();
        }
        final int sx = x, sy = y, screenW = getRootView().getWidth(), screenH = getRootView().getHeight();
        final long fade = pendingFade;
        final int gen = ++bakeGen;
        final int tEnd = topScrimEnd, bStart = bottomScrimStart;
        final float tMax = topScrimMax, bMax = bottomScrimMax, r = radius();
        final Context app = getContext().getApplicationContext();
        BAKER.execute(() -> {
            Bitmap out = render(app, path, sx, sy, w, h, screenW, screenH, tEnd, tMax, bStart, bMax, r, density);
            if (out == null) return;
            ui.post(() -> {
                if (gen != bakeGen) { out.recycle(); return; }
                show(out, fade);
            });
        });
    }

    private void show(Bitmap b, long fadeMs) {
        if (fadeAnim != null) fadeAnim.cancel();
        if (previous != null && previous != glass) previous.recycle();
        previous = glass;
        glass = b;
        if (previous == null || fadeMs <= 0) {
            mix = 1f;
            invalidate();
            return;
        }
        mix = 0f;
        fadeAnim = ValueAnimator.ofFloat(0f, 1f).setDuration(fadeMs);
        fadeAnim.addUpdateListener(a -> { mix = (float) a.getAnimatedValue(); invalidate(); });
        fadeAnim.start();
    }

    /**
     * Renders the glass for a panel of w x h at (px, py) on a screenW x screenH screen.
     * Uses a signed-distance field of the rounded rectangle to find each pixel's depth from
     * the rim and the rim's normal, then refracts the backdrop along that normal.
     */
    static Bitmap render(Context ctx, String path, int px, int py, int w, int h,
                         int screenW, int screenH, int topEnd, float topMax,
                         int bottomStart, float bottomMax, float radius, float density) {
        int band = Math.max(8, Math.round(h * BAND));
        int sw = w + 2 * band, sh = h + 2 * band;
        int[] src = backdrop(ctx, path, px - band, py - band, sw, sh, screenW, screenH);
        if (src == null) return null;
        for (int row = 0; row < sh; row++) {
            int screenY = py - band + row;
            float k = 1f;
            if (screenY < topEnd) k *= 1f - topMax * (1f - Math.max(0, screenY) / (float) topEnd);
            if (screenY > bottomStart) {
                k *= 1f - bottomMax * Math.min(1f, (screenY - bottomStart) / (float) (screenH - bottomStart));
            }
            if (k >= 1f) continue;
            for (int i = row * sw, end = i + sw; i < end; i++) {
                int p = src[i];
                src[i] = 0xFF000000 | ((int) (((p >> 16) & 0xFF) * k) << 16)
                        | ((int) (((p >> 8) & 0xFF) * k) << 8) | (int) ((p & 0xFF) * k);
            }
        }
        blur(src, sw, sh, Math.max(1, Math.round(density)));
        blur(src, sw, sh, Math.max(1, Math.round(density)));

        int[] out = new int[w * h];
        float cx = w / 2f, cy = h / 2f;
        float hx = cx - radius, hy = cy - radius;
        float lightNx = -0.5f, lightNy = -0.866f; // key light from the upper left
        float specW = 1.6f * density, glowW = 10f * density, shadeW = 7f * density;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float dx = x + 0.5f - cx, dy = y + 0.5f - cy;
                float qx = Math.abs(dx) - hx, qy = Math.abs(dy) - hy;
                float ox = Math.max(qx, 0f), oy = Math.max(qy, 0f);
                float outside = (float) Math.sqrt(ox * ox + oy * oy);
                float sd = outside + Math.min(Math.max(qx, qy), 0f) - radius;
                float edgeA = Math.max(0f, Math.min(1f, 0.5f - sd));
                if (edgeA <= 0f) continue;
                float t = -sd; // depth inside the glass

                // Outward normal of the nearest rim point.
                float nx, ny;
                if (qx > 0 && qy > 0 && outside > 0) { nx = ox / outside; ny = oy / outside; }
                else if (qx > qy) { nx = 1f; ny = 0f; } else { nx = 0f; ny = 1f; }
                if (dx < 0) nx = -nx;
                if (dy < 0) ny = -ny;

                // Lens: near the rim, look outward and squeeze what's outside into the edge.
                float f = t < band ? 1f - t / band : 0f;
                float d = band * REFRACT * f * f;
                float bx = x + band, by = y + band;
                int r = channel(src, sw, sh, bx + nx * d * 1.10f, by + ny * d * 1.10f, 16);
                int g = channel(src, sw, sh, bx + nx * d, by + ny * d, 8);
                int b = channel(src, sw, sh, bx + nx * d * 0.90f, by + ny * d * 0.90f, 0);

                // Vibrancy: slight lift and saturation, as Liquid Glass does.
                float lum = 0.299f * r + 0.587f * g + 0.114f * b;
                float fr = lum + (r - lum) * 1.15f + 8f;
                float fg = lum + (g - lum) * 1.15f + 8f;
                float fb = lum + (b - lum) * 1.15f + 8f;

                // Lighting: specular rim (strongest facing the light, softer on the far
                // corner), a soft inner glow along the edge, and a faint shade at the bottom.
                float facing = nx * lightNx + ny * lightNy;
                float spec = t < specW ? (1f - t / specW) * (0.35f + 0.65f * Math.max(0f, facing)
                        + 0.35f * Math.max(0f, -facing)) : 0f;
                float glow = t < glowW ? (1f - t / glowW) * (1f - t / glowW) * 0.16f : 0f;
                float light = Math.min(1f, spec * 0.9f + glow);
                fr += (255f - fr) * light;
                fg += (255f - fg) * light;
                fb += (255f - fb) * light;
                if (ny > 0.6f && t < shadeW) {
                    float s = 1f - 0.12f * (1f - t / shadeW);
                    fr *= s; fg *= s; fb *= s;
                }
                int a = Math.round(255 * edgeA);
                out[y * w + x] = (a << 24) | (clamp(fr) << 16) | (clamp(fg) << 8) | clamp(fb);
            }
        }
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        bmp.setPixels(out, 0, w, 0, 0, w, h);
        return bmp;
    }

    /**
     * Wallpaper pixels behind a screen rectangle, at screen resolution, clamped at edges.
     * Decodes a quarter-size copy (960x540 for a 4K photo): the JPEG decoder scales inside the
     * DCT, so no full-size buffers are ever allocated. Big one-off native allocations here were
     * being kept by the allocator (~55 MB) for the life of the process. The glass softens its
     * backdrop anyway, so the lower resolution doesn't show.
     */
    private static int[] backdrop(Context ctx, String path, int x, int y, int w, int h,
                                  int screenW, int screenH) {
        Bitmap small;
        try (InputStream in = Wallpapers.open(ctx, path)) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = 4;
            small = BitmapFactory.decodeStream(in, null, o);
        } catch (Exception e) {
            return null;
        }
        if (small == null) return null;
        float sx = small.getWidth() / (float) screenW, sy = small.getHeight() / (float) screenH;
        Rect want = new Rect(Math.round(x * sx), Math.round(y * sy),
                Math.round((x + w) * sx), Math.round((y + h) * sy));
        Rect have = new Rect(want);
        if (!have.intersect(0, 0, small.getWidth(), small.getHeight())) { small.recycle(); return null; }
        Bitmap full = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(full);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        // Stretch the available part over everything first (fills any off-screen margin),
        // then draw it again exactly where it belongs.
        c.drawBitmap(small, have, new RectF(0, 0, w, h), p);
        c.drawBitmap(small, have, new RectF((have.left - want.left) / sx, (have.top - want.top) / sy,
                (have.right - want.left) / sx, (have.bottom - want.top) / sy), p);
        small.recycle();
        int[] px = new int[w * h];
        full.getPixels(px, 0, w, 0, 0, w, h);
        full.recycle();
        return px;
    }

    private static int channel(int[] px, int w, int h, float fx, float fy, int shift) {
        fx = Math.max(0f, Math.min(w - 1.001f, fx - 0.5f));
        fy = Math.max(0f, Math.min(h - 1.001f, fy - 0.5f));
        int x0 = (int) fx, y0 = (int) fy;
        float ax = fx - x0, ay = fy - y0;
        int i = y0 * w + x0;
        float top = ((px[i] >> shift) & 0xFF) * (1 - ax) + ((px[i + 1] >> shift) & 0xFF) * ax;
        float bot = ((px[i + w] >> shift) & 0xFF) * (1 - ax) + ((px[i + w + 1] >> shift) & 0xFF) * ax;
        return Math.round(top * (1 - ay) + bot * ay);
    }

    private static int clamp(float v) {
        return v < 0 ? 0 : v > 255 ? 255 : (int) v;
    }

    /** Separable box blur, radius r, in place. */
    private static void blur(int[] px, int w, int h, int r) {
        int[] tmp = new int[px.length];
        pass(px, tmp, w, h, r, true);
        pass(tmp, px, h, w, r, false);
    }

    private static void pass(int[] in, int[] out, int len, int lines, int r, boolean horizontal) {
        int div = 2 * r + 1;
        for (int l = 0; l < lines; l++) {
            int sr = 0, sg = 0, sb = 0;
            for (int i = -r; i <= r; i++) {
                int p = in[idx(l, Math.max(0, Math.min(len - 1, i)), len, lines, horizontal)];
                sr += (p >> 16) & 0xFF; sg += (p >> 8) & 0xFF; sb += p & 0xFF;
            }
            for (int i = 0; i < len; i++) {
                out[idx(l, i, len, lines, horizontal)] = 0xFF000000 | ((sr / div) << 16) | ((sg / div) << 8) | (sb / div);
                int pOut = in[idx(l, Math.max(0, i - r), len, lines, horizontal)];
                int pIn = in[idx(l, Math.min(len - 1, i + r + 1), len, lines, horizontal)];
                sr += ((pIn >> 16) & 0xFF) - ((pOut >> 16) & 0xFF);
                sg += ((pIn >> 8) & 0xFF) - ((pOut >> 8) & 0xFF);
                sb += (pIn & 0xFF) - (pOut & 0xFF);
            }
        }
    }

    private static int idx(int line, int i, int len, int lines, boolean horizontal) {
        return horizontal ? line * len + i : i * lines + line;
    }
}
