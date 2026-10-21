package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.GLUtils;
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
 * A slow, subtle wave over the wallpaper (wallpaper_motion), drawn with OpenGL ES 2.0 on its own surface under Home's
 * window, so the UI above is never redrawn for it. Kept cheap for 1 GB TVs: it renders 960 px wide and the display
 * scales it up, the wave is worked out per vertex on a coarse grid so each pixel is one texture read (two during a
 * crossfade), it draws 15 frames a second (30 while crossfading), and nothing runs while Home is hidden.
 */
final class WallpaperWave extends GLSurfaceView implements GLSurfaceView.Renderer {
    static final int RENDER_WIDTH = 960;
    /** Of the screen height: about 19 px on a 1080p screen, a gentle shimmer rather than a swell. */
    private static final float AMPLITUDE = 0.018f;
    /** The zoom that keeps the moved edges inside the picture; a still cover in its place should match it. */
    static final float ZOOM = 1f + 2f * AMPLITUDE;
    private static final int COLUMNS = 32, ROWS = 18;
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

    private final float shade;
    private final Runnable onDrawn;
    private volatile float mix;
    private volatile long time;
    private volatile boolean reportDrawn;
    private boolean playing;

    // GL thread only. The pictures change in events, which can run while there is no context; the textures follow
    // them at the next frame, where there always is one.
    private Bitmap pictureA, pictureB, loadedA, loadedB;
    private int textureA, textureB, single, blend, vertices, indices, width = 1, height = 1;

    private final Choreographer.FrameCallback driver = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            if (!playing) return;
            time = frameTimeNanos;
            requestRender();
            // 15 frames a second is smooth for a drift of half a pixel a frame; a crossfade gets 30 so the blend
            // has no visible steps. The delay lands on the next vsync after it.
            Choreographer.getInstance().postFrameCallbackDelayed(this, mix > 0f ? 25 : 58);
        }
    };

    WallpaperWave(Context context, int renderHeight, int dimPercent, Runnable onDrawn) {
        super(context);
        shade = 1f - dimPercent / 100f;
        this.onDrawn = onDrawn;
        setEGLContextClientVersion(2);
        setEGLConfigChooser(WallpaperWave::chooseConfig);
        getHolder().setFormat(PixelFormat.OPAQUE);
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
            mix = 0f;
        });
        requestRender();
    }

    /** Starts a crossfade to {@code picture}; {@link #setMix} moves it and {@link #commit} ends it. */
    void fadeTo(Bitmap picture) {
        queueEvent(() -> pictureB = picture);
    }

    void setMix(float value) {
        mix = value;
    }

    void commit() {
        // On the GL thread, so no frame falls between the swap and the mix going back to 0.
        queueEvent(() -> {
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
            mix = 0f;
        });
        requestRender();
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
    }

    @Override
    public void onDrawFrame(GL10 unused) {
        // A clear first is the cheap start on a tiled GPU: nothing of the last frame has to be read back.
        GLES20.glClearColor(0f, 0f, 0f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        if (loadedA != pictureA) {
            delete(textureA);
            textureA = upload(pictureA);
            loadedA = pictureA;
        }
        if (loadedB != pictureB) {
            delete(textureB);
            textureB = upload(pictureB);
            loadedB = pictureB;
        }
        float m = textureB == 0 ? 0f : mix;
        int program = m > 0f ? blend : single;
        if (textureA == 0 || program == 0) return;
        GLES20.glUseProgram(program);
        double seconds = time / 1e9;
        // Whole turns are dropped in double, so the phases stay exact however long Home has been up.
        GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "uPhase"),
                (float) (2 * Math.PI * fraction(seconds / 9.0)), (float) (2 * Math.PI * fraction(seconds / 6.0)));
        GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "uAmp"), AMPLITUDE * 0.35f * height / width, AMPLITUDE);
        crop(program, "uCropA", loadedA);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uShade"), shade);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureA);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uA"), 0);
        if (program == blend) {
            crop(program, "uCropB", loadedB);
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uMix"), m);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureB);
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uB"), 1);
        }
        int position = GLES20.glGetAttribLocation(program, "aPos");
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vertices);
        GLES20.glEnableVertexAttribArray(position);
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, 0);
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, indices);
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, COLUMNS * ROWS * 6, GLES20.GL_UNSIGNED_SHORT, 0);
        if (reportDrawn) {
            reportDrawn = false;
            postDelayed(onDrawn, 50);   // the frame is queued now and on screen within a vsync or two
        }
    }

    private void crop(int program, String name, Bitmap picture) {
        float[] scale = WallpaperSize.cropScale(picture.getWidth(), picture.getHeight(), width, height);
        GLES20.glUniform2f(GLES20.glGetUniformLocation(program, name), scale[0] / ZOOM, scale[1] / ZOOM);
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

    /** RGB888 with no depth, stencil or multisampling: nothing here needs them, and a tiled GPU would move them. */
    private static EGLConfig chooseConfig(EGL10 egl, EGLDisplay display) {
        int[] spec = {EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8,
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
                    + attribute(egl, display, configs[i], EGL10.EGL_BLUE_SIZE) - 24
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
