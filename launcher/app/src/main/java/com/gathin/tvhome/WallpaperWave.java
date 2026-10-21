package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.GLUtils;
import android.provider.Settings;
import android.util.Log;
import android.view.Choreographer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLDisplay;
import javax.microedition.khronos.opengles.GL10;

/**
 * A slow wave over the wallpaper (wallpaper_motion_wave), drawn with OpenGL ES 2.0 on its own surface under Home's
 * window, so the UI above is never redrawn for it. Kept cheap for 1 GB TVs: it renders 960 px wide and the display
 * scales it up, the wave is worked out per vertex on a coarse grid so each pixel is one texture read (two during a
 * crossfade), it draws only the 15, 20 or 30 frames a second the drift needs to look smooth, and nothing runs while
 * Home is hidden.
 */
final class WallpaperWave extends GLSurfaceView implements GLSurfaceView.Renderer {
    static final int RENDER_WIDTH = 960;
    static final int DEFAULT_STRENGTH = 30, DEFAULT_SPEED = 100;
    private static final int COLUMNS = 32, ROWS = 18;
    /** Indexes into a program's uniform locations. */
    private static final int PHASE = 0, AMP = 1, CROP_A = 2, CROP_B = 3, MIX = 4;
    private static final String TAG = "TvHome.Wave";

    private static final String VERTEX =
            "attribute vec2 aPos;\n"   // 0..1 across the screen, y down
            + "uniform vec2 uCropA;\n"
            + "uniform vec2 uCropB;\n"
            + "uniform vec2 uAmp;\n"
            + "uniform vec2 uPhase;\n"
            + "varying vec2 vA;\n"
            + "varying vec2 vB;\n"
            + "void main() {\n"
            // The lower part, behind the dock, moves at a third of the strength.
            + "  float e = 1.0 - 0.7 * smoothstep(0.55, 1.0, aPos.y);\n"
            + "  vec2 d = vec2(sin(6.2832 * 1.3 * aPos.y - uPhase.y),\n"
            + "      0.7 * sin(6.2832 * 1.1 * aPos.x - uPhase.x) + 0.3 * sin(6.2832 * (2.3 * aPos.x + 0.6 * aPos.y) + uPhase.y));\n"
            + "  vec2 p = aPos + uAmp * e * d - 0.5;\n"
            + "  vA = 0.5 + p * uCropA;\n"
            + "  vB = 0.5 + p * uCropB;\n"
            + "  gl_Position = vec4(2.0 * aPos.x - 1.0, 1.0 - 2.0 * aPos.y, 0.0, 1.0);\n"
            + "}\n";
    // highp coordinates where the GPU has them: mediump steps about a texel at 960 px, which a slow drift shows.
    private static final String PRECISION =
            "#ifdef GL_FRAGMENT_PRECISION_HIGH\nprecision highp float;\n#else\nprecision mediump float;\n#endif\n";
    private static final String SINGLE = PRECISION
            + "uniform sampler2D uA;\n"
            + "uniform float uShade;\n"
            + "varying vec2 vA;\n"
            + "void main() { gl_FragColor = vec4(texture2D(uA, vA).rgb * uShade, 1.0); }\n";
    private static final String BLEND = PRECISION
            + "uniform sampler2D uA;\n"
            + "uniform sampler2D uB;\n"
            + "uniform float uShade;\n"
            + "uniform float uMix;\n"
            + "varying vec2 vA;\n"
            + "varying vec2 vB;\n"
            + "void main() { gl_FragColor = vec4(mix(texture2D(uA, vA).rgb, texture2D(uB, vB).rgb, uMix) * uShade, 1.0); }\n";

    /** The zoom that keeps the moved edges inside the picture; a still cover in its place should match it. */
    final float zoom;
    /** Of the screen height. */
    private final float amplitude;
    /** Times the default pace (cycles of 9 and 6 seconds). */
    private final double pace;
    private final long idleDelay;
    private final float shade;
    private final Runnable onDrawn;
    private volatile long time;
    private volatile boolean reportDrawn;
    private boolean playing;
    /** A crossfade is under way. Written on the GL thread only, so a fade ending can't cancel the next one. */
    private volatile boolean fading;

    // GL thread only. The pictures change in events, which can run while there is no context; the textures follow
    // them at the next frame, where there always is one.
    private Bitmap pictureA, pictureB, loadedA, loadedB;
    private int textureA, textureB, single, blend, vertices, indices, width = 1, height = 1;
    /** Each program's uniform locations, looked up once per context. */
    private int[] singleAt, blendAt;
    /** The loaded pictures' crops, already zoomed, and the wave's sideways amplitude for this surface. */
    private float[] cropA, cropB;
    private float amplitudeX;
    private long fadeStart, fadeNanos;
    private Runnable onFaded;

