package com.android.example.myapplication;

import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import static org.junit.Assert.*;

public class PcmAudioTest {
    @Test public void stereoKeepsSignedLittleEndianSamplesInCorrectDirection() {
        byte[] interleaved = {0, (byte) 0x80, (byte) 0xff, 0x7f,
                0x34, 0x12, (byte) 0xcc, (byte) 0xed};
        byte[] tx = new byte[4]; byte[] rx = new byte[4];
        assertEquals(4, PcmIo.splitTxRx(interleaved, 8, tx, rx));
        assertArrayEquals(new byte[]{0, (byte) 0x80, 0x34, 0x12}, tx);
        assertArrayEquals(new byte[]{(byte) 0xff, 0x7f, (byte) 0xcc, (byte) 0xed}, rx);
    }
    @Test(expected = IllegalArgumentException.class)
    public void splitRejectsIncompleteStereoFrame() {
        PcmIo.splitTxRx(new byte[7], 7, new byte[4], new byte[4]);
    }
    @Test public void shortReadsDoNotLosePcmSampleBytes() throws IOException {
        byte[] source = {1, 2, 3, 4, 5, 6};
        ByteArrayInputStream input = new ByteArrayInputStream(source) {
            @Override public synchronized int read(byte[] b, int off, int len) {
                return super.read(b, off, Math.min(1, len));
            }
        };
        byte[] buffer = new byte[4];
        assertEquals(4, PcmIo.readFrames(input, buffer, 2));
        assertArrayEquals(new byte[]{1, 2, 3, 4}, buffer);
        assertEquals(2, PcmIo.readFrames(input, buffer, 2));
        assertEquals(5, buffer[0]); assertEquals(6, buffer[1]);
        assertEquals(-1, PcmIo.readFrames(input, buffer, 2));
    }
    @Test(expected = IOException.class)
    public void truncatedMonoSampleIsAnError() throws IOException {
        PcmIo.readFrames(new ByteArrayInputStream(new byte[3]), new byte[4], 2);
    }
    @Test public void durationUsesUnifiedTargetRate() {
        PcmFormat narrow = PcmFormat.fromFile(new File("uplink_auto_20261014_16000_mono_s16le.pcm"));
        assertEquals(16000, narrow.sampleRate);
        assertEquals(1.0, narrow.seconds(32000), 0.00001);
    }
    @Test(expected = IllegalArgumentException.class)
    public void unlabelledPcmIsNotSilentlyPlayedAt48k() {
        PcmFormat.fromFile(new File("speech.pcm"));
    }
    @Test(expected = IllegalArgumentException.class)
    public void stereoIsNotSilentlyTreatedAsMono() {
        PcmFormat.fromFile(new File("speech_16000_stereo_s16le.pcm"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void legacy48kPcmIsRejected() {
        PcmFormat.fromFile(new File("downlink_auto_20261012_48000_mono_s16le.pcm"));
    }
}
