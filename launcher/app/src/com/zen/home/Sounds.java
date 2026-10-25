package com.zen.home;

import android.content.ContentResolver;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.SoundPool;
import android.provider.Settings;

/**
 * Apple TV-style navigation feedback: a soft tick when focus moves, a rounder tock when
 * something opens, and a muted bump at the end of a row. The sounds are synthesized by
 * tools/make_sounds.py. They follow the SHIELD's own "System sounds" switch
 * (Settings > Device Preferences > Sound), so turning that off silences them too.
 */
final class Sounds {

    private static final int RATE = 48000;

    private final SoundPool pool;
    private final ContentResolver resolver;
    private final AudioAttributes attrs = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build();
    private final int tick, select, bump;
    private AudioTrack keepAlive;

    Sounds(Context ctx) {
        resolver = ctx.getContentResolver();
        pool = new SoundPool.Builder().setMaxStreams(3).setAudioAttributes(attrs).build();
        tick = pool.load(ctx, R.raw.tick, 1);
        select = pool.load(ctx, R.raw.select, 1);
        bump = pool.load(ctx, R.raw.bump, 1);
    }

    // Effective loudness = file peak x volume: tick and bump 0.45 x 0.667 = 30%, select 0.50 x 0.8 = 40%.
    void tick() { play(tick, 0.667f); }
    void select() { play(select, 0.8f); }
    void bump() { play(bump, 0.667f); }

    /**
     * Keeps the HDMI audio link awake while Home is on screen. Android puts the output in
     * standby 3 s after the last sound, and TVs and soundbars take a few hundred ms to unmute
     * HDMI audio again, which swallows a 30 ms click entirely. A looping buffer of digital
     * silence keeps the stream running, so every click is heard. Stopped as soon as an app opens.
     */
    void wake() {
        if (keepAlive != null || !enabled()) return;
        int frames = RATE / 10;
        try {
            keepAlive = new AudioTrack.Builder()
                    .setAudioAttributes(attrs)
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(frames * 2)
                    .build();
            keepAlive.write(new short[frames], 0, frames);
            keepAlive.setLoopPoints(0, frames, -1);
            keepAlive.play();
        } catch (RuntimeException e) {
            sleep();
        }
    }

    void sleep() {
        if (keepAlive == null) return;
        try {
            keepAlive.stop();
        } catch (RuntimeException ignored) { }
        keepAlive.release();
        keepAlive = null;
    }

    void release() {
        sleep();
        pool.release();
    }

    private boolean enabled() {
        return Settings.System.getInt(resolver, Settings.System.SOUND_EFFECTS_ENABLED, 1) != 0;
    }

    private void play(int id, float volume) {
        if (!enabled()) return;
        if (keepAlive == null) wake();
        pool.play(id, volume, volume, 1, 0, 1f);
    }
}
