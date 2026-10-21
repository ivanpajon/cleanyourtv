package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import org.junit.Test;

public class WallpaperRotationTest {

    private static File touch(File dir, String name, int bytes) throws IOException {
        File f = new File(dir, name);
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(new byte[bytes]);
        }
        return f;
    }

    @Test
    public void imagesKeepsPicturesOnlySortedByName() throws IOException {
        File dir = Files.createTempDirectory("walls").toFile();
        touch(dir, "b.JPG", 10);
        touch(dir, "a.png", 10);
        touch(dir, "notes.txt", 10);
        touch(dir, "empty.jpg", 0);
        new File(dir, "folder.jpg").mkdir();
        List<File> images = WallpaperRotation.images(dir);
        assertEquals(Arrays.asList("a.png", "b.JPG"), names(images));
        assertTrue(WallpaperRotation.images(null).isEmpty());
        assertTrue(WallpaperRotation.images(new File(dir, "missing")).isEmpty());
    }

    @Test
    public void pickAvoidsThePreviousPictureWhenThereIsAnother() {
        List<File> images = Arrays.asList(new File("a.jpg"), new File("b.jpg"));
        Random random = new Random(1);
        for (int i = 0; i < 20; i++) {
            assertNotEquals("a.jpg", WallpaperRotation.pick(images, "a.jpg", random).getName());
        }
        assertEquals("a.jpg", WallpaperRotation.pick(Collections.singletonList(new File("a.jpg")), "a.jpg", random).getName());
        assertNull(WallpaperRotation.pick(Collections.emptyList(), "", random));
    }

    @Test
    public void findMatchesByName() {
        List<File> images = Arrays.asList(new File("a.jpg"), new File("b.jpg"));
        assertEquals("b.jpg", WallpaperRotation.find(images, "b.jpg").getName());
        assertNull(WallpaperRotation.find(images, "c.jpg"));
    }

    @Test
    public void dueOnlyWithRotationOnAndTheIntervalPassed() {
        long minute = 60_000L;
        assertFalse(WallpaperRotation.due(0, 10 * minute, 0));             // rotation off
        assertTrue(WallpaperRotation.due(0, 10 * minute, 5 * minute));      // never changed yet
        assertFalse(WallpaperRotation.due(8 * minute, 10 * minute, 5 * minute));
        assertTrue(WallpaperRotation.due(5 * minute, 10 * minute, 5 * minute));
        assertTrue(WallpaperRotation.due(20 * minute, 10 * minute, 5 * minute));   // clock went back
    }

    @Test
    public void waitIsTheTimeLeftAndNeverMoreThanOneInterval() {
        long second = 1000L;
        assertEquals(3 * second, WallpaperRotation.wait(2 * second, 4 * second, 5 * second));
        assertEquals(5 * second, WallpaperRotation.wait(0, 60 * second, 5 * second));          // already due
        assertEquals(5 * second, WallpaperRotation.wait(5 * second, 10 * second, 5 * second)); // due right now
        assertEquals(5 * second, WallpaperRotation.wait(600 * second, 10 * second, 5 * second)); // clock went back
    }

    @Test
    public void fadeAlphaRunsFromZeroToOneInScreenSteps() {
        assertEquals(0f, WallpaperRotation.fadeAlpha(0f), 0f);
        assertEquals(1f, WallpaperRotation.fadeAlpha(1f), 0f);
        assertEquals(0f, WallpaperRotation.fadeAlpha(-0.2f), 0f);   // interpolator overshoot stays in range
        assertEquals(1f, WallpaperRotation.fadeAlpha(1.2f), 0f);
        // Nearby fractions share a step, so the frames between them redraw nothing.
        assertEquals(WallpaperRotation.fadeAlpha(0.5000f), WallpaperRotation.fadeAlpha(0.5005f), 0f);
        assertEquals(128 / 255f, WallpaperRotation.fadeAlpha(0.5f), 0f);
    }

    private static List<String> names(List<File> files) {
        String[] out = new String[files.size()];
        for (int i = 0; i < out.length; i++) out[i] = files.get(i).getName();
        return Arrays.asList(out);
    }
}
