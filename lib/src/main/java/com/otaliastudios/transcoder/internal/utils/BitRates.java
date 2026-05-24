package com.otaliastudios.transcoder.internal.utils;

import android.media.MediaFormat;
import com.otaliastudios.transcoder.internal.media.MediaFormatConstants;

/**
 * Utilities for bit rate estimation.
 */
public class BitRates {

    // For AVC this should be a reasonable default.
    // https://stackoverflow.com/a/5220554/4288782
    public static long estimateVideoBitRate(int width, int height, int frameRate) {
        return estimateVideoBitRate(width, height, frameRate, MediaFormatConstants.MIMETYPE_VIDEO_AVC);
    }

    public static long estimateVideoBitRate(int width, int height, int frameRate, String mimeType) {
        float factor = 0.07F * 2; // AVC base factor
        if (MediaFormatConstants.MIMETYPE_VIDEO_HEVC.equalsIgnoreCase(mimeType) || 
            MediaFormatConstants.MIMETYPE_VIDEO_VP9.equalsIgnoreCase(mimeType)) {
            factor = 0.07F; // HEVC/VP9 are roughly twice as efficient
        }
        return (long) (factor * width * height * frameRate);
    }

    // Wildly assuming a 0.75 compression rate for AAC.
    @SuppressWarnings("UnnecessaryLocalVariable")
    public static long estimateAudioBitRate(int channels, int sampleRate) {
        int bitsPerSample = 16;
        long samplesPerSecondPerChannel = (long) sampleRate;
        long bitsPerSecond = bitsPerSample * samplesPerSecondPerChannel * channels;
        double codecCompression = 0.75D; // Totally random.
        return (long) (bitsPerSecond * codecCompression);
    }
}
