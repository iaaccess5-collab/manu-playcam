package com.manumusicprod.playcam.core;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MIDI par le Wi-Fi selon le standard RTP-MIDI / AppleMIDI (RFC 6295) :
 * celui de « Configuration MIDI audio › Réseau » sur Mac et de rtpMIDI sur Windows.
 *
 * Le téléphone peut :
 *  - accepter la connexion d'un ordinateur (il est annoncé sur le réseau sous son nom) ;
 *  - se connecter lui-même à une session de l'ordinateur (invite).
 * Il reçoit ensuite le MIDI joué dans Logic, Ableton, MainStage ou sur le clavier relié à l'ordinateur.
 */
public final class AppleMidiSession {

    public interface Listener {
        /** Un message MIDI complet (avec octet de statut). */
        void onMidi(byte[] msg, int length, long arrivalNanos);
        /** La liste des ordinateurs connectés a changé. */
        void onPeersChanged();
        /** Information à afficher (erreur de connexion, refus…). */
        void onInfo(String message);
    }

    public static final class Peer {
        public final long ssrc;
        public volatile String name;
        public final InetAddress address;
        public final int controlPort;
        public volatile boolean connected;
        public volatile long lastSeenMs;
        public volatile double latencyMs = -1;
        public volatile long packets;
        final int token;
        final boolean weInvited;
        Peer(long ssrc, String name, InetAddress address, int controlPort, int token, boolean weInvited) {
            this.ssrc = ssrc; this.name = name; this.address = address; this.controlPort = controlPort;
            this.token = token; this.weInvited = weInvited; this.lastSeenMs = System.currentTimeMillis();
        }
        public int dataPort() { return controlPort + 1; }
    }

    private static final int IN = ('I' << 8) | 'N', OK = ('O' << 8) | 'K', NO = ('N' << 8) | 'O';
    private static final int BY = ('B' << 8) | 'Y', CK = ('C' << 8) | 'K', RS = ('R' << 8) | 'S';

    private final String name;
    private final Listener listener;
    private final long ssrc;
    private final long startNanos = System.nanoTime();
    private DatagramSocket control, data;
    private volatile boolean running;
    private final Map<Long, Peer> peers = new ConcurrentHashMap<>();
    /** Invitations envoyées par le téléphone, en attente de réponse : jeton -> peer provisoire. */
    private final Map<Integer, Peer> pending = new ConcurrentHashMap<>();
    private final Random rnd = new Random();
    private Thread tControl, tData, tTimer;

    public AppleMidiSession(String name, Listener listener) {
        this.name = name;
        this.listener = listener;
        this.ssrc = new Random().nextInt() & 0xFFFFFFFFL;
    }

    public int controlPort() { return control == null ? -1 : control.getLocalPort(); }
    public boolean isRunning() { return running; }

    /** Ouvre la paire de ports (5004/5005 par défaut, ou la paire libre suivante). */
    public synchronized void start(int preferredPort) throws IOException {
        if (running) return;
        IOException last = null;
        for (int p = preferredPort; p < preferredPort + 40; p += 2) {
            DatagramSocket c = null, d = null;
            try {
                c = new DatagramSocket(null); c.setReuseAddress(true); c.bind(new InetSocketAddress(p));
                d = new DatagramSocket(null); d.setReuseAddress(true); d.bind(new InetSocketAddress(p + 1));
                control = c; data = d; break;
            } catch (SocketException e) {
                last = e;
                if (c != null) c.close();
                if (d != null) d.close();
            }
        }
        if (control == null) throw last != null ? last : new IOException("Aucun port libre");
        running = true;
        tControl = new Thread(() -> loop(control, true), "applemidi-control");
        tData = new Thread(() -> loop(data, false), "applemidi-data");
        tTimer = new Thread(this::timer, "applemidi-sync");
        for (Thread t : new Thread[]{tControl, tData, tTimer}) { t.setDaemon(true); t.start(); }
    }

    public synchronized void stop() {
        if (!running) return;
        for (Peer p : peers.values()) sendBye(p);
        running = false;
        peers.clear(); pending.clear();
        if (control != null) control.close();
        if (data != null) data.close();
        control = null; data = null;
        listener.onPeersChanged();
    }

