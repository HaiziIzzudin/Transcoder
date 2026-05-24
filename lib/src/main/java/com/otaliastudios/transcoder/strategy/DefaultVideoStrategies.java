package com.otaliastudios.transcoder.strategy;

import androidx.annotation.NonNull;

/**
 * Contains presets and utilities for defining a {@link DefaultVideoStrategy}.
 */
public class DefaultVideoStrategies {

    private DefaultVideoStrategies() {}

    /**
     * A {@link DefaultVideoStrategy} that uses 720x1280.
     * This preset is ensured to work on any Android &gt;=4.3 devices by Android CTS,
     * assuming that the codec is available.
     *
     * @return a default video strategy
     */
    @NonNull
    public static DefaultVideoStrategy for720x1280() {
        return DefaultVideoStrategy.exact(720, 1280)
                .bitRate(2L * 1000 * 1000)
                .frameRate(30)
                .keyFrameInterval(3F)
                .build();
    }

    /**
     * A {@link DefaultVideoStrategy} that uses 360x480 (3:4),
     * ensured to work for 3:4 videos as explained by
     * https://developer.android.com/guide/topics/media/media-formats
     *
     * @return a default video strategy
     */
    @SuppressWarnings("unused")
    @NonNull
    public static DefaultVideoStrategy for360x480() {
        return DefaultVideoStrategy.exact(360, 480)
                .bitRate(500L * 1000)
                .frameRate(30)
                .keyFrameInterval(3F)
                .build();
    }

    /**
     * A {@link DefaultVideoStrategy} that uses 720x1280 with HEVC (H.265).
     *
     * @return a default video strategy using HEVC
     */
    @NonNull
    public static DefaultVideoStrategy for720x1280Hevc() {
        return DefaultVideoStrategy.exact(720, 1280)
                .asHevc()
                .bitRate(1L * 1000 * 1000)
                .frameRate(30)
                .keyFrameInterval(3F)
                .build();
    }

    /**
     * A {@link DefaultVideoStrategy} that uses 720x1280 with VP9.
     *
     * @return a default video strategy using VP9
     */
    @NonNull
    public static DefaultVideoStrategy for720x1280Vp9() {
        return DefaultVideoStrategy.exact(720, 1280)
                .asVp9()
                .bitRate(1L * 1000 * 1000)
                .frameRate(30)
                .keyFrameInterval(3F)
                .build();
    }
}
