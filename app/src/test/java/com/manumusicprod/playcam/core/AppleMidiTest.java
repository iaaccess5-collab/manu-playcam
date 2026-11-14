package com.manumusicprod.playcam.core;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Teste le MIDI Wi-Fi (RTP-MIDI / AppleMIDI) en jouant le rôle d'un ordinateur :
 *  1. l'ordinateur se connecte au téléphone et envoie des notes ;
 *  2. le téléphone se connecte à la session de l'ordinateur et reçoit des notes.
 */
public class AppleMidiTest {

    static int fails = 0;
    static void check(String label, boolean ok, String detail) {
        if (!ok) fails++;
        System.out.println((ok ? "OK   " : "FAIL ") + label + (detail.isEmpty() ? "" : " -> " + detail));
    }

    static byte[] inv(String cmd, int token, int ssrc, String name) {
        byte[] nm = name.getBytes(StandardCharsets.UTF_8);
        byte[] p = new byte[16 + nm.length + 1];
        p[0] = (byte) 0xFF; p[1] = (byte) 0xFF; p[2] = (byte) cmd.charAt(0); p[3] = (byte) cmd.charAt(1);
        put32(p, 4, 2); put32(p, 8, token); put32(p, 12, ssrc);
        System.arraycopy(nm, 0, p, 16, nm.length);
        return p;
    }
    static void put32(byte[] b, int o, long v) { b[o] = (byte) (v >> 24); b[o + 1] = (byte) (v >> 16); b[o + 2] = (byte) (v >> 8); b[o + 3] = (byte) v; }

    /** Paquet RTP-MIDI : Note On C4, puis (delta) E4 et G4 en running status, puis Note Off C4. */
    static byte[] rtp(int seq, int ssrc) {
        byte[] list = {
            (byte) 0x90, 60, 100,          // premier message, sans delta (Z = 0)
            0x00, 64, 90,                  // delta 0, running status : E4
            (byte) 0x81, 0x00, 67, 80,     // delta sur 2 octets, running status : G4
            0x10, (byte) 0xF8,             // delta + horloge temps réel (ignorée)
            0x00, (byte) 0x80, 60, 0,      // Note Off C4
        };
        byte[] p = new byte[12 + 2 + list.length];
        p[0] = (byte) 0x80; p[1] = 0x61; p[2] = (byte) (seq >> 8); p[3] = (byte) seq;
        put32(p, 4, 1234); put32(p, 8, ssrc);
        p[12] = (byte) (0x80 | (list.length >> 8)); // en-tête long (B=1) : la liste dépasse 15 octets
        p[13] = (byte) list.length;
        System.arraycopy(list, 0, p, 14, list.length);
        return p;
    }

    static String cmdOf(byte[] b) { return "" + (char) b[2] + (char) b[3]; }