    public List<Peer> peers() { return new ArrayList<>(peers.values()); }

    /** Le téléphone se connecte à la session d'un ordinateur. */
    public void invite(InetAddress host, int controlPort) {
        if (!running) return;
        int token = rnd.nextInt();
        Peer p = new Peer(0, host.getHostAddress(), host, controlPort, token, true);
        pending.put(token, p);
        send(control, host, controlPort, invitation(IN, token));
        // pas de réponse : on réessaie quelques fois, puis on prévient
        new Thread(() -> {
            for (int i = 0; i < 6 && running; i++) {
                try { Thread.sleep(1500); } catch (InterruptedException e) { return; }
                Peer q = pending.get(token);
                if (q == null) return;
                if (!q.connected) send(control, host, controlPort, invitation(IN, token));
            }
            if (pending.remove(token) != null) listener.onInfo("L'ordinateur ne répond pas. Vérifie que la session réseau est activée et que les deux appareils sont sur le même Wi-Fi.");
        }, "applemidi-invite").start();
    }

    public void disconnect(long peerSsrc) {
        Peer p = peers.remove(peerSsrc);
        if (p != null) { sendBye(p); listener.onPeersChanged(); }
    }

    // ------------------------------------------------------------------ réception
    private void loop(DatagramSocket s, boolean isControl) {
        byte[] buf = new byte[1500];
        DatagramPacket pkt = new DatagramPacket(buf, buf.length);
        while (running && !s.isClosed()) {
            try {
                pkt.setLength(buf.length);
                s.receive(pkt);
                long t = System.nanoTime();
                int len = pkt.getLength();
                if (len >= 4 && (buf[0] & 0xFF) == 0xFF && (buf[1] & 0xFF) == 0xFF) {
                    handleCommand(s, isControl, buf, len, pkt.getAddress(), pkt.getPort());
                } else if (!isControl && len >= 12 && (buf[0] & 0xC0) == 0x80) {
                    handleRtp(buf, len, t);
                }
            } catch (IOException e) {
                if (!running) break;
            }
        }
    }

    private void handleCommand(DatagramSocket s, boolean isControl, byte[] b, int len, InetAddress from, int port) {
        int cmd = ((b[2] & 0xFF) << 8) | (b[3] & 0xFF);
        switch (cmd) {
            case IN: { // invitation reçue : on accepte
                if (len < 16) return;
                int token = i32(b, 8);
                long peerSsrc = u32(b, 12);
                String peerName = cstr(b, 16, len);
                if (isControl) {
                    Peer p = peers.get(peerSsrc);
                    if (p == null) { p = new Peer(peerSsrc, peerName, from, port, token, false); peers.put(peerSsrc, p); }
                    p.name = peerName.isEmpty() ? from.getHostAddress() : peerName;
                } else {
                    Peer p = peers.get(peerSsrc);
                    if (p == null) { p = new Peer(peerSsrc, peerName, from, port - 1, token, false); peers.put(peerSsrc, p); }
                    p.connected = true; p.lastSeenMs = System.currentTimeMillis();
                    listener.onPeersChanged();
                }
                send(s, from, port, invitation(OK, token));
                break;
            }
            case OK: { // réponse à notre invitation
                if (len < 16) return;
                int token = i32(b, 8);
                long peerSsrc = u32(b, 12);
                Peer p = pending.get(token);
                if (p == null) return;
                String peerName = cstr(b, 16, len);
                if (isControl) {
                    Peer real = new Peer(peerSsrc, peerName.isEmpty() ? p.name : peerName, p.address, p.controlPort, token, true);
                    pending.put(token, real);
                    send(data, p.address, p.controlPort + 1, invitation(IN, token));
                } else {
                    pending.remove(token);
                    p.connected = true; p.lastSeenMs = System.currentTimeMillis();
                    peers.put(peerSsrc, p);
                    listener.onPeersChanged();
                    sendSync(p, 0, nowTicks(), 0, 0);
                }
                break;
            }
            case NO: {
                int token = len >= 12 ? i32(b, 8) : 0;
                if (pending.remove(token) != null) listener.onInfo("L'ordinateur a refusé la connexion. Sur Mac, règle « Qui peut se connecter » sur « Tout le monde ».");
                break;
            }
            case BY: {
                if (len < 16) return;
                long peerSsrc = u32(b, 12);
                if (peers.remove(peerSsrc) != null) { listener.onPeersChanged(); listener.onInfo("L'ordinateur s'est déconnecté."); }
                break;
            }
            case CK: {
                if (len < 36) return;
                long peerSsrc = u32(b, 4);
                int count = b[8] & 0xFF;
                long ts1 = i64(b, 12), ts2 = i64(b, 20), ts3 = i64(b, 28);
                Peer p = peers.get(peerSsrc);
                if (p != null) p.lastSeenMs = System.currentTimeMillis();
                if (count == 0) {
                    reply(s, from, port, ck(1, ts1, nowTicks(), 0));
                } else if (count == 1) {
                    long now = nowTicks();
                    reply(s, from, port, ck(2, ts1, ts2, now));
                    if (p != null) p.latencyMs = (now - ts1) / 20.0; // aller simple, en ms (unités de 100 µs)
                } else if (count == 2 && p != null) {
                    p.latencyMs = (ts3 - ts1) / 20.0;
                }
                break;
            }
            default: break; // RS et autres : rien à faire côté réception
        }
    }

