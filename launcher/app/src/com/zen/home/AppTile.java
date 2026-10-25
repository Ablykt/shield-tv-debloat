package com.zen.home;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Apple TV-style app tile. Focus has no border: the tile springs up in size, lifts onto a
 * deeper shadow, catches a soft glare that sweeps across it, and settles out of a slight 3D
 * tilt toward the direction the remote moved. Grid tiles also show their name underneath;
 * the dock's focused name is shown above the dock by HomeActivity.
 */
final class AppTile extends LinearLayout {

    static final float FOCUS_SCALE = 1.14f;
    private static final float DOCK_SCALE = 1.17f;
    private static final float TILT_DEG = 7f;

    /** Set by HomeActivity just before focus moves: -1 moved left, +1 moved right, 0 other. */
    static int pendingTilt;

    final String pkg;
    final String name;
    final boolean inDock;
    private final float scale, density;
    private final FrameLayout card;
    private final View glare;
    private final TextView label;
    private ObjectAnimator wiggle;

    AppTile(Context ctx, AppRepo.App app, boolean inDock, int w, int h, float density) {
        super(ctx);
        this.pkg = app.pkg;
        this.name = app.label;
        this.inDock = inDock;
        this.density = density;
        this.scale = inDock ? DOCK_SCALE : FOCUS_SCALE;
        setOrientation(VERTICAL);
        setClipChildren(false);
        setClipToPadding(false);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
        setContentDescription(app.label);

        final float radius = (inDock ? 10 : 9) * density;
        card = new FrameLayout(ctx);
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
            }
        });
        card.setClipToOutline(true);
        card.setElevation(2 * density);
        card.setCameraDistance(9000 * density);

        ImageView art = new ImageView(ctx);
        art.setScaleType(ImageView.ScaleType.FIT_XY);
        art.setImageBitmap(app.art);
        card.addView(art, new FrameLayout.LayoutParams(-1, -1));

        // Specular highlight, brightest at the top-left like light on glass.
        glare = new View(ctx);
        glare.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[] { 0x55FFFFFF, 0x14FFFFFF, 0x00FFFFFF }));
        glare.setAlpha(0f);
        card.addView(glare, new FrameLayout.LayoutParams(-1, -1));

        LayoutParams cl = new LayoutParams(w, h);
        if (inDock) {
            int g = slotGap(density);
            cl.setMargins(g, g, g, g);
        }
        addView(card, cl);

        label = new TextView(ctx);
        label.setText(app.label);
        label.setTextColor(0xFFFFFFFF);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        label.setGravity(Gravity.CENTER_HORIZONTAL);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setShadowLayer(6 * density, 0, density, 0xAA000000);
        label.setAlpha(0f);
        LayoutParams lp = new LayoutParams(w, LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) (8 * density);
        if (!inDock) addView(label, lp);

        setOnFocusChangeListener((v, focused) -> animateFocus(focused));
    }

    /** Breathing room around a dock tile so it can grow without touching the glass edge. */
    static int slotGap(float density) {
        return Math.round(4 * density);
    }

    private void animateFocus(boolean focused) {
        int tilt = pendingTilt;
        pendingTilt = 0;
        if (focused) {
            if (tilt != 0) card.setRotationY(-tilt * TILT_DEG);
            card.animate().scaleX(scale).scaleY(scale).rotationY(0f)
                    .translationZ((inDock ? 16 : 14) * density)
                    .setDuration(320).setInterpolator(new OvershootInterpolator(1.6f)).start();
            // Glare sweeps in from the side the remote came from, then rests.
            int w = card.getLayoutParams().width;
            glare.setTranslationX(tilt == 0 ? -w * 0.25f : -tilt * w * 0.5f);
            glare.animate().alpha(1f).translationX(0f).setDuration(420)
                    .setInterpolator(new DecelerateInterpolator(2f)).start();
        } else {
            card.animate().scaleX(1f).scaleY(1f).rotationY(0f).translationZ(0f)
                    .setDuration(180).setInterpolator(new DecelerateInterpolator(2f)).start();
            glare.animate().alpha(0f).setDuration(150).start();
        }
        float lift = focused ? card.getLayoutParams().height * (scale - 1f) / 2f : 0f;
        label.animate().alpha(focused ? 1f : 0f).translationY(lift).setDuration(200).start();
    }

    void setWiggling(boolean on) {
        if (on) {
            if (wiggle != null) return;
            wiggle = ObjectAnimator.ofFloat(card, View.ROTATION, -1.6f, 1.6f);
            wiggle.setDuration(130);
            wiggle.setRepeatMode(ValueAnimator.REVERSE);
            wiggle.setRepeatCount(ValueAnimator.INFINITE);
            wiggle.start();
        } else if (wiggle != null) {
            wiggle.cancel();
            wiggle = null;
            card.setRotation(0f);
        }
    }
}
