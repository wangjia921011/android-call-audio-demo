package com.android.example.myapplication;

import android.media.AudioTrack;
import android.os.SystemClock;
import java.util.function.BooleanSupplier;

final class PcmPlayback {
    private PcmPlayback() { }

    /** AudioTrack.write queues PCM; EOF is not playback completion. */
    static void drain(AudioTrack track, long bytes, int frameBytes, BooleanSupplier running) {
        long target = bytes / frameBytes;
        long head = Integer.toUnsignedLong(track.getPlaybackHeadPosition());
        long remaining = Math.max(0, target - head);
        long deadline = SystemClock.elapsedRealtime()
                + remaining * 1000 / track.getSampleRate() + 2000;
        while (running.getAsBoolean()
                && Integer.toUnsignedLong(track.getPlaybackHeadPosition()) < target) {
            if (SystemClock.elapsedRealtime() >= deadline) {
                throw new IllegalStateException("播放缓冲区未排空，音频路由可能已停止");
            }
            SystemClock.sleep(10);
        }
    }
}