    private int rsCounter = 0;

    private void handleRtp(byte[] b, int len, long t) {
        long peerSsrc = u32(b, 8);
        int seq = ((b[2] & 0xFF) << 8) | (b[3] & 0xFF);
        Peer p = peers.get(peerSsrc);
        if (p != null) { p.lastSeenMs = System.currentTimeMillis(); p.packets++; }
        parseMidiList(b, 12, len, (msg, n) -> listener.onMidi(msg, n, t));
        // accusé de réception périodique : permet à l'ordinateur d'alléger son « journal »
        if (p != null && (++rsCounter % 32) == 0) {
            byte[] rs = new byte[12];
            rs[0] = (byte) 0xFF; rs[1] = (byte) 0xFF; rs[2] = 'R'; rs[3] = 'S';
            put32(rs, 4, ssrc); rs[8] = (byte) (seq >> 8); rs[9] = (byte) seq;
            send(control, p.address, p.controlPort, rs);
        }
    }

    // ------------------------------------------------------------------ décodage RTP-MIDI
    public interface MessageSink { void accept(byte[] msg, int length); }

    /**
     * Décode la section « liste de commandes MIDI » d'un paquet RTP-MIDI (RFC 6295 §3).
     * Gère l'en-tête long (B), les temps delta (Z), le running status et ignore le journal.
     */
    public static void parseMidiList(byte[] b, int offset, int end, MessageSink sink) {
        if (offset >= end) return;
        int h = b[offset] & 0xFF;
        boolean longHeader = (h & 0x80) != 0;
        boolean z = (h & 0x20) != 0;
        int listLen = h & 0x0F;
        int i = offset + 1;
        if (longHeader) { if (i >= end) return; listLen = (listLen << 8) | (b[i] & 0xFF); i++; }
        int listEnd = Math.min(end, i + listLen);
        int running = 0;
        boolean first = true;
        byte[] msg = new byte[3];
        while (i < listEnd) {
            if (!first || z) { // temps delta : 1 à 4 octets
                for (int k = 0; k < 4 && i < listEnd; k++) { int v = b[i++] & 0xFF; if ((v & 0x80) == 0) break; }
                if (i >= listEnd) break;
            }
            first = false;
            int s = b[i] & 0xFF;
            if (s >= 0x80) {
                if (s == 0xF0 || s == 0xF7 || s == 0xF4) { // SysEx (éventuellement segmenté) : on saute
                    i++;
                    while (i < listEnd) { int v = b[i++] & 0xFF; if (v == 0xF7 || v == 0xF0 || v == 0xF4) break; }
                    continue;
                }
                if (s >= 0xF8) { i++; continue; } // temps réel
                if (s >= 0xF0) { // système commun
                    int n = (s == 0xF2) ? 2 : (s == 0xF1 || s == 0xF3) ? 1 : 0;
                    i += 1 + n; running = 0; continue;
                }
                running = s; i++;
            } else if (running == 0) { i++; continue; }
            int type = running & 0xF0;
            int n = (type == 0xC0 || type == 0xD0) ? 1 : 2;
            if (i + n > listEnd) break;
            msg[0] = (byte) running;
            msg[1] = b[i];
            if (n == 2) msg[2] = b[i + 1];
            i += n;
            sink.accept(msg, n + 1);
        }
    }

