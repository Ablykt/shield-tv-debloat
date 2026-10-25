package com.zen.home;

import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.IBinder;
import android.os.Process;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Runs in its own short-lived ":tiles" process. Reading every app's banner opens that app's
 * resource tables, which the framework keeps in native memory (~55 MB for 55 apps) for the
 * life of the process. Doing it here and exiting hands all of that back to the OS; the home
 * screen only ever reads the small finished tiles from the cache.
 */
public final class TileLoaderService extends Service {

    static final String ACTION_READY = "com.zen.home.TILES_READY";
    static final String EXTRA_W = "w", EXTRA_H = "h";

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        final int w = intent != null ? intent.getIntExtra(EXTRA_W, 296) : 296;
        final int h = intent != null ? intent.getIntExtra(EXTRA_H, 166) : 166;
        new Thread(() -> {
            try {
                write(AppRepo.load(getApplicationContext(), w, h));
                sendBroadcast(new Intent(ACTION_READY).setPackage(getPackageName()));
            } catch (Exception ignored) {
            } finally {
                stopSelf();
                Process.killProcess(Process.myPid());
            }
        }, "zen-tiles").start();
        return START_NOT_STICKY;
    }

    private void write(List<AppRepo.App> apps) throws Exception {
        File dir = TileCache.dir(this);
        dir.mkdirs();
        StringBuilder index = new StringBuilder();
        for (AppRepo.App a : apps) {
            File png = new File(dir, a.pkg + ".png");
            File tmp = new File(dir, a.pkg + ".png.tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                a.art.compress(Bitmap.CompressFormat.PNG, 100, out);
            }
            if (!tmp.renameTo(png)) continue;
            index.append(a.pkg).append('\t')
                 .append(a.component.flattenToString()).append('\t')
                 .append(a.label.replace('\t', ' ').replace('\n', ' ')).append('\n');
        }
        File tmp = new File(dir, "index.tmp");
        try (Writer wr = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
            wr.write(index.toString());
        }
        tmp.renameTo(new File(dir, "index.txt"));
        // Drop tiles of apps that are gone.
        File[] files = dir.listFiles();
        if (files != null) {
            String all = "\n" + index;
            for (File f : files) {
                String n = f.getName();
                if (n.endsWith(".png") && !all.contains("\n" + n.substring(0, n.length() - 4) + "\t")) f.delete();
            }
        }
    }
}
