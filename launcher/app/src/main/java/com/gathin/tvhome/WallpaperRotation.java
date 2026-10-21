package com.gathin.tvhome;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Random;

final class WallpaperRotation {
    static List<File> images(File directory) {
        List<File> result = new ArrayList<>();
        File[] entries = directory == null ? null : directory.listFiles();
        if (entries == null) return result;
        Arrays.sort(entries, Comparator.comparing(File::getName));
        for (File entry : entries) {
            String name = entry.getName().toLowerCase(Locale.ROOT);
            if (entry.isFile() && entry.length() > 0 && entry.length() <= 10 * 1024 * 1024
                    && (name.endsWith(".jpg") || name.endsWith(".jpeg")
                    || name.endsWith(".png") || name.endsWith(".webp"))) {
                result.add(entry);
                if (result.size() == 100) break;
            }
        }
        return result;
    }

    static File find(List<File> images, String name) {
        for (File image : images) if (image.getName().equals(name)) return image;
        return null;
    }

    static File pick(List<File> images, String previous, Random random) {
        List<File> candidates = new ArrayList<>(images);
        if (candidates.size() > 1) candidates.removeIf(image -> image.getName().equals(previous));
        return candidates.isEmpty() ? null : candidates.get(random.nextInt(candidates.size()));
    }

    static boolean due(long previous, long now, long interval) {
        return interval > 0 && (previous == 0 || now < previous || now - previous >= interval);
    }

    /** Milliseconds until the next change is due; one interval when it is already due or the clock went back. */
    static long wait(long previous, long now, long interval) {
        long left = previous + interval - now;
        return left > 0 && left <= interval ? left : interval;
    }

    /**
     * A crossfade's alpha in the 256 steps the screen can show: frames that land on the same step set the same
     * alpha and draw nothing, so a 30-second fade redraws a few times a second instead of on every frame.
     */
    static float fadeAlpha(float fraction) {
        return Math.round(Math.max(0f, Math.min(1f, fraction)) * 255) / 255f;
    }
}