    public static void main(String[] args) throws Exception {
        // ---- 0. décodage seul, en-tête long + Z
        List<String> out = new ArrayList<>();
        byte[] longPkt = {(byte) 0xA0, 0x07, 0x00, (byte) 0xB0, 64, 127, 0x00, (byte) 0xC0, 5};
        AppleMidiSession.parseMidiList(longPkt, 0, longPkt.length, (m, n) -> out.add(hex(m, n)));
        check("Décodage en-tête long + delta initial", String.join(" ", out).equals("B0 40 7F C0 05"), String.join(" ", out));

        // ---- 1. l'ordinateur se connecte au téléphone
        List<String> received = Collections.synchronizedList(new ArrayList<>());
        List<String> infos = Collections.synchronizedList(new ArrayList<>());
        AppleMidiSession phone = new AppleMidiSession("Manu Play Cam (test)", new AppleMidiSession.Listener() {
            public void onMidi(byte[] m, int n, long t) { received.add(hex(m, n)); }
            public void onPeersChanged() {}
            public void onInfo(String s) { infos.add(s); }
        });
        phone.start(5004);
        int cp = phone.controlPort();
        InetAddress lo = InetAddress.getLoopbackAddress();

        DatagramSocket mc = new DatagramSocket(), md = new DatagramSocket();
        mc.setSoTimeout(2000); md.setSoTimeout(2000);
        byte[] buf = new byte[512];
        DatagramPacket r = new DatagramPacket(buf, buf.length);

        mc.send(new DatagramPacket(inv("IN", 77, 0x0A0B0C0D, "MacBook de Manu"), 0, 16 + 16, lo, cp));
        mc.receive(r);
        check("Invitation (contrôle) acceptée", cmdOf(buf).equals("OK"), cmdOf(buf));
        byte[] inData = inv("IN", 77, 0x0A0B0C0D, "MacBook de Manu");
        md.send(new DatagramPacket(inData, inData.length, lo, cp + 1));
        r.setLength(buf.length); md.receive(r);
        check("Invitation (données) acceptée", cmdOf(buf).equals("OK"), cmdOf(buf));
        Thread.sleep(100);
        check("Ordinateur listé comme connecté", phone.peers().size() == 1 && phone.peers().get(0).connected && phone.peers().get(0).name.equals("MacBook de Manu"),
                phone.peers().isEmpty() ? "aucun" : phone.peers().get(0).name);

        // synchronisation d'horloge lancée par l'ordinateur
        byte[] ck = new byte[36]; ck[0] = (byte) 0xFF; ck[1] = (byte) 0xFF; ck[2] = 'C'; ck[3] = 'K';
        put32(ck, 4, 0x0A0B0C0D); ck[8] = 0;
        md.send(new DatagramPacket(ck, 36, lo, cp + 1));
        r.setLength(buf.length); md.receive(r);
        check("Synchronisation d'horloge (CK 0 -> CK 1)", cmdOf(buf).equals("CK") && buf[8] == 1, "count=" + buf[8]);

        byte[] pkt = rtp(1, 0x0A0B0C0D);
        md.send(new DatagramPacket(pkt, pkt.length, lo, cp + 1));
        Thread.sleep(200);
        check("Notes reçues de l'ordinateur", String.join(" ", received).equals("90 3C 64 90 40 5A 90 43 50 80 3C 00"), String.join(" ", received));

        byte[] by = inv("BY", 77, 0x0A0B0C0D, "");
        mc.send(new DatagramPacket(by, 16, lo, cp));
        Thread.sleep(150);
        check("Déconnexion propre (BY)", phone.peers().isEmpty(), phone.peers().size() + " connecté(s)");

        // ---- 2. le téléphone se connecte à la session de l'ordinateur
        received.clear();
        DatagramSocket sc = new DatagramSocket(0, lo);
        DatagramSocket sd;
        // la session de l'ordinateur occupe deux ports consécutifs
        while (true) { try { sd = new DatagramSocket(sc.getLocalPort() + 1, lo); break; } catch (Exception e) { sc.close(); sc = new DatagramSocket(0, lo); } }
        sc.setSoTimeout(3000); sd.setSoTimeout(3000);
        phone.invite(lo, sc.getLocalPort());
        DatagramPacket q = new DatagramPacket(new byte[512], 512);
        sc.receive(q);
        byte[] qb = q.getData();
        check("Le téléphone envoie l'invitation", cmdOf(qb).equals("IN"), cmdOf(qb));
        int token = ((qb[8] & 0xFF) << 24) | ((qb[9] & 0xFF) << 16) | ((qb[10] & 0xFF) << 8) | (qb[11] & 0xFF);
        byte[] ok = inv("OK", token, 0x01020304, "Session Logic");
        sc.send(new DatagramPacket(ok, ok.length, q.getAddress(), q.getPort()));
        q.setLength(512); sd.receive(q);
        check("Invitation sur le port de données", cmdOf(q.getData()).equals("IN"), cmdOf(q.getData()));
        sd.send(new DatagramPacket(ok, ok.length, q.getAddress(), q.getPort()));
        q.setLength(512); sd.receive(q);
        check("Le téléphone lance la synchro (CK 0)", cmdOf(q.getData()).equals("CK") && q.getData()[8] == 0, "");
        Thread.sleep(100);
        check("Session de l'ordinateur connectée", phone.peers().size() == 1 && phone.peers().get(0).name.equals("Session Logic"),
                phone.peers().isEmpty() ? "aucune" : phone.peers().get(0).name);
        byte[] pkt2 = rtp(2, 0x01020304);
        sd.send(new DatagramPacket(pkt2, pkt2.length, lo, cp + 1));
        Thread.sleep(200);
        check("Notes reçues après connexion du téléphone", received.size() == 4, String.join(" ", received));

        phone.stop();
        System.out.println(fails == 0 ? "\nMIDI WI-FI : TOUS LES TESTS PASSENT" : "\n" + fails + " ÉCHEC(S)");
        System.exit(fails == 0 ? 0 : 1);
    }

    static String hex(byte[] m, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) { if (i > 0) sb.append(' '); sb.append(String.format("%02X", m[i] & 0xFF)); }
        return sb.toString();
    }
}
