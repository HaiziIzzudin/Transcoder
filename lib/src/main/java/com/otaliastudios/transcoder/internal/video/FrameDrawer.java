package com.otaliastudios.transcoder.internal.video;


import android.graphics.SurfaceTexture;
import android.opengl.GLES20;
import android.opengl.Matrix;
import android.view.Surface;

import androidx.annotation.GuardedBy;
import androidx.annotation.NonNull;

import com.otaliastudios.opengl.draw.GlRect;
import com.otaliastudios.opengl.program.GlTextureProgram;
import com.otaliastudios.opengl.texture.GlTexture;
import com.otaliastudios.transcoder.internal.utils.Logger;

/**
 * The purpose of this class is to create a {@link Surface} associated to a certain GL texture.
 *
 * The Surface is exposed through {@link #getSurface()} and we expect someone to draw there.
 * Typically this will be a {@link android.media.MediaCodec} instance, using this surface as output.
 *
 * When {@link #drawFrame()} is called, this class will wait for a new frame from MediaCodec,
 * and draw it on the current EGL surface. The class itself does no GL initialization, and will
 * draw on whatever surface is current.
 *
 * NOTE: By default, the Surface will be using a BufferQueue in asynchronous mode, so we
 * can potentially drop frames.
 */
class FrameDrawer {
    private static final Logger LOG = new Logger("FrameDrawer");

    private static final long NEW_IMAGE_TIMEOUT_MILLIS = 10000;

    private SurfaceTexture mSurfaceTexture;
    private Surface mSurface;

    private GlTexture mInputTexture;
    private GlTextureProgram mProgram;
    private DownscalingOesProgram mDownscalingProgram;
    private GlRect mDrawable;

    private int mSourceWidth = 0;
    private int mSourceHeight = 0;
    private int mTargetWidth = 0;
    private int mTargetHeight = 0;

    private float mScaleX = 1F;
    private float mScaleY = 1F;
    private int mRotation = 0;
    private boolean mFlipY = false;

    @GuardedBy("mFrameAvailableLock")
    private boolean mFrameAvailable;
    private final Object mFrameAvailableLock = new Object();

    /**
     * Creates an VideoDecoderOutput using the current EGL context (rather than establishing a
     * new one). Creates a Surface that can be passed to MediaCodec.configure().
     */
    public FrameDrawer() {
        mInputTexture = new GlTexture();
        mProgram = new GlTextureProgram();
        mProgram.setTexture(mInputTexture);
        mDrawable = new GlRect();

        // Even if we don't access the SurfaceTexture after the constructor returns, we
        // still need to keep a reference to it.  The Surface doesn't retain a reference
        // at the Java level, so if we don't either then the object can get GCed, which
        // causes the native finalizer to run.
        mSurfaceTexture = new SurfaceTexture(mInputTexture.getId());
        mSurfaceTexture.setOnFrameAvailableListener(new SurfaceTexture.OnFrameAvailableListener() {
            @Override
            public void onFrameAvailable(SurfaceTexture surfaceTexture) {
                LOG.v("New frame available");
                synchronized (mFrameAvailableLock) {
                    if (mFrameAvailable) {
                        throw new RuntimeException("mFrameAvailable already set, frame could be dropped");
                    }
                    mFrameAvailable = true;
                    mFrameAvailableLock.notifyAll();
                }
            }
        });
        mSurface = new Surface(mSurfaceTexture);
    }

    /**
     * Sets the frame scale along the two axes.
     * @param scaleX x scale
     * @param scaleY y scale
     */
    public void setScale(float scaleX, float scaleY) {
        mScaleX = scaleX;
        mScaleY = scaleY;
    }

    /**
     * Sets the desired frame rotation with respect
     * to its natural orientation.
     * @param rotation rotation
     */
    public void setRotation(int rotation) {
        mRotation = rotation;
    }

    public void setFlipY(boolean flipY) {
        mFlipY = flipY;
    }

    /**
     * Returns a Surface to draw onto.
     * @return the output surface
     */
    @NonNull
    public Surface getSurface() {
        return mSurface;
    }

    public void setResolutions(int sourceWidth, int sourceHeight, int targetWidth, int targetHeight) {
        mSourceWidth = sourceWidth;
        mSourceHeight = sourceHeight;
        mTargetWidth = targetWidth;
        mTargetHeight = targetHeight;

        // If downscaling is occurring, instantiate the downscaling shader program
        if (mSourceWidth > mTargetWidth || mSourceHeight > mTargetHeight) {
            try {
                mDownscalingProgram = new DownscalingOesProgram();
                mDownscalingProgram.setTexture(mInputTexture);
                LOG.i("Downscaling SSAA program initialized for downscaling from " + mSourceWidth + "x" + mSourceHeight + " to " + mTargetWidth + "x" + mTargetHeight);
            } catch (Exception e) {
                LOG.e("Failed to initialize downscaling SSAA program. Falling back to default bilinear.", e);
                if (mDownscalingProgram != null) {
                    mDownscalingProgram.release();
                    mDownscalingProgram = null;
                }
            }
        }
    }

    /**
     * Discard all resources held by this class, notably the EGL context.
     */
    public void release() {
        mProgram.release();
        if (mDownscalingProgram != null) {
            mDownscalingProgram.release();
            mDownscalingProgram = null;
        }
        mSurface.release();
        // this causes a bunch of warnings that appear harmless but might confuse someone:
        // W BufferQueue: [unnamed-3997-2] cancelBuffer: BufferQueue has been abandoned!
        // mSurfaceTexture.release();
        mSurface = null;
        mSurfaceTexture = null;
        mDrawable = null;
        mProgram = null;
    }

