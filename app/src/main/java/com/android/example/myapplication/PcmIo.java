package com.android.example.myapplication;

import java.io.IOException;
import java.io.InputStream;

final class PcmIo {
    private PcmIo() { }

    /** Preserve partial frames across short reads; reject a truncated final frame. */
    static int readFrames(InputStream input, byte[] buffer, int frameBytes) throws IOException {
        if (frameBytes <= 0 || buffer.length == 0 || buffer.length % frameBytes != 0) {
            throw new IllegalArgumentException("PCM 缓冲区必须按帧对齐");
        }
        int size = 0;
        while (size < buffer.length) {
            int count = input.read(buffer, size, buffer.length - size);
            if (count == -1) break;
            if (count == 0) throw new IOException("PCM 文件读取没有进展");
            size += count;
        }
        if (size % frameBytes != 0) throw new IOException("PCM 文件末尾不是完整帧");
        return size == 0 ? -1 : size;
    }

    /** UNISOC VOICE_CALL layout: little-endian TX(L), RX(R). Returns bytes per mono output. */
    static int splitTxRx(byte[] stereo, int size, byte[] tx, byte[] rx) {
        if (size < 0 || size % 4 != 0 || size > stereo.length
                || tx.length < size / 2 || rx.length < size / 2) {
            throw new IllegalArgumentException("双声道 PCM 帧不完整或输出缓冲区过小");
        }
        for (int i = 0, o = 0; i < size; i += 4, o += 2) {
            tx[o] = stereo[i]; tx[o + 1] = stereo[i + 1];
            rx[o] = stereo[i + 2]; rx[o + 1] = stereo[i + 3];
        }
        return size / 2;
    }
}
