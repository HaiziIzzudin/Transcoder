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

    private GlTexture mProgramTexture;
    private GlTextureProgram mProgram;
    private GlRect mDrawable;

    private GlTexture m2DTexture;
    private GlTextureProgram mProgram2D;
    private int mFboId = 0;
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
        GlTexture texture = new GlTexture();
        mProgram = new GlTextureProgram();
        mProgram.setTexture(texture);
        mDrawable = new GlRect();

        // Even if we don't access the SurfaceTexture after the constructor returns, we
        // still need to keep a reference to it.  The Surface doesn't retain a reference
        // at the Java level, so if we don't either then the object can get GCed, which
        // causes the native finalizer to run.
        mSurfaceTexture = new SurfaceTexture(texture.getId());
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

        // Only initialize FBO if downscaling is actually occurring
        if (mSourceWidth > mTargetWidth || mSourceHeight > mTargetHeight) {
            try {
                m2DTexture = new GlTexture(mSourceWidth, mSourceHeight);
                
                // Set mipmapping parameters
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, m2DTexture.getId());
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_LINEAR);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);

                // Create FBO
                int[] framebuffers = new int[1];
                GLES20.glGenFramebuffers(1, framebuffers, 0);
                mFboId = framebuffers[0];
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, mFboId);
                GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, m2DTexture.getId(), 0);

                int status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);
                if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                    throw new RuntimeException("Framebuffer not complete: " + status);
                }
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);

                mProgram2D = new GlTextureProgram();
                mProgram2D.setTexture(m2DTexture);
                LOG.i("FBO initialized successfully for downscaling from " + mSourceWidth + "x" + mSourceHeight + " to " + mTargetWidth + "x" + mTargetHeight);
            } catch (Exception e) {
                LOG.e("Failed to initialize FBO for mipmapped downscaling. Falling back to single-pass.", e);
                releaseFbo();
            }
        }
    }

    private void releaseFbo() {
        if (mProgram2D != null) {
            mProgram2D.release();
            mProgram2D = null;
        }
        if (m2DTexture != null) {
            m2DTexture.release();
            m2DTexture = null;
        }
        if (mFboId != 0) {
            int[] framebuffers = new int[]{mFboId};
            GLES20.glDeleteFramebuffers(1, framebuffers, 0);
            mFboId = 0;
        }
    }

    /**
     * Discard all resources held by this class, notably the EGL context.
     */
    public void release() {
        mProgram.release();
        mSurface.release();
        releaseFbo();
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
        if (mFboId != 0 && mProgram2D != null && m2DTexture != null) {
            // PASS 1: Render OES texture onto the FBO (1:1 copy at source resolution)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, mFboId);
            GLES20.glViewport(0, 0, mSourceWidth, mSourceHeight);

            mSurfaceTexture.getTransformMatrix(mProgram.getTextureTransform());
            mProgram.draw(mDrawable);

            // Generate mipmaps for the 2D texture
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, m2DTexture.getId());
            GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);

            // Unbind FBO
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);

            // PASS 2: Render the 2D texture with mipmaps to target surface with scale, rotate, flip
            GLES20.glViewport(0, 0, mTargetWidth, mTargetHeight);

            Matrix.setIdentityM(mProgram2D.getTextureTransform(), 0);
            // Invert the scale.
            float glScaleX = 1F / mScaleX;
            float glScaleY = 1F / mScaleY;
            // Compensate before scaling.
            float glTranslX = (1F - glScaleX) / 2F;
            float glTranslY = (1F - glScaleY) / 2F;
            Matrix.translateM(mProgram2D.getTextureTransform(), 0, glTranslX, glTranslY, 0);
            // Scale.
            Matrix.scaleM(mProgram2D.getTextureTransform(), 0, glScaleX, glScaleY, 1);
            // Apply rotation and flip.
            Matrix.translateM(mProgram2D.getTextureTransform(), 0, 0.5F, 0.5F, 0);
            Matrix.rotateM(mProgram2D.getTextureTransform(), 0, mRotation, 0, 0, 1);
            if (mFlipY) {
                Matrix.scaleM(mProgram2D.getTextureTransform(), 0, 1F, -1F, 1F);
            }
            Matrix.translateM(mProgram2D.getTextureTransform(), 0, -0.5F, -0.5F, 0);

            mProgram2D.draw(mDrawable);
        } else {
            // Fallback: single-pass rendering
            mSurfaceTexture.getTransformMatrix(mProgram.getTextureTransform());
            // Invert the scale.
            float glScaleX = 1F / mScaleX;
            float glScaleY = 1F / mScaleY;
            // Compensate before scaling.
            float glTranslX = (1F - glScaleX) / 2F;
            float glTranslY = (1F - glScaleY) / 2F;
            Matrix.translateM(mProgram.getTextureTransform(), 0, glTranslX, glTranslY, 0);
            // Scale.
            Matrix.scaleM(mProgram.getTextureTransform(), 0, glScaleX, glScaleY, 1);
            // Apply rotation and flip.
            Matrix.translateM(mProgram.getTextureTransform(), 0, 0.5F, 0.5F, 0);
            Matrix.rotateM(mProgram.getTextureTransform(), 0, mRotation, 0, 0, 1);
            if (mFlipY) {
                Matrix.scaleM(mProgram.getTextureTransform(), 0, 1F, -1F, 1F);
            }
            Matrix.translateM(mProgram.getTextureTransform(), 0, -0.5F, -0.5F, 0);

            // Draw.
            mProgram.draw(mDrawable);
        }
    }
}