    /**
     * Waits for a new frame drawn into our surface (see {@link #getSurface()}),
     * then draws it using OpenGL.
     */
    public void drawFrame() {
        awaitNewFrame();
        drawNewFrame();
    }

    /**
     * Latches the next buffer into the texture.  Must be called from the thread that created
     * the VideoDecoderOutput object, after the onFrameAvailable callback has signaled that new
     * data is available.
     */
    private void awaitNewFrame() {
        synchronized (mFrameAvailableLock) {
            while (!mFrameAvailable) {
                try {
                    // Wait for onFrameAvailable() to signal us. Use a timeout to avoid
                    // stalling the test if it doesn't arrive.
                    mFrameAvailableLock.wait(NEW_IMAGE_TIMEOUT_MILLIS);
                    if (!mFrameAvailable) {
                        // TODO: if "spurious wakeup", continue while loop
                        // TODO: what does this mean? ^
                        throw new RuntimeException("Surface frame wait timed out");
                    }
                } catch (InterruptedException ie) {
                    throw new RuntimeException(ie);
                }
            }
            mFrameAvailable = false;
        }
        // Latch the data.
        mSurfaceTexture.updateTexImage();
    }

    /**
     * Draws the data from SurfaceTexture onto the current EGL surface.
     */
    private void drawNewFrame() {
        GlTextureProgram activeProgram = (mDownscalingProgram != null) ? mDownscalingProgram : mProgram;

        activeProgram.getTextureTransform(); // Load it
        mSurfaceTexture.getTransformMatrix(activeProgram.getTextureTransform());

        // Invert the scale.
        float glScaleX = 1F / mScaleX;
        float glScaleY = 1F / mScaleY;
        // Compensate before scaling.
        float glTranslX = (1F - glScaleX) / 2F;
        float glTranslY = (1F - glScaleY) / 2F;
        Matrix.translateM(activeProgram.getTextureTransform(), 0, glTranslX, glTranslY, 0);
        // Scale.
        Matrix.scaleM(activeProgram.getTextureTransform(), 0, glScaleX, glScaleY, 1);
        // Apply rotation and flip.
        Matrix.translateM(activeProgram.getTextureTransform(), 0, 0.5F, 0.5F, 0);
        Matrix.rotateM(activeProgram.getTextureTransform(), 0, mRotation, 0, 0, 1);
        if (mFlipY) {
            Matrix.scaleM(activeProgram.getTextureTransform(), 0, 1F, -1F, 1F);
        }
        Matrix.translateM(activeProgram.getTextureTransform(), 0, -0.5F, -0.5F, 0);

        if (activeProgram == mDownscalingProgram) {
            mDownscalingProgram.setPixelSize(1.0f / mTargetWidth, 1.0f / mTargetHeight);
        }

        // Draw.
        activeProgram.draw(mDrawable);
    }

    private static class DownscalingOesProgram extends GlTextureProgram {
        private static final String VERTEX_SHADER =
                "uniform mat4 uMVPMatrix;\n" +
                "uniform mat4 uTexMatrix;\n" +
                "uniform vec2 uPixelSize;\n" +
                "attribute vec4 aPosition;\n" +
                "attribute vec4 aTextureCoord;\n" +
                "varying vec2 vTextureCoord;\n" +
                "varying vec2 vPixelOffsetX;\n" +
                "varying vec2 vPixelOffsetY;\n" +
                "void main() {\n" +
                "    gl_Position = uMVPMatrix * aPosition;\n" +
                "    vTextureCoord = (uTexMatrix * aTextureCoord).xy;\n" +
                "    vPixelOffsetX = (uTexMatrix * vec4(uPixelSize.x, 0.0, 0.0, 0.0)).xy;\n" +
                "    vPixelOffsetY = (uTexMatrix * vec4(0.0, uPixelSize.y, 0.0, 0.0)).xy;\n" +
                "}\n";

        private static final String FRAGMENT_SHADER =
                "#extension GL_OES_EGL_image_external : require\n" +
                "precision mediump float;\n" +
                "varying vec2 vTextureCoord;\n" +
                "varying vec2 vPixelOffsetX;\n" +
                "varying vec2 vPixelOffsetY;\n" +
                "uniform samplerExternalOES sTexture;\n" +
                "void main() {\n" +
                "    vec4 color = vec4(0.0);\n" +
                "    for (int y = -2; y <= 2; y++) {\n" +
                "        for (int x = -2; x <= 2; x++) {\n" +
                "            vec2 offset = float(x) * 0.2 * vPixelOffsetX + float(y) * 0.2 * vPixelOffsetY;\n" +
                "            color += texture2D(sTexture, vTextureCoord + offset);\n" +
                "        }\n" +
                "    }\n" +
                "    gl_FragColor = color / 25.0;\n" +
                "}\n";

        private int uPixelSizeLocation = -1;

        public DownscalingOesProgram() {
            super(VERTEX_SHADER, FRAGMENT_SHADER);
            uPixelSizeLocation = GLES20.glGetUniformLocation(getHandle(), "uPixelSize");
        }

        public void setPixelSize(float w, float h) {
            if (uPixelSizeLocation != -1) {
                GLES20.glUniform2f(uPixelSizeLocation, w, h);
            }
        }
    }
}
