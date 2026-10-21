package com.gathin.tvhome;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class WallpaperWaveTest {
    @Test public void aGentleWaveDrawsFifteenFramesASecond() {
        assertEquals(58, WallpaperWave.idleDelay(19, 100));
        assertEquals(58, WallpaperWave.idleDelay(10, 200));
        assertEquals(58, WallpaperWave.idleDelay(30, 50));
    }

    @Test public void theDefaultWaveDrawsTwenty() {
        assertEquals(41, WallpaperWave.idleDelay(WallpaperWave.DEFAULT_STRENGTH, WallpaperWave.DEFAULT_SPEED));
        assertEquals(41, WallpaperWave.idleDelay(19, 150));
    }

    @Test public void aStrongOrFastWaveDrawsThirty() {
        assertEquals(25, WallpaperWave.idleDelay(42, 100));
        assertEquals(25, WallpaperWave.idleDelay(30, 150));
        assertEquals(25, WallpaperWave.idleDelay(Config.MAX_WAVE_STRENGTH, Config.MAX_WAVE_SPEED));
    }
}