    private final Choreographer.FrameCallback driver = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            if (!playing) return;
            time = frameTimeNanos;
            requestRender();
            // A crossfade gets at least 20 frames a second so the blend has no visible steps. Counted from this
            // frame's vsync rather than from now, so a late callback still lands on the vsync it aims for.
            long delay = fading ? Math.min(idleDelay, 41) : idleDelay;
            long late = (System.nanoTime() - frameTimeNanos) / 1_000_000;
            Choreographer.getInstance().postFrameCallbackDelayed(this, Math.max(0, delay - late));
        }
    };

    /**
     * @param strength how far the picture drifts, in pixels of a 1080p screen
     * @param speed    percent of the default pace
     */
    WallpaperWave(Context context, int renderHeight, int dimPercent, int strength, int speed, Runnable onDrawn) {
        super(context);
        amplitude = strength / 1080f;
        zoom = 1f + 2f * amplitude;
        pace = speed / 100.0;
        idleDelay = idleDelay(strength, speed);
        shade = 1f - dimPercent / 100f;
        this.onDrawn = onDrawn;
        setEGLContextClientVersion(2);
        // The pictures are RGB_565 already: a 565 surface halves what the GPU writes and composition reads. A dimmed
        // theme keeps 888, or its darkened gradients would lose another bit.
        boolean rgb565 = dimPercent == 0;
        setEGLConfigChooser((egl, display) -> chooseConfig(egl, display, rgb565 ? 5 : 8, rgb565 ? 6 : 8));
        getHolder().setFormat(rgb565 ? PixelFormat.RGB_565 : PixelFormat.OPAQUE);
        getHolder().setFixedSize(RENDER_WIDTH, renderHeight);
        setRenderer(this);
        setRenderMode(RENDERMODE_WHEN_DIRTY);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /** The picture to show now, without a crossfade. */
    void show(Bitmap picture) {
        queueEvent(() -> {
            pictureA = picture;
            pictureB = null;
            fading = false;
        });
        requestRender();
    }

    /**
     * Crossfades to {@code picture} over {@code millis}, stretched by the TV's animator scale and timed from the
     * fade's first frame on screen. {@code onFaded} runs on the main thread once the new picture shows alone.
     */
    void fadeTo(Bitmap picture, long millis, Runnable onFaded) {
        float scale = Settings.Global.getFloat(getContext().getContentResolver(),
                Settings.Global.ANIMATOR_DURATION_SCALE, 1f);   // what ValueAnimator would apply
        long nanos = Math.max(1, (long) (millis * 1_000_000L * scale));
        queueEvent(() -> {
            pictureB = picture;
            fadeStart = 0;
            fadeNanos = nanos;
            this.onFaded = onFaded;
            fading = true;
        });
    }

    /** Lands a crossfade on its new picture now; the caller does what onFaded would. */
    void finishFade() {
        queueEvent(() -> {
            swap();
            fading = false;
        });
        requestRender();
    }

    /** GL thread: the picture faded to becomes the only one. */
    private void swap() {
        if (pictureB == null) return;
        pictureA = pictureB;
        pictureB = null;
        // The new picture's texture moves over instead of being uploaded again; the old one goes next frame.
        int texture = textureA;
        textureA = textureB;
        textureB = texture;
        Bitmap loaded = loadedA;
        loadedA = loadedB;
        loadedB = loaded;
        float[] crop = cropA;
        cropA = cropB;
        cropB = crop;
    }

    /** Calls onDrawn (on the main thread) once the next frame with a picture is on screen. */
    void reportNextFrame() {
        reportDrawn = true;
        requestRender();
    }

    void play() {
        if (playing) return;
        playing = true;
        time = System.nanoTime();   // the clock frame times use, so a frame drawn before the first tick is in step
        onResume();
        Choreographer.getInstance().postFrameCallback(driver);
    }

    void pause() {
        playing = false;
        Choreographer.getInstance().removeFrameCallback(driver);
        onPause();   // releases the GL context, so its memory goes back to the app in front
        removeCallbacks(onDrawn);   // a frame from before the pause says nothing about the next one
    }

    @Override
    public void onSurfaceCreated(GL10 unused, EGLConfig config) {
        // A new context (the first, or after a pause): the old textures went with the old one.
        textureA = textureB = 0;
        loadedA = loadedB = null;
        single = program(SINGLE);
        blend = program(BLEND);
        singleAt = uniforms(single);
        blendAt = uniforms(blend);
        float[] grid = new float[(COLUMNS + 1) * (ROWS + 1) * 2];
        for (int r = 0, i = 0; r <= ROWS; r++) {
            for (int c = 0; c <= COLUMNS; c++) {
                grid[i++] = (float) c / COLUMNS;
                grid[i++] = (float) r / ROWS;
            }
        }
        short[] order = new short[COLUMNS * ROWS * 6];
        for (int r = 0, i = 0; r < ROWS; r++) {
            for (int c = 0; c < COLUMNS; c++) {
                short corner = (short) (r * (COLUMNS + 1) + c);
                short below = (short) (corner + COLUMNS + 1);
                order[i++] = corner; order[i++] = below; order[i++] = (short) (corner + 1);
                order[i++] = (short) (corner + 1); order[i++] = below; order[i++] = (short) (below + 1);
            }
        }
        int[] ids = new int[2];
        GLES20.glGenBuffers(2, ids, 0);
        vertices = ids[0];
        indices = ids[1];
        FloatBuffer gridData = ByteBuffer.allocateDirect(grid.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        gridData.put(grid).position(0);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vertices);
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, grid.length * 4, gridData, GLES20.GL_STATIC_DRAW);
        ShortBuffer orderData = ByteBuffer.allocateDirect(order.length * 2).order(ByteOrder.nativeOrder()).asShortBuffer();
        orderData.put(order).position(0);
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, indices);
        GLES20.glBufferData(GLES20.GL_ELEMENT_ARRAY_BUFFER, order.length * 2, orderData, GLES20.GL_STATIC_DRAW);
        // GLES 2 keeps the grid's buffers and layout for the context, whichever program draws: set them once.
        GLES20.glEnableVertexAttribArray(0);
        GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 0, 0);
        GLES20.glClearColor(0f, 0f, 0f, 1f);
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glDisable(GLES20.GL_BLEND);
        // RGB_565 rows are 2 bytes a pixel: the default of 4 skews pictures of an odd width.
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 2);
    }

    @Override
    public void onSurfaceChanged(GL10 unused, int width, int height) {
        GLES20.glViewport(0, 0, width, height);
        this.width = Math.max(1, width);
        this.height = Math.max(1, height);
        amplitudeX = amplitude * 0.35f * this.height / this.width;
        cropA = crop(loadedA);
        cropB = crop(loadedB);
    }

    @Override
    public void onDrawFrame(GL10 unused) {
        // A clear first is the cheap start on a tiled GPU: nothing of the last frame has to be read back.
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        if (loadedA != pictureA) {
            delete(textureA);
            textureA = upload(pictureA);
            loadedA = pictureA;
            cropA = crop(pictureA);
        }
        if (loadedB != pictureB) {
            delete(textureB);
            textureB = upload(pictureB);
            loadedB = pictureB;
            cropB = crop(pictureB);
        }
        float m = 0f;
        if (fading && textureB != 0) {
            // Timed from the fade's first frame, so the frame that uploaded its picture doesn't eat into it.
            if (fadeStart == 0) fadeStart = time;
            double progress = (time - fadeStart) / (double) fadeNanos;
            if (progress >= 1) {
                swap();
                fading = false;
                post(onFaded);
            } else {
                // AccelerateDecelerateInterpolator's curve, in the screen's alpha steps.
                m = WallpaperRotation.fadeAlpha((float) (Math.cos((progress + 1) * Math.PI) / 2 + 0.5));
            }
        }
        boolean blending = m > 0f && blend != 0;
        int program = blending ? blend : single;
        int[] at = blending ? blendAt : singleAt;
        if (textureA == 0 || program == 0) return;
        GLES20.glUseProgram(program);
        double seconds = time / 1e9 * pace;
        // Whole turns are dropped in double, so the phases stay exact however long Home has been up.
        GLES20.glUniform2f(at[PHASE],
                (float) (2 * Math.PI * fraction(seconds / 9.0)), (float) (2 * Math.PI * fraction(seconds / 6.0)));
        GLES20.glUniform2f(at[AMP], amplitudeX, amplitude);
        GLES20.glUniform2f(at[CROP_A], cropA[0], cropA[1]);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureA);
        if (blending) {
            GLES20.glUniform2f(at[CROP_B], cropB[0], cropB[1]);
            GLES20.glUniform1f(at[MIX], m);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureB);
        }
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, COLUMNS * ROWS * 6, GLES20.GL_UNSIGNED_SHORT, 0);
        if (reportDrawn) {
            reportDrawn = false;
            postDelayed(onDrawn, 50);   // the frame is queued now and on screen within a vsync or two
        }
    }

    /** A picture's crop for this surface, with the zoom that hides the moved edges; null for no picture. */
    private float[] crop(Bitmap picture) {
        if (picture == null) return null;
        float[] scale = WallpaperSize.cropScale(picture.getWidth(), picture.getHeight(), width, height);
        scale[0] /= zoom;
        scale[1] /= zoom;
        return scale;
    }

    /** Looks up a program's uniforms once and sets the ones that never change; null for no program. */
    private int[] uniforms(int program) {
        if (program == 0) return null;
        GLES20.glUseProgram(program);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uShade"), shade);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uA"), 0);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uB"), 1);   // -1 in the single program: ignored
        return new int[] {GLES20.glGetUniformLocation(program, "uPhase"),
                GLES20.glGetUniformLocation(program, "uAmp"), GLES20.glGetUniformLocation(program, "uCropA"),
                GLES20.glGetUniformLocation(program, "uCropB"), GLES20.glGetUniformLocation(program, "uMix")};
    }

    /**
     * Milliseconds between frames outside a crossfade: the fewest frames (15, 20 or 30 a second, every 4th, 3rd or
     * 2nd vsync at 60 Hz) that keep the fastest point drifting at most 1.5 px a frame.
     */
    static long idleDelay(int strength, int speed) {
        // Peak speed of the vertex shader's drift, in px a second at the default pace: y moves 0.7 at 2π/9 plus 0.3
        // at 2π/6 of the strength, x 0.35 at 2π/6. Taking both peaks together keeps it an upper bound.
        double vertical = 0.7 * 2 * Math.PI / 9 + 0.3 * 2 * Math.PI / 6, across = 0.35 * 2 * Math.PI / 6;
        double pixelsPerSecond = strength * Math.hypot(vertical, across) * speed / 100.0;
        return pixelsPerSecond <= 15 * 1.5 ? 58 : pixelsPerSecond <= 20 * 1.5 ? 41 : 25;
    }

    private static double fraction(double turns) {
        return turns - Math.floor(turns);
    }

    private static int upload(Bitmap picture) {
        if (picture == null) return 0;
        int[] id = new int[1];
        GLES20.glGenTextures(1, id, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id[0]);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, picture, 0);
        return id[0];
    }

    private static void delete(int texture) {
        if (texture != 0) GLES20.glDeleteTextures(1, new int[]{texture}, 0);
    }

    /** 0 when the shader does not build: nothing is drawn then, Home keeps its cover picture. */
    private static int program(String fragment) {
        int program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, shader(GLES20.GL_VERTEX_SHADER, VERTEX));
        GLES20.glAttachShader(program, shader(GLES20.GL_FRAGMENT_SHADER, fragment));
        GLES20.glBindAttribLocation(program, 0, "aPos");   // both programs read the grid set up once at 0
        GLES20.glLinkProgram(program);
        int[] linked = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0);
        if (linked[0] == 0) {
            Log.w(TAG, "Wave shader failed: " + GLES20.glGetProgramInfoLog(program));
            GLES20.glDeleteProgram(program);
            return 0;
        }
        return program;
    }

    private static int shader(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        return shader;
    }

    /**
     * Exactly {@code redBlue}/{@code green} bits of colour with no alpha, depth, stencil or multisampling: nothing here
     * needs them, and a tiled GPU would move them.
     */
    private static EGLConfig chooseConfig(EGL10 egl, EGLDisplay display, int redBlue, int green) {
        int[] spec = {EGL10.EGL_RED_SIZE, redBlue, EGL10.EGL_GREEN_SIZE, green, EGL10.EGL_BLUE_SIZE, redBlue,
                EGL10.EGL_RENDERABLE_TYPE, 4 /* EGL_OPENGL_ES2_BIT */, EGL10.EGL_NONE};
        int[] count = new int[1];
        egl.eglChooseConfig(display, spec, null, 0, count);
        EGLConfig[] configs = new EGLConfig[Math.max(1, count[0])];
        egl.eglChooseConfig(display, spec, configs, configs.length, count);
        EGLConfig best = configs[0];
        int bestCost = Integer.MAX_VALUE;
        for (int i = 0; i < count[0]; i++) {
            int cost = attribute(egl, display, configs[i], EGL10.EGL_RED_SIZE)
                    + attribute(egl, display, configs[i], EGL10.EGL_GREEN_SIZE)
                    + attribute(egl, display, configs[i], EGL10.EGL_BLUE_SIZE) - 2 * redBlue - green
                    + attribute(egl, display, configs[i], EGL10.EGL_ALPHA_SIZE)
                    + attribute(egl, display, configs[i], EGL10.EGL_DEPTH_SIZE)
                    + attribute(egl, display, configs[i], EGL10.EGL_STENCIL_SIZE)
                    + 8 * attribute(egl, display, configs[i], EGL10.EGL_SAMPLES);
            if (cost < bestCost) {
                best = configs[i];
                bestCost = cost;
            }
        }
        return best;
    }

    private static int attribute(EGL10 egl, EGLDisplay display, EGLConfig config, int name) {
        int[] value = new int[1];
        return egl.eglGetConfigAttrib(display, config, name, value) ? value[0] : 0;
    }
}
