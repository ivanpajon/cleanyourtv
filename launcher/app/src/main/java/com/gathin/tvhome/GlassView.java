package com.gathin.tvhome;

import android.content.Context;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Build;
import android.util.AttributeSet;
import android.view.View;

/**
 * A translucent glass slab: soft sheen fill, bright top specular, crisp rim, a faint outer glow and, on Home, a
 * soft white halo around the focused tile. One instance floats over the grid and fades in on the focused tile.
 */
public class GlassView extends View {

    /** Room around the slab (dp) for the glow, and for the halo once showHalo is on; owners size the view with it. */
    static final float GLOW_DP = 6, HALO_DP = 30;

    private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path tile = new Path();
    private boolean haloOn;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint specular = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float density;
    private float radius;
    /** Glow colour as 0xRRGGBB: white normally, a festival's accent (diya gold) while a festive skin is on. */
    private int glowRgb = 0xFFFFFF;
    /** Glow strength: the white glow stays faint, a coloured one needs more alpha to read as warm light. */
    private int glowAlpha = 0x30;

    public GlassView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        radius = 20f * density;
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(1.5f * density);
        rim.setColor(0xCCFFFFFF);
        glow.setStyle(Paint.Style.STROKE);
        halo.setColor(0x40FFFFFF);
        halo.setMaskFilter(new BlurMaskFilter(12f * density, BlurMaskFilter.Blur.NORMAL));
        // Cache the slab as a texture. Moves and fades only change translation and alpha, so the GPU can
        // move it without replaying clipPath and the glow on every frame. That path is
        // software-rendered on the MT5895 and is what makes the highlight crawl.
        setLayerType(LAYER_TYPE_HARDWARE, null);
    }

    /** Tints the rim and the outer glow; 0 restores the plain white glass. */
    void setAccent(int argb) {
        if (argb == 0) {
            glowRgb = 0xFFFFFF;
            glowAlpha = 0x30;
            rim.setColor(0xCCFFFFFF);
        } else {
            glowRgb = argb & 0xFFFFFF;
            glowAlpha = 0x70;
            // The rim is the accent lifted towards white, so the edge still reads as glass, not paint.
            int r = (((glowRgb >> 16) & 0xFF) + 255 * 2) / 3, g = (((glowRgb >> 8) & 0xFF) + 255 * 2) / 3,
                    b = ((glowRgb & 0xFF) + 255 * 2) / 3;
            rim.setColor(0xE6000000 | (r << 16) | (g << 8) | b);
        }
        invalidate();
    }

    /** Draws the halo around the slab (Home's tiles). Call it before the first layout and size the view with HALO_DP. */
    void showHalo() {
        haloOn = true;
    }

    private float inset() {
        return (haloOn ? HALO_DP : GLOW_DP) * density;
    }

    void setRadius(float px) {
        radius = px;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        float inset = inset(), slabH = h - 2 * inset;
        fill.setShader(new LinearGradient(0, inset, 0, inset + slabH, 0x3DFFFFFF, 0x12FFFFFF, Shader.TileMode.CLAMP));
        specular.setShader(new LinearGradient(0, inset, 0, inset + slabH * 0.45f, 0x66FFFFFF, 0x00FFFFFF,
                Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float inset = inset();
        rect.set(inset, inset, getWidth() - inset, getHeight() - inset);

        // The tile's halo, even all round so the tile stays centred: a blurred round rect a little larger than
        // the slab, brightest right at its edge and kept off the tile itself. Before Android 9 the hardware
        // renderer ignores mask filters, which would leave a hard ring, so those TVs go without.
        if (haloOn && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            tile.reset();
            tile.addRoundRect(rect, radius, radius, Path.Direction.CW);
            canvas.save();
            canvas.clipOutPath(tile);
            float spread = 4f * density;
            canvas.drawRoundRect(rect.left - spread, rect.top - spread, rect.right + spread, rect.bottom + spread,
                    radius + spread, radius + spread, halo);
            canvas.restore();
        }

        // Outer glow: three widening, fading strokes. The hardware layer caches the result, so
        // they cost one draw, not one per frame of a fade.
        for (int i = 1; i <= 3; i++) {
            glow.setStrokeWidth(i * 2f * density);
            glow.setColor((glowAlpha / i) << 24 | glowRgb);
            float o = i * density;
            canvas.drawRoundRect(rect.left - o, rect.top - o, rect.right + o, rect.bottom + o, radius + o, radius + o, glow);
        }

        canvas.drawRoundRect(rect, radius, radius, fill);

        // Sheen across the top 45%: clipRect stays on the GPU (clipPath did not on the MT5895) and
        // the round rect under it keeps the top corners rounded with a flat bottom edge.
        canvas.save();
        canvas.clipRect(rect.left, rect.top, rect.right, rect.top + rect.height() * 0.45f);
        canvas.drawRoundRect(rect, radius, radius, specular);
        canvas.restore();

        canvas.drawRoundRect(rect, radius, radius, rim);
    }
}
