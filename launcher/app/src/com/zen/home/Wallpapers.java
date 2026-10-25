package com.zen.home;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The photos Home rotates through: this week's download (see WallpaperFetcher) when there is
 * one, otherwise the set bundled in the APK. The download lives in files/wallpapers/ with an
 * index.txt of "file|title|credit" lines; its modification time is when the set was fetched.
 */
final class Wallpapers {

    static final class Entry {
        /** "asset:wallpapers/00.jpg" or an absolute file path. */
        final String path, title, credit;
        Entry(String path, String title, String credit) {
            this.path = path;
            this.title = title;
            this.credit = credit;
        }
    }

    private static final String ASSET = "asset:";
    /** Fewer downloaded photos than this and the bundled set is used instead. */
    static final int MIN_SET = 4;

    private Wallpapers() { }

    static File dir(Context c) { return new File(c.getFilesDir(), "wallpapers"); }
    static File index(Context c) { return new File(dir(c), "index.txt"); }

    /** When the current download was fetched (0 = never). */
    static long fetchedAt(Context c) { return index(c).lastModified(); }

    static List<Entry> load(Context c) {
        List<Entry> out = readIndex(c);
        if (out.size() >= MIN_SET) return out;
        out.clear();
        try {
            String[] names = c.getAssets().list("wallpapers");
            List<String[]> captions = readCaptions(c);
            if (names != null) {
                Arrays.sort(names);
                int i = 0;
                for (String n : names) {
                    if (!n.endsWith(".jpg")) continue;
                    String[] cap = i < captions.size() ? captions.get(i) : new String[] { "", "" };
                    out.add(new Entry(ASSET + "wallpapers/" + n, cap[0], cap[1]));
                    i++;
                }
            }
        } catch (IOException ignored) { }
        return out;
    }

    /** Downloaded entries whose files still exist. */
    static List<Entry> readIndex(Context c) {
        List<Entry> out = new ArrayList<>();
        File dir = dir(c);
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new FileInputStream(index(c)), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.split("\\|", 3);
                File f = new File(dir, p[0]);
                if (p[0].isEmpty() || !f.isFile()) continue;
                out.add(new Entry(f.getAbsolutePath(), p.length > 1 ? p[1] : "", p.length > 2 ? p[2] : ""));
            }
        } catch (IOException ignored) { }
        return out;
    }

    static InputStream open(Context c, String path) throws IOException {
        if (path.startsWith(ASSET)) return c.getAssets().open(path.substring(ASSET.length()));
        return new FileInputStream(path);
    }

    private static List<String[]> readCaptions(Context c) {
        List<String[]> out = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                c.getAssets().open("wallpapers/captions.txt"), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.split("\\|", 2);
                out.add(new String[] { p[0], p.length > 1 ? p[1] : "" });
            }
        } catch (IOException ignored) { }
        return out;
    }
}
