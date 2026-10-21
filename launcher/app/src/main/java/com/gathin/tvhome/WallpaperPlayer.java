package com.gathin.tvhome;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.PorterDuff;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Displays and rotates a theme's local wallpaper images while Home is visible. */
final class WallpaperPlayer {
    private final FrameLayout root;
    /** The picture; with a wallpaper motion, the still cover over the wave while that has no frame on screen. */
    private final ImageView image;
    /** The next picture, faded in over {@link #image}; null when the theme cuts instead of fading. */
    private final ImageView incoming;
    /** The moving wallpaper (wallpaper_motion_wave), under {@link #image}; null for still pictures. */
    private WallpaperWave wave;
    private final List<File> files;
    private final long interval;
    private final long fade;
    private final SharedPreferences preferences;
    private final SharedPreferences settings;
    private final ExecutorService decoder = Executors.newSingleThreadExecutor();
    private final Random random = new Random();
    private final int width;
    private final int height;
    private boolean active;
    private boolean closed;
    private boolean loading;
    private ValueAnimator fading;
    private String displayed = "";
    /** The picture the wave shows, for the cover to take over while Home is paused. */
    private Bitmap current;
    /** The picture a wave crossfade is heading to, until it shows alone. */
    private Bitmap fadingTo;
    /** The rotation's last picture and change time, saved when Home pauses rather than at every change. */
    private String imageName;
    private long changedAt;
    private boolean unsaved;
    /** The rotation keeps its own time, so intervals shorter than Home's minute tick work. */
    private final Runnable tick = this::update;

