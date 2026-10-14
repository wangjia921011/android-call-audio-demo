package com.android.example.myapplication;

import java.io.File;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Raw PCM has no header: require a format suffix instead of guessing from its contents. */
final class PcmFormat {
    static final int TARGET_SAMPLE_RATE_HZ = 16_000;
    private static final Pattern NAME = Pattern.compile(
            ".*_16000_mono_s16le\\.pcm", Pattern.CASE_INSENSITIVE);
    final int sampleRate;
    static final int FRAME_BYTES = 2;

    PcmFormat(int sampleRate) {
        if (sampleRate != TARGET_SAMPLE_RATE_HZ) {
            throw new IllegalArgumentException("仅支持 16000 Hz 单声道 PCM16");
        }
        this.sampleRate = sampleRate;
    }

    static PcmFormat fromFile(File file) {
        Matcher match = NAME.matcher(file.getName());
        if (!match.matches()) {
            throw new IllegalArgumentException("仅支持文件名末尾为 _16000_mono_s16le.pcm 的 PCM");
        }
        return new PcmFormat(TARGET_SAMPLE_RATE_HZ);
    }

    double seconds(long bytes) { return bytes / (double) (sampleRate * FRAME_BYTES); }
}
