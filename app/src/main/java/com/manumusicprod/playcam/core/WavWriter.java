package com.manumusicprod.playcam.core;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

/** Écrit un fichier WAV PCM 16 bits ; l'en-tête est complété à la fermeture. */
public final class WavWriter implements AutoCloseable {

    private final RandomAccessFile raf;
    private final int sampleRate;
    private final int channels;
    private long dataBytes = 0;

    public WavWriter(File file, int sampleRate, int channels) throws IOException {
        this.sampleRate = sampleRate;
        this.channels = channels;
        raf = new RandomAccessFile(file, "rw");
        raf.setLength(0);
        raf.write(new byte[44]);
    }

    public int getSampleRate() { return sampleRate; }
    public int getChannels() { return channels; }
    public long getFramesWritten() { return dataBytes / (2L * channels); }

    public synchronized void write(byte[] pcm16le, int offset, int length) throws IOException {
        raf.write(pcm16le, offset, length);
        dataBytes += length;
    }

    @Override
    public synchronized void close() throws IOException {
        raf.seek(0);
        byte[] h = header(sampleRate, channels, dataBytes);
        raf.write(h);
        raf.close();
    }

    static byte[] header(int rate, int ch, long dataLen) {
        byte[] h = new byte[44];
        put(h, 0, "RIFF"); le32(h, 4, (int) Math.min(0xFFFFFFFFL, 36 + dataLen)); put(h, 8, "WAVE");
        put(h, 12, "fmt "); le32(h, 16, 16); le16(h, 20, 1); le16(h, 22, ch);
        le32(h, 24, rate); le32(h, 28, rate * ch * 2); le16(h, 32, ch * 2); le16(h, 34, 16);
        put(h, 36, "data"); le32(h, 40, (int) Math.min(0xFFFFFFFFL, dataLen));
        return h;
    }

    private static void put(byte[] b, int o, String s) { for (int i = 0; i < 4; i++) b[o + i] = (byte) s.charAt(i); }
    private static void le16(byte[] b, int o, int v) { b[o] = (byte) v; b[o + 1] = (byte) (v >> 8); }
    private static void le32(byte[] b, int o, int v) { b[o] = (byte) v; b[o + 1] = (byte) (v >> 8); b[o + 2] = (byte) (v >> 16); b[o + 3] = (byte) (v >> 24); }

    /** Lit l'en-tête d'un WAV produit par cette classe : {sampleRate, channels, dataOffset, dataLength}. */
    public static long[] readInfo(File f) throws IOException {
        try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
            byte[] h = new byte[44];
            r.readFully(h);
            int ch = (h[22] & 0xFF) | ((h[23] & 0xFF) << 8);
            int rate = (h[24] & 0xFF) | ((h[25] & 0xFF) << 8) | ((h[26] & 0xFF) << 16) | ((h[27] & 0xFF) << 24);
            long len = r.length() - 44;
            return new long[]{rate, ch, 44, len};
        }
    }
}
