package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.List;

/** Remote-friendly settings owned by the launcher; system settings stay one row away. */
public final class LauncherSettingsActivity extends Activity {
    private SharedPreferences choices;
    private final TextView[] columnChoices = new TextView[4];
    private final TextView[] rowChoices = new TextView[4];
    private static final int[] STRENGTHS = {30, 42, 54, 66};
    private static final int[] SPEEDS = {50, 100, 150, 200};
    private final TextView[] strengthChoices = new TextView[4];
    private final TextView[] speedChoices = new TextView[4];
    private TextView themeChoice;
    private TextView waveChoice;
    private TextView wallpaperChoice;
    private TextView animationsChoice;
    private TextView advancedChoice;
    private TextView followThemeChoice;
    private String themeDisplayName;
    private boolean themeHasWave;
    private int columns;
    private int rowsBelowDock;
    private boolean animations;
    private boolean advancedEffects;
    private boolean wave;
    private int waveStrength;
    private int waveSpeed;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_launcher_settings);
        choices = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
        Config config = Config.load(this);
        columns = config.columns;
        rowsBelowDock = config.rowsBelowDock;
        animations = config.animations;
        advancedEffects = config.animations && config.advancedEffects;
        loadWave(config);
        themeHasWave = config.themeHasWave;
        themeDisplayName = config.themeName == null ? getString(R.string.theme_builtin)
                : config.themeName.isEmpty() ? getString(R.string.theme_custom) : config.themeName;

        int[] ids = {R.id.columns4, R.id.columns5, R.id.columns6, R.id.columns7};
        for (int i = 0; i < ids.length; i++) {
            final int value = i + 4;
            columnChoices[i] = findViewById(ids[i]);
            columnChoices[i].setOnClickListener(v -> {
                if (columns == value) return;
                columns = value;
                choices.edit().putInt("columns", value).apply();
                setResult(RESULT_OK);
                refresh();
            });
        }
        int[] rowIds = {R.id.rows0, R.id.rows1, R.id.rows2, R.id.rows3};
        for (int i = 0; i < rowIds.length; i++) {
            final int value = i;
            rowChoices[i] = findViewById(rowIds[i]);
            rowChoices[i].setOnClickListener(v -> {
                if (rowsBelowDock == value) return;
                rowsBelowDock = value;
                choices.edit().putInt(Config.ROWS_BELOW_DOCK_KEY, value).apply();
                setResult(RESULT_OK);
                refresh();
            });
        }
        // Keep the numeric choices increasing in the Hebrew reading direction. Reorder the
        // actual views so D-pad focus follows their positions without changing the saved values.
        int[] strengthIds = {R.id.strength30, R.id.strength42, R.id.strength54, R.id.strength66};
        int[] speedIds = {R.id.speed50, R.id.speed100, R.id.speed150, R.id.speed200};
        for (int i = 0; i < 4; i++) {
            final int strength = STRENGTHS[i], speed = SPEEDS[i];
            strengthChoices[i] = findViewById(strengthIds[i]);
            strengthChoices[i].setOnClickListener(v -> {
                if (waveStrength == strength) return;
                waveStrength = strength;
                choices.edit().putInt(Config.WAVE_STRENGTH_KEY, strength).apply();
                setResult(RESULT_OK);
                refresh();
            });
            speedChoices[i] = findViewById(speedIds[i]);
            speedChoices[i].setOnClickListener(v -> {
                if (waveSpeed == speed) return;
                waveSpeed = speed;
                choices.edit().putInt(Config.WAVE_SPEED_KEY, speed).apply();
                setResult(RESULT_OK);
                refresh();
            });
        }
        if (getResources().getBoolean(R.bool.rtl_app_grid)) {
            reverseRow(columnChoices);
            reverseRow(rowChoices);
            reverseRow(strengthChoices);
            reverseRow(speedChoices);
        }
        themeChoice = findViewById(R.id.themeChoice);
        themeChoice.setOnClickListener(v -> showThemePicker());
        waveChoice = findViewById(R.id.waveChoice);
        waveChoice.setOnClickListener(v -> {
            wave = !wave;
            // Choosing the wave again also forgets a crash that switched it off.
            choices.edit().putBoolean(Config.WAVE_KEY, wave).remove(Config.MOTION_PENDING_KEY).apply();
            setResult(RESULT_OK);
            refresh();
        });
        findViewById(R.id.themeSettings).setVisibility(
                themeHasWave || config.wallpaperPicker ? View.VISIBLE : View.GONE);
        ((TextView) findViewById(R.id.themeSettingsTitle)).setText(
                getString(R.string.theme_settings_title, themeDisplayName));
        int waveVisibility = themeHasWave ? View.VISIBLE : View.GONE;
        waveChoice.setVisibility(waveVisibility);
        findViewById(R.id.waveDetails).setVisibility(waveVisibility);
        wallpaperChoice = findViewById(R.id.wallpaperChoice);
        wallpaperChoice.setOnClickListener(v -> showWallpaperPicker());
        wallpaperChoice.setVisibility(config.wallpaperPicker ? View.VISIBLE : View.GONE);
        animationsChoice = findViewById(R.id.animationsChoice);
        animationsChoice.setOnClickListener(v -> {
            animations = !animations;
            if (!animations) advancedEffects = false;
            choices.edit().putBoolean("animations", animations)
                    .putBoolean("advanced_effects", advancedEffects).apply();
            setResult(RESULT_OK);
            refresh();
        });
        advancedChoice = findViewById(R.id.advancedChoice);
        advancedChoice.setOnClickListener(v -> {
            if (advancedEffects) {
                setAdvanced(false);
            } else {
                new AlertDialog.Builder(this)
                        .setTitle(R.string.advanced_effects)
                        .setMessage(R.string.advanced_effects_warning)
                        .setPositiveButton(R.string.enable, (dialog, which) -> setAdvanced(true))
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            }
        });
        followThemeChoice = findViewById(R.id.followThemeChoice);
        followThemeChoice.setOnClickListener(v -> {
            if (!hasOnTvChoices()) return;
            choices.edit().remove("columns").remove(Config.ROWS_BELOW_DOCK_KEY).remove("animations")
                    .remove("advanced_effects").remove(Config.WALLPAPER_CHOICE_KEY).remove(Config.WAVE_KEY)
                    .remove(Config.WAVE_STRENGTH_KEY).remove(Config.WAVE_SPEED_KEY).commit();
            Config current = Config.load(this);
            columns = current.columns;
            rowsBelowDock = current.rowsBelowDock;
            animations = current.animations;
            advancedEffects = current.animations && current.advancedEffects;
            loadWave(current);
            setResult(RESULT_OK);
            refresh();
        });
        findViewById(R.id.defaultHomeChoice).setOnClickListener(v -> {
            Toast.makeText(this, R.string.home_settings_help, Toast.LENGTH_LONG).show();
            open(new Intent(Settings.ACTION_HOME_SETTINGS));
        });
        findViewById(R.id.tvSettingsChoice).setOnClickListener(v ->
                open(new Intent(Settings.ACTION_SETTINGS)));
        refresh();
        columnChoices[Math.max(0, Math.min(columns - 4, 3))].requestFocus();
    }

    private void reverseRow(TextView[] views) {
        LinearLayout row = (LinearLayout) views[0].getParent();
        row.removeAllViews();
        for (int i = views.length - 1; i >= 0; i--) row.addView(views[i]);
    }

    private void loadWave(Config config) {
        wave = config.wallpaperWave;
        waveStrength = config.wallpaperWaveStrength;
        waveSpeed = config.wallpaperWaveSpeed;
    }

    /** Lists the theme pushed to the TV; switching themes is done by pushing another one. */
    private void showThemePicker() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.theme_label)
                .setSingleChoiceItems(new String[] {themeDisplayName}, 0, (dialog, which) -> dialog.dismiss())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showWallpaperPicker() {
        File dir = Config.dir(this);
        List<File> images = new java.util.ArrayList<>(WallpaperRotation.images(
                dir == null ? null : new File(dir, "wallpapers")));
        // A theme with a single wallpaper.jpg offers that picture too (Config maps the choice back to it).
        File single = dir == null ? null : new File(dir, Config.WALLPAPER_NAME);
        if (single != null && single.isFile()) images.add(0, single);
        String[] labels = new String[images.size() + 1];
        for (int i = 0; i < images.size(); i++) labels[i] = images.get(i).getName();
        labels[images.size()] = getString(R.string.wallpaper_none);
        String current = choices.getString(Config.WALLPAPER_CHOICE_KEY, null);
        int checked = "none".equals(current) ? images.size()
                : indexOf(images, current);
        new AlertDialog.Builder(this)
                .setTitle(R.string.wallpaper_label)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    dialog.dismiss();
                    if (which == images.size()) {
                        choices.edit().putString(Config.WALLPAPER_CHOICE_KEY, "none").apply();
                    } else {
                        choices.edit().putString(Config.WALLPAPER_CHOICE_KEY,
                                images.get(which).getName()).apply();
                    }
                    setResult(RESULT_OK);
                    Toast.makeText(this, R.string.wallpaper_applied, Toast.LENGTH_SHORT).show();
                    refresh();
                })
                .setNeutralButton(R.string.wallpaper_theme_default, (dialog, which) -> {
                    choices.edit().remove(Config.WALLPAPER_CHOICE_KEY).apply();
                    setResult(RESULT_OK);
                    refresh();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static int indexOf(List<File> images, String name) {
        if (name == null) return -1;
        for (int i = 0; i < images.size(); i++) {
            if (images.get(i).getName().equals(name)) return i;
        }
        return -1;
    }

    private void setAdvanced(boolean enabled) {
        advancedEffects = enabled;
        choices.edit().putBoolean("advanced_effects", enabled).apply();
        setResult(RESULT_OK);
        refresh();
    }

    private void refresh() {
        for (int i = 0; i < columnChoices.length; i++) {
            columnChoices[i].setSelected(columns == i + 4);
        }
        for (int i = 0; i < rowChoices.length; i++) {
            rowChoices[i].setSelected(rowsBelowDock == i);
        }
        animationsChoice.setText(getString(R.string.animations_state,
                getString(animations ? R.string.on : R.string.off)));
        advancedChoice.setText(getString(R.string.advanced_effects_state,
                getString(advancedEffects ? R.string.on : R.string.off)));
        advancedChoice.setEnabled(animations);
        advancedChoice.setAlpha(animations ? 1f : 0.5f);
        themeChoice.setText(getString(R.string.theme_state, themeDisplayName));
        waveChoice.setText(getString(R.string.wave_state, getString(wave ? R.string.on : R.string.off)));
        // Like advanced effects, the wave only runs with animations on.
        waveChoice.setEnabled(animations);
        waveChoice.setAlpha(animations ? 1f : 0.5f);
        boolean tunable = wave && animations;
        for (int i = 0; i < 4; i++) {
            strengthChoices[i].setSelected(waveStrength == STRENGTHS[i]);
            strengthChoices[i].setEnabled(tunable);
            strengthChoices[i].setAlpha(tunable ? 1f : 0.5f);
            speedChoices[i].setSelected(waveSpeed == SPEEDS[i]);
            speedChoices[i].setEnabled(tunable);
            speedChoices[i].setAlpha(tunable ? 1f : 0.5f);
        }
        String wallpaperCurrent = choices.getString(Config.WALLPAPER_CHOICE_KEY, null);
        wallpaperChoice.setText(getString(R.string.wallpaper_state,
                "none".equals(wallpaperCurrent) ? getString(R.string.wallpaper_none)
                        : wallpaperCurrent != null ? wallpaperCurrent
                        : getString(R.string.wallpaper_theme_default)));
        followThemeChoice.setSelected(!hasOnTvChoices());
    }

    private boolean hasOnTvChoices() {
        return choices.contains("columns") || choices.contains(Config.ROWS_BELOW_DOCK_KEY)
                || choices.contains("animations") || choices.contains("advanced_effects")
                || choices.contains(Config.WALLPAPER_CHOICE_KEY) || choices.contains(Config.WAVE_KEY)
                || choices.contains(Config.WAVE_STRENGTH_KEY) || choices.contains(Config.WAVE_SPEED_KEY);
    }

    private void open(Intent intent) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_LONG).show();
        }
    }
}
