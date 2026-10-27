package dev.noirly.tv.photos;

import android.content.Context;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The picked photos, kept in the app's own storage as 0001.jpg, 0002.jpg ... so the slideshow and the
 * screensaver never need the network. A new pick downloads into "incoming" and replaces the set only once
 * every photo is in, so a failed pick leaves the old slideshow as it was.
 */
final class Library {
    private final File dir;
    private final File incoming;

    Library(Context ctx) {
        dir = new File(ctx.getFilesDir(), "photos");
        incoming = new File(ctx.getFilesDir(), "incoming");
    }

    /** The photos to show, in the order they were picked. */
    List<File> photos() {
        File[] files = dir.listFiles((d, name) -> name.endsWith(".jpg"));
        if (files == null) return new ArrayList<>();
        Arrays.sort(files);
        return new ArrayList<>(Arrays.asList(files));
    }

    /** An empty folder to download a new pick into. */
    File startIncoming() {
        clear(incoming);
        incoming.mkdirs();
        return incoming;
    }

    static String name(int index) {
        return String.format(java.util.Locale.ROOT, "%04d.jpg", index + 1);
    }

    /** The finished pick becomes the slideshow. */
    void commit() {
        clear(dir);
        if (!incoming.renameTo(dir)) {
            dir.mkdirs();
            File[] files = incoming.listFiles();
            if (files != null) for (File f : files) f.renameTo(new File(dir, f.getName()));
            clear(incoming);
        }
    }

    void abandonIncoming() {
        clear(incoming);
    }

    /** Forget every photo (the Remove photos button). */
    void clearAll() {
        clear(dir);
        clear(incoming);
    }

    private static void clear(File folder) {
        File[] files = folder.listFiles();
        if (files != null) for (File f : files) f.delete();
        folder.delete();
    }
}
