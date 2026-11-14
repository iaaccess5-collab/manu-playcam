package com.manumusicprod.playcam.core;

import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Réception de « Manu Play Cam Connect » (logiciel de l'ordinateur) par le Wi-Fi :
 * le son du logiciel de musique et le MIDI joué, sur le port UDP 5010.
 *
 * Paquets (tous commencent par « MMP » + une lettre) :
 *   MMPD                         découverte : le téléphone répond « MMPH » + son nom
 *   MMPA + nom                   l'ordinateur s'annonce sur le Wi-Fi (liste des ordinateurs, choix par le nom)
 *   MMPC + nom                   choix : le téléphone choisit l'ordinateur, ou l'inverse ; MMPX (dans les deux sens) = déconnexion
 *   MMPN + nom                   l'ordinateur relié se présente (chaque seconde, sert aussi de signal de vie)
 *   MMPM + octets MIDI           message(s) MIDI
 *   MMPS + session(4) + trame(8) + fréquence(4) + canaux(1) + 3 octets réservés + PCM 16 bits little-endian
 *
 * Câble USB : l'ordinateur ouvre (par adb reverse) le port TCP 5011 du téléphone vers le sien. Le téléphone s'y
 * connecte sur 127.0.0.1:5011 et reçoit les mêmes paquets, chacun précédé de sa longueur (2 octets, big-endian).
 * Quand le câble est branché, il est prioritaire sur le Wi-Fi.
 *
 * Minutage : chaque bloc audio porte l'index de sa première trame côté ordinateur. L'instant de la trame 0
 * est estimé par le minimum de (arrivée − index / fréquence) : c'est le paquet arrivé le plus vite qui fixe
 * la référence, ce qui élimine les variations de délai du Wi-Fi. Les trous (paquets perdus) sont remplis
 * de silence pour ne jamais décaler la suite.
 */
public final class ConnectReceiver {

    public static final int PORT = 5010;
    public static final int USB_PORT = 5011;

    public interface Listener {
        void onMidi(byte[] data, int offset, int length, long arrivalNanos);
        void onSender(String name, String host);
        /** Crêtes gauche / droite et niveau moyen (0..1), environ 20 fois par seconde. */
        void onLevel(float peakL, float peakR, float rms);
    }

    /** Ordinateur vu sur le Wi-Fi (il ouvre Manu Play Cam Connect). */
    public static final class Computer {
        public final String name, host; final int port; final long seenMs;
        Computer(String n, String h, int p, long t) { name = n; host = h; port = p; seenMs = t; }
    }

    public static final class Take {
        public final File file; public final long firstFrameNanos; public final int sampleRate, channels; public final long frames; public final long gapFrames;
        Take(File f, long first, int rate, int ch, long frames, long gaps) { file = f; firstFrameNanos = first; sampleRate = rate; channels = ch; this.frames = frames; gapFrames = gaps; }
    }

    private final String phoneName;
    private final Listener listener;
    private DatagramSocket socket;
    private volatile boolean running;
    private volatile Socket usb;
    private final ConcurrentHashMap<String, Computer> seen = new ConcurrentHashMap<>();
    private volatile String chosenHost = null;          // ordinateur relié par le Wi-Fi
    private volatile String blockedHost = null;         // déconnecté à la main : ignoré quelques secondes
    private volatile long blockedUntil = 0;
    private volatile long lastUsbMs = 0;

    private volatile String senderName = null;
    private volatile String senderHost = null;
    private volatile long lastPacketMs = 0;
    private volatile long lastAudioMs = 0;
    private volatile int rate = 48000, channels = 2;
    private volatile long session = -1;
    private long t0 = Long.MAX_VALUE;          // System.nanoTime de la trame 0 de l'ordinateur
    private long expected = -1;                // prochaine trame attendue (écriture continue)

    private final Object rec = new Object();
    private WavWriter writer;
    private File writerFile;
    private File pendingFile;
    private long recFirstFrame = -1;
    private long recGaps = 0;

    public ConnectReceiver(String phoneName, Listener listener) { this.phoneName = phoneName; this.listener = listener; }

    public synchronized void start() throws IOException {
        if (running) return;
        DatagramSocket s = new DatagramSocket(null);
        s.setReuseAddress(true);
        s.setReceiveBufferSize(1 << 20);
        s.bind(new InetSocketAddress(PORT));
        socket = s; running = true;
        Thread t = new Thread(this::loop, "connect-receiver");
        t.setPriority(Thread.MAX_PRIORITY); t.setDaemon(true); t.start();
        Thread u = new Thread(this::usbLoop, "connect-usb");
        u.setPriority(Thread.MAX_PRIORITY); u.setDaemon(true); u.start();
    }

    public synchronized void stop() {
        running = false;
        if (socket != null) socket.close();
        socket = null;
        Socket c = usb; usb = null;
        if (c != null) try { c.close(); } catch (IOException ignored) {}
    }

    /** Ordinateurs vus sur le Wi-Fi depuis moins de 5 s, triés par nom. */
    public List<Computer> computers() {
        long now = System.currentTimeMillis();
        List<Computer> out = new ArrayList<>();
        for (Computer c : seen.values()) if (now - c.seenMs < 5000) out.add(c);
        out.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
        return out;
    }

    /** Relie le téléphone à cet ordinateur (choisi par son nom dans la liste). */
    public void connectTo(Computer c) {
        blockedHost = null;
        chosenHost = c.host;
        try { reply(InetAddress.getByName(c.host), c.port, "MMPC" + phoneName); } catch (IOException ignored) {}
    }

    /** Déconnecte l'ordinateur relié par le Wi-Fi. */
    public void disconnect() {
        String h = chosenHost != null ? chosenHost : senderHost;
        if (h != null && !"USB".equals(h)) {
            Computer c = seen.get(h);
            try { reply(InetAddress.getByName(h), c != null ? c.port : PORT, "MMPX"); } catch (IOException ignored) {}
            blockedHost = h; blockedUntil = System.currentTimeMillis() + 4000;
        }
        chosenHost = null; senderName = null; senderHost = null; lastPacketMs = 0;
    }

    public String chosenHost() { return chosenHost; }

    /** true si cet ordinateur a été déconnecté à la main (on ne s'y reconnecte pas tout seul). */
    public boolean isBlocked(String host) { return host.equals(blockedHost) && System.currentTimeMillis() < blockedUntil; }

    /** true quand l'ordinateur est relié par le câble USB. */
    public boolean viaUsb() { return usb != null && System.currentTimeMillis() - lastUsbMs < 4000; }

    public boolean isRunning() { return running; }
    public String senderName() { return senderName; }
    public String senderHost() { return senderHost; }
    public boolean senderActive() { return senderName != null && System.currentTimeMillis() - lastPacketMs < 4000; }
    public boolean audioActive() { return System.currentTimeMillis() - lastAudioMs < 1500; }
    public int sampleRate() { return rate; }
    public int channels() { return channels; }

    private void loop() {
        byte[] buf = new byte[2048];
        DatagramPacket pkt = new DatagramPacket(buf, buf.length);
        while (running) {
            try {
                pkt.setLength(buf.length);
                socket.receive(pkt);
                long now = System.nanoTime();
                int len = pkt.getLength();
                if (len < 4 || buf[0] != 'M' || buf[1] != 'M' || buf[2] != 'P') continue;
                InetAddress from = pkt.getAddress();
                String host = from.getHostAddress();
                if (buf[3] == 'D') { reply(from, pkt.getPort(), "MMPH" + phoneName); continue; }
                if (buf[3] == 'A') {
                    String n = new String(buf, 4, len - 4, StandardCharsets.UTF_8).trim();
                    seen.put(host, new Computer(n.isEmpty() ? host : n, host, pkt.getPort(), System.currentTimeMillis()));
                    continue;
                }
                if (buf[3] == 'C') {                    // l'ordinateur nous choisit (clic dans Connect)
                    if (host.equals(blockedHost)) blockedHost = null;
                    chosenHost = host;
                    String n = new String(buf, 4, len - 4, StandardCharsets.UTF_8).trim();
                    if (!n.isEmpty()) seen.put(host, new Computer(n, host, pkt.getPort(), System.currentTimeMillis()));
                    continue;
                }
                if (buf[3] == 'X') {
                    if (host.equals(chosenHost) || host.equals(senderHost)) {
                        chosenHost = null; senderName = null; senderHost = null; lastPacketMs = 0;
                        blockedHost = host; blockedUntil = Long.MAX_VALUE;   // déconnecté depuis l'ordinateur : pas de reconnexion auto
                    }
                    continue;
                }
                if (viaUsb()) continue;                 // le câble est prioritaire : on ignore le Wi-Fi
                if (host.equals(blockedHost) && System.currentTimeMillis() < blockedUntil) continue;
                if (buf[3] == 'N' && chosenHost == null) chosenHost = host;   // l'ordinateur nous a choisis
                if (chosenHost != null && !host.equals(chosenHost)) continue;  // un seul ordinateur à la fois
                handle(buf, len, now, host);
            } catch (IOException e) {
                if (!running) break;
            }
        }
    }

    private void handle(byte[] buf, int len, long now, String host) {
        lastPacketMs = System.currentTimeMillis();
        switch (buf[3]) {
            case 'N': {
                String n = new String(buf, 4, len - 4, StandardCharsets.UTF_8).trim();
                if (!n.equals(senderName) || !host.equals(senderHost)) { senderName = n; senderHost = host; listener.onSender(n, host); }
                break;
            }
            case 'M': if (len > 4) listener.onMidi(buf, 4, len - 4, now); break;
            case 'S': if (len > 24) audio(buf, len, now); break;
            default: break;
        }
    }

    /** Câble USB : connexion au port que l'ordinateur a ouvert avec « adb reverse ». */
    private void usbLoop() {
        byte[] buf = new byte[65536];
        while (running) {
            Socket c = null;
            try {
                c = new Socket();
                c.setTcpNoDelay(true);
                c.connect(new InetSocketAddress("127.0.0.1", USB_PORT), 500);
                c.setSoTimeout(3000);
                OutputStream out = c.getOutputStream();
                byte[] hello = ("MMPH" + phoneName).getBytes(StandardCharsets.UTF_8);
                out.write(new byte[]{(byte) (hello.length >> 8), (byte) hello.length});
                out.write(hello);
                out.flush();
                DataInputStream in = new DataInputStream(c.getInputStream());
                usb = c;
                while (running) {
                    int len = in.readUnsignedShort();
                    in.readFully(buf, 0, len);
                    long now = System.nanoTime();
                    lastUsbMs = System.currentTimeMillis();
                    if (len < 4 || buf[0] != 'M' || buf[1] != 'M' || buf[2] != 'P') continue;
                    if (buf[3] == 'D') continue;
                    handle(buf, len, now, "USB");
                }
            } catch (IOException e) {
                // pas de câble (connexion refusée) ou câble débranché : on réessaie
            } finally {
                if (c != null) try { c.close(); } catch (IOException ignored) {}
                if (usb == c) usb = null;
            }
            if (running) try { Thread.sleep(1000); } catch (InterruptedException ignored) { return; }
        }
    }

    private void reply(InetAddress to, int port, String text) {
        byte[] b = text.getBytes(StandardCharsets.UTF_8);
        try { socket.send(new DatagramPacket(b, b.length, to, port)); } catch (IOException ignored) {}
    }

    private void audio(byte[] b, int len, long now) {
        long sess = u32(b, 4);
        long frame = (u32(b, 8) << 32) | u32(b, 12);
        int r = (int) u32(b, 16);
        int ch = b[20] & 0xFF;
        if (r < 8000 || r > 192000 || ch < 1 || ch > 2) return;
        int frameBytes = 2 * ch;
        int frames = (len - 24) / frameBytes;
        if (frames <= 0) return;
        lastAudioMs = System.currentTimeMillis();

        synchronized (rec) {
            if (sess != session || r != rate || ch != channels) {
                // nouvelle diffusion (logiciel relancé, autre réglage) : on repart de zéro
                boolean sameFormat = r == rate && ch == channels;
                session = sess; rate = r; channels = ch; t0 = Long.MAX_VALUE; expected = -1;
                if (writer != null && !sameFormat) { closeWriterQuietly(); }
            }
            if (pendingFile != null && writer == null) {
                try { writer = new WavWriter(pendingFile, r, ch); writerFile = pendingFile; } catch (IOException e) { pendingFile = null; }
                pendingFile = null;
            }
            long est = now - frame * 1_000_000_000L / r;
            if (est < t0) t0 = est;

            if (writer != null) {
                if (recFirstFrame < 0) { recFirstFrame = frame; expected = frame; }
                if (frame + frames <= expected) { /* paquet en retard ou doublon : ignoré */ }
                else {
                    int skip = 0;
                    if (frame < expected) skip = (int) (expected - frame);           // chevauchement partiel
                    else if (frame > expected) {                                   // trou : silence
                        long gap = frame - expected;
                        if (gap < (long) r * 2) { writeSilence(gap); recGaps += gap; }
                        expected = frame;
                    }
                    try { writer.write(b, 24 + skip * frameBytes, (frames - skip) * frameBytes); } catch (IOException ignored) {}
                    expected = frame + frames;
                }
            }
        }
        level(b, 24, frames * frameBytes);
    }

    private final byte[] zeros = new byte[4096];
    private void writeSilence(long frames) {
        long bytes = frames * 2 * channels;
        try {
            while (bytes > 0) { int n = (int) Math.min(bytes, zeros.length); writer.write(zeros, 0, n); bytes -= n; }
        } catch (IOException ignored) {}
    }

    private long levelAt = 0;
    private float peakL = 0, peakR = 0; private double sumSq = 0; private long n = 0;
    private void level(byte[] b, int off, int bytes) {
        int ch = channels;
        int k = 0;
        for (int i = off; i + 1 < off + bytes; i += 2, k++) {
            float s = (short) ((b[i] & 0xFF) | (b[i + 1] << 8)) / 32768f;
            float a = Math.abs(s);
            if (ch == 1 || (k & 1) == 0) { if (a > peakL) peakL = a; }
            if (ch == 1 || (k & 1) == 1) { if (a > peakR) peakR = a; }
            sumSq += s * s; n++;
        }
        long now = System.currentTimeMillis();
        if (now - levelAt > 50) {
            listener.onLevel(peakL, peakR, n > 0 ? (float) Math.sqrt(sumSq / n) : 0f);
            levelAt = now; peakL = 0; peakR = 0; sumSq = 0; n = 0;
        }
    }

    /** Commence à écrire le son reçu dans un fichier WAV (à partir du prochain bloc). */
    public void startRecording(File f) {
        synchronized (rec) {
            closeWriterQuietly();
            pendingFile = f; writerFile = null; recFirstFrame = -1; recGaps = 0;
        }
    }

    /** Termine l'écriture. firstFrameNanos = instant (System.nanoTime) correspondant à la première trame du fichier. */
    public Take stopRecording() {
        synchronized (rec) {
            pendingFile = null;
            if (writer == null) return null;
            long frames = writer.getFramesWritten();
            int r = writer.getSampleRate(), ch = writer.getChannels();
            closeWriterQuietly();
            long first = (recFirstFrame < 0 || t0 == Long.MAX_VALUE) ? 0 : t0 + recFirstFrame * 1_000_000_000L / r;
            File f = writerFile; writerFile = null;
            return new Take(f, first, r, ch, frames, recGaps);
        }
    }

    private void closeWriterQuietly() {
        if (writer != null) { try { writer.close(); } catch (IOException ignored) {} }
        writer = null;
    }

    private static long u32(byte[] b, int o) {
        return (((long) (b[o] & 0xFF)) << 24) | ((b[o + 1] & 0xFF) << 16) | ((b[o + 2] & 0xFF) << 8) | (b[o + 3] & 0xFF);
    }
}
