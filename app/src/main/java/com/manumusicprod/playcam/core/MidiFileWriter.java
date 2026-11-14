package com.manumusicprod.playcam.core;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Enregistre les événements MIDI d'une performance et les écrit dans un fichier Standard MIDI
 * (format 0, 960 tics par noire, tempo de référence 120 BPM) en conservant le minutage réel.
 * Les temps sont relatifs au début de la vidéo : le fichier se cale directement sur l'image.
 */
public final class MidiFileWriter {

    public static final int PPQ = 960;
    public static final int TEMPO_US = 500_000; // 120 BPM

    private static final class Ev {
        final long tNanos; final byte[] bytes; final int order;
        Ev(long tNanos, byte[] bytes, int order) { this.tNanos = tNanos; this.bytes = bytes; this.order = order; }
    }

    private final List<Ev> events = new ArrayList<>();
    private int seq = 0;

    /** Ajoute un message canal (note, contrôleur, program change, pitch bend…) à l'instant t (ns depuis le début). */
    public synchronized void add(long tNanos, int status, int d1, int d2) {
        if (status < 0x80 || status >= 0xF0) return;
        int type = status & 0xF0;
        byte[] b = (type == 0xC0 || type == 0xD0)
                ? new byte[]{(byte) status, (byte) (d1 & 0x7F)}
                : new byte[]{(byte) status, (byte) (d1 & 0x7F), (byte) (Math.max(d2, 0) & 0x7F)};
        events.add(new Ev(Math.max(0, tNanos), b, seq++));
    }

    public synchronized int size() { return events.size(); }

    public static long nanosToTicks(long nanos) {
        // tics = ns * PPQ / (tempo en ns)
        return Math.round(nanos * (double) PPQ / (TEMPO_US * 1000.0));
    }

    public synchronized void write(File file, String trackName, long endNanos) throws IOException {
        try (OutputStream out = new FileOutputStream(file)) { out.write(toBytes(trackName, endNanos)); }
    }

    public synchronized byte[] toBytes(String trackName, long endNanos) {
        List<Ev> sorted = new ArrayList<>(events);
        sorted.sort((x, y) -> x.tNanos != y.tNanos ? Long.compare(x.tNanos, y.tNanos) : Integer.compare(x.order, y.order));

        ByteArrayOutputStream trk = new ByteArrayOutputStream();
        byte[] nameBytes = trackName.getBytes(StandardCharsets.UTF_8);
        writeVlq(trk, 0); trk.write(0xFF); trk.write(0x03); writeVlq(trk, nameBytes.length); trk.write(nameBytes, 0, nameBytes.length);
        writeVlq(trk, 0); trk.write(0xFF); trk.write(0x51); trk.write(0x03);
        trk.write((TEMPO_US >> 16) & 0xFF); trk.write((TEMPO_US >> 8) & 0xFF); trk.write(TEMPO_US & 0xFF);
        writeVlq(trk, 0); trk.write(0xFF); trk.write(0x58); trk.write(0x04); trk.write(4); trk.write(2); trk.write(24); trk.write(8);

        long lastTick = 0;
        for (Ev e : sorted) {
            long tick = nanosToTicks(e.tNanos);
            writeVlq(trk, tick - lastTick); lastTick = tick;
            trk.write(e.bytes, 0, e.bytes.length);
        }
        long endTick = Math.max(lastTick, nanosToTicks(endNanos));
        writeVlq(trk, endTick - lastTick); trk.write(0xFF); trk.write(0x2F); trk.write(0x00);

        byte[] body = trk.toByteArray();
        ByteArrayOutputStream f = new ByteArrayOutputStream();
        f.write('M'); f.write('T'); f.write('h'); f.write('d');
        writeInt(f, 6); writeShort(f, 0); writeShort(f, 1); writeShort(f, PPQ);
        f.write('M'); f.write('T'); f.write('r'); f.write('k');
        writeInt(f, body.length); f.write(body, 0, body.length);
        return f.toByteArray();
    }

    static void writeVlq(ByteArrayOutputStream o, long v) {
        if (v < 0) v = 0;
        long buf = v & 0x7F;
        while ((v >>= 7) > 0) { buf <<= 8; buf |= ((v & 0x7F) | 0x80); }
        while (true) { o.write((int) (buf & 0xFF)); if ((buf & 0x80) != 0) buf >>= 8; else break; }
    }

    private static void writeInt(ByteArrayOutputStream o, int v) {
        o.write((v >>> 24) & 0xFF); o.write((v >>> 16) & 0xFF); o.write((v >>> 8) & 0xFF); o.write(v & 0xFF);
    }

    private static void writeShort(ByteArrayOutputStream o, int v) { o.write((v >>> 8) & 0xFF); o.write(v & 0xFF); }
}