    // ------------------------------------------------------------------ envoi
    private byte[] invitation(int cmd, int token) {
        byte[] nm = name.getBytes(StandardCharsets.UTF_8);
        byte[] p = new byte[16 + nm.length + 1];
        p[0] = (byte) 0xFF; p[1] = (byte) 0xFF; p[2] = (byte) (cmd >> 8); p[3] = (byte) cmd;
        put32(p, 4, 2); put32(p, 8, token); put32(p, 12, ssrc);
        System.arraycopy(nm, 0, p, 16, nm.length);
        return p;
    }

    private byte[] ck(int count, long t1, long t2, long t3) {
        byte[] p = new byte[36];
        p[0] = (byte) 0xFF; p[1] = (byte) 0xFF; p[2] = 'C'; p[3] = 'K';
        put32(p, 4, ssrc); p[8] = (byte) count;
        put64(p, 12, t1); put64(p, 20, t2); put64(p, 28, t3);
        return p;
    }

    private void sendSync(Peer p, int count, long t1, long t2, long t3) { send(data, p.address, p.dataPort(), ck(count, t1, t2, t3)); }

    private void sendBye(Peer p) {
        byte[] by = new byte[16];
        by[0] = (byte) 0xFF; by[1] = (byte) 0xFF; by[2] = 'B'; by[3] = 'Y';
        put32(by, 4, 2); put32(by, 8, p.token); put32(by, 12, ssrc);
        send(control, p.address, p.controlPort, by);
    }

    private void reply(DatagramSocket s, InetAddress to, int port, byte[] payload) { send(s, to, port, payload); }

    private void send(DatagramSocket s, InetAddress to, int port, byte[] payload) {
        if (s == null || s.isClosed()) return;
        try { s.send(new DatagramPacket(payload, payload.length, to, port)); } catch (IOException ignored) {}
    }

    /** Synchronisation d'horloge régulière (quand le téléphone a lancé la connexion) et nettoyage. */
    private void timer() {
        int tick = 0;
        while (running) {
            try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
            tick++;
            long now = System.currentTimeMillis();
            boolean changed = false;
            for (Peer p : peers.values()) {
                if (p.weInvited && p.connected && (tick < 10 ? tick % 2 == 0 : tick % 10 == 0)) sendSync(p, 0, nowTicks(), 0, 0);
                if (now - p.lastSeenMs > 70_000) { peers.remove(p.ssrc); changed = true; }
            }
            if (changed) { listener.onPeersChanged(); listener.onInfo("Connexion avec l'ordinateur perdue."); }
        }
    }

    /** Horloge de session en unités de 100 µs. */
    private long nowTicks() { return (System.nanoTime() - startNanos) / 100_000L; }

    // ------------------------------------------------------------------ octets
    private static int i32(byte[] b, int o) { return ((b[o] & 0xFF) << 24) | ((b[o + 1] & 0xFF) << 16) | ((b[o + 2] & 0xFF) << 8) | (b[o + 3] & 0xFF); }
    private static long u32(byte[] b, int o) { return i32(b, o) & 0xFFFFFFFFL; }
    private static long i64(byte[] b, int o) { return (u32(b, o) << 32) | u32(b, o + 4); }
    private static void put32(byte[] b, int o, long v) { b[o] = (byte) (v >> 24); b[o + 1] = (byte) (v >> 16); b[o + 2] = (byte) (v >> 8); b[o + 3] = (byte) v; }
    private static void put64(byte[] b, int o, long v) { put32(b, o, v >>> 32); put32(b, o + 4, v); }
    private static String cstr(byte[] b, int o, int end) {
        int e = o; while (e < end && b[e] != 0) e++;
        return new String(b, o, Math.max(0, e - o), StandardCharsets.UTF_8).trim();
    }
}