    WallpaperPlayer(Context context, FrameLayout root, Config config) {
        this.root = root;
        files = new ArrayList<>(config.wallpapers);
        interval = config.wallpaperRotationSeconds * 1000L;
        // Ends at least a second before the next tick, so every picture is seen whole for a moment.
        fade = config.animations && !config.reducedMotion
                ? Math.min(config.wallpaperFadeSeconds * 1000L, interval - 1000) : 0;
        preferences = context.getSharedPreferences("wallpaper_rotation", Context.MODE_PRIVATE);
        imageName = preferences.getString("image", "");
        changedAt = preferences.getLong("changed", 0);
        settings = context.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
        boolean shown = config.wallpaperEnabled && !files.isEmpty();
        boolean motion = shown && config.wallpaperWave && config.animations && !config.reducedMotion
                && ValueAnimator.areAnimatorsEnabled();
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        // The wave renders small and the display scales it up: its pictures need no more pixels than that.
        width = motion ? WallpaperWave.RENDER_WIDTH : metrics.widthPixels;
        height = motion ? Math.round((float) WallpaperWave.RENDER_WIDTH * metrics.heightPixels / metrics.widthPixels)
                : metrics.heightPixels;
        image = picture(context);
        // A second full-screen view only for a fading rotation: it is overdraw on 1 GB TVs, and stays GONE between fades.
        boolean fades = shown && !motion && fade > 0 && files.size() > 1;
        incoming = fades ? picture(context) : null;
        if (motion) {
            wave = new WallpaperWave(context, height, config.wallpaperDim, config.wallpaperWaveStrength,
                    config.wallpaperWaveSpeed, this::onWaveDrawn);
        }
        if (shown) {
            int index = 0;
            if (wave != null) root.addView(wave, index++, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            root.addView(image, index++, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            if (incoming != null) {
                incoming.setVisibility(View.GONE);
                root.addView(incoming, index++, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            }
            if (wave != null) {
                // The cover looks like a still frame of the wave: the same zoom, and the dim the wave draws itself.
                image.setScaleX(wave.zoom);
                image.setScaleY(wave.zoom);
                if (config.wallpaperDim > 0) {
                    image.setColorFilter((config.wallpaperDim * 255 / 100) << 24, PorterDuff.Mode.SRC_ATOP);
                }
            } else if (config.wallpaperDim > 0) {
                // No shade layer at all when the theme doesn't dim: a full-screen view is overdraw on 1 GB TVs.
                View shade = new View(context);
                shade.setBackgroundColor((config.wallpaperDim * 255 / 100) << 24);
                shade.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                root.addView(shade, index, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            }
            showFirst();
            if (wave != null && current == null) {
                // Nothing could be decoded: the wave's surface would show black instead of the theme's colour.
                root.removeView(wave);
                wave = null;
            }
        }
    }

    private static ImageView picture(Context context) {
        ImageView view = new ImageView(context);
        view.setScaleType(ImageView.ScaleType.CENTER_CROP);
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return view;
    }

    /**
     * The picture Home opens with is decoded right here, on the main thread, as the single wallpaper always was:
     * Home's first frame already has it instead of drawing the plain colour and the picture popping in later.
     * Only the later changes of a rotation decode in the background.
     */
    private void showFirst() {
        String previous = imageName;
        File first = WallpaperRotation.find(files, previous);
        if (first == null) first = WallpaperRotation.pick(files, previous, random);
        while (first != null) {
            Bitmap bitmap = decode(first);
            if (bitmap != null) {
                image.setImageBitmap(bitmap);
                if (wave != null) {
                    wave.show(bitmap);
                    current = bitmap;
                }
                displayed = first.getName();
                if (!displayed.equals(previous)) {
                    imageName = displayed;
                    changedAt = System.currentTimeMillis();
                    unsaved = true;
                    save();
                }
                return;
            }
            files.remove(first);   // unreadable or over 4K: try another, or keep the plain colour
            first = WallpaperRotation.pick(files, previous, random);
        }
    }

    void start() {
        active = true;
        if (wave != null) {
            // Each start builds a new GL context. Cleared by its first frame; still set when Home next starts, the
            // wave took Home down, and it stays off.
            settings.edit().putBoolean(Config.MOTION_PENDING_KEY, true).commit();
            wave.reportNextFrame();   // the cover stays until the wave is on screen
            wave.play();
        }
        update();
    }

    void stop() {
        active = false;
        root.removeCallbacks(tick);
        finishFade();   // Home is hidden: land on the new picture instead of fading unseen
        save();
        if (wave != null) {
            // Home can stay in sight while paused (a panel over it), and the wave's surface comes back black.
            image.setImageBitmap(current);
            image.setVisibility(View.VISIBLE);
            wave.pause();
            // The GL thread has stopped without taking Home down, even if it drew no frame.
            settings.edit().remove(Config.MOTION_PENDING_KEY).apply();
        }
    }

    void close() {
        active = false;
        closed = true;
        root.removeCallbacks(tick);
        finishFade();
        save();
        if (wave != null) wave.pause();
        decoder.shutdownNow();
    }

    private void onWaveDrawn() {
        settings.edit().remove(Config.MOTION_PENDING_KEY).apply();
        if (!active) return;
        image.setVisibility(View.GONE);
        image.setImageDrawable(null);
    }

    void update() {
        // A decode in progress books the next tick itself.
        if (!active || closed || loading || files.isEmpty()) return;
        // A fade the TV's animator scale stretched past the interval: finish it, or this tick is lost.
        finishFade();
        root.removeCallbacks(tick);
        String previous = imageName;
        File selected = WallpaperRotation.find(files, previous);
        long changed = changedAt;
        // Stored as the change time below, so the next tick, one interval on, finds it due.
        long now = System.currentTimeMillis();
        if (selected == null || WallpaperRotation.due(changed, now, interval)) {
            selected = WallpaperRotation.pick(files, previous, random);
        }
        if (selected == null || selected.getName().equals(displayed)) {
            if (interval > 0) root.postDelayed(tick, WallpaperRotation.wait(changed, now, interval));
            return;
        }
        File next = selected;
        loading = true;
        decoder.execute(() -> {
            Bitmap bitmap = decode(next);
            root.post(() -> {
                loading = false;
                if (closed || !active) {
                    if (bitmap != null) bitmap.recycle();
                    return;
                }
                if (bitmap == null) {
                    files.remove(next);
                    Log.w("TvHome.Wallpaper", "Skipping unreadable image: " + next.getName());
                    update();
                    return;
                }
                show(bitmap);
                displayed = next.getName();
                if (!displayed.equals(previous) || changed == 0) {
                    // Saved when Home pauses: no flash write every rotation.
                    imageName = displayed;
                    changedAt = now;
                    unsaved = true;
                }
                if (interval > 0) root.postDelayed(tick, interval);
            });
        });
    }

    private void show(Bitmap bitmap) {
        if (wave != null) {
            if (fade == 0) {
                wave.show(bitmap);
                settle(bitmap);
            } else {
                // The wave times the crossfade on its own frames: no animator wakes the main thread every vsync.
                fadingTo = bitmap;
                wave.fadeTo(bitmap, fade, () -> landFade(bitmap));
            }
            return;
        }
        if (incoming == null) {
            image.setImageBitmap(bitmap);
            return;
        }
        incoming.setImageBitmap(bitmap);
        incoming.setAlpha(0f);
        incoming.setVisibility(View.VISIBLE);
        fading = ValueAnimator.ofFloat(0f, 1f);
        fading.setDuration(fade);
        fading.setInterpolator(new AccelerateDecelerateInterpolator());
        fading.addUpdateListener(animation ->
                incoming.setAlpha(WallpaperRotation.fadeAlpha(animation.getAnimatedFraction())));
        fading.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                fading = null;
                // The bottom view takes the new picture and the top one goes away in the same frame, so nothing
                // shows the change, and between fades only one full-screen picture is drawn.
                image.setImageBitmap(bitmap);
                incoming.setVisibility(View.GONE);
                incoming.setImageDrawable(null);
            }
        });
        fading.start();
    }

    /** Lands a crossfade in progress on its new picture now. */
    private void finishFade() {
        if (fading != null) fading.end();
        if (fadingTo != null) {
            wave.finishFade();
            landFade(fadingTo);
        }
    }

    /** The wave's crossfade to {@code bitmap} is over, by itself or by finishFade, whichever comes first. */
    private void landFade(Bitmap bitmap) {
        if (fadingTo != bitmap) return;
        fadingTo = null;
        settle(bitmap);
    }

    private void save() {
        if (!unsaved) return;
        unsaved = false;
        preferences.edit().putString("image", imageName).putLong("changed", changedAt).apply();
    }

    /** The wave now shows {@code bitmap}; a cover in sight follows it. */
    private void settle(Bitmap bitmap) {
        current = bitmap;
        if (image.getVisibility() == View.VISIBLE) image.setImageBitmap(bitmap);
    }

    private Bitmap decode(File file) {
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), options);
            int width = options.outWidth, height = options.outHeight;
            if (width <= 0 || height <= 0) return null;
            int sample = WallpaperSize.sampleSize(width, height, this.width, this.height);
            if (!WallpaperSize.accepted(width / sample, height / sample)) return null;
            options.inJustDecodeBounds = false;
            options.inSampleSize = sample;
            int[] scale = WallpaperSize.coverScale(width / sample, height / sample, this.width, this.height);
            if (scale != null) {
                options.inScaled = true;
                options.inDensity = scale[0];
                options.inTargetDensity = scale[1];
            }
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeFile(file.getPath(), options);
        } catch (Exception | OutOfMemoryError failure) {
            return null;
        }
    }
}
