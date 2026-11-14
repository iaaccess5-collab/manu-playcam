package com.manumusicprod.playcam.core;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Lance le récepteur du téléphone et attend « Manu Play Cam Connect » en mode test
 * (python3 companion/mmp_connect.py --console --tone --phone 127.0.0.1 --seconds 6).
 * Vérifie : présentation de l'ordinateur, MIDI reçu, son enregistré avec la bonne durée.
 */
public class ConnectTest {
    public static void main(String[] args) throws Exception {
        List<String> midi = Collections.synchronizedList(new ArrayList<>());
        final String[] sender = {null};
        final String[] host = {null};
        String mode = args.length > 1 ? args[1] : "wifi";   // « wifi » ou « usb » (câble)
        final float[] peak = {0};
        ConnectReceiver r = new ConnectReceiver("Téléphone de test", new ConnectReceiver.Listener() {
            public void onMidi(byte[] d, int o, int l, long t) { midi.add(String.format("%02X", d[o] & 0xFF)); }
            public void onSender(String name, String h) { sender[0] = name + " @ " + h; host[0] = h; }
            public void onLevel(float pl, float pr, float rms) { float p = Math.max(pl, pr); if (p > peak[0]) peak[0] = p; }
        });
        r.start();
        System.out.println("Récepteur prêt sur le port " + ConnectReceiver.PORT);
        // attendre l'ordinateur
        long until = System.currentTimeMillis() + 10_000;
        while (!r.audioActive() && System.currentTimeMillis() < until) Thread.sleep(50);
        if (mode.equals("usb")) { while (!r.viaUsb() && System.currentTimeMillis() < until) Thread.sleep(50); Thread.sleep(300); }
        File wav = new File(args.length > 0 ? args[0] : "connect.wav");
        r.startRecording(wav);
        long t0 = System.nanoTime();
        Thread.sleep(3000);
        ConnectReceiver.Take take = r.stopRecording();
        long t1 = System.nanoTime();
        r.stop();

        int fails = 0;
        boolean okSender = sender[0] != null;
        System.out.println((okSender ? "OK   " : "FAIL ") + "Ordinateur présenté : " + sender[0]); if (!okSender) fails++;
        boolean okMode = mode.equals("usb") ? "USB".equals(host[0]) : (host[0] != null && !"USB".equals(host[0]));
        System.out.println((okMode ? "OK   " : "FAIL ") + "Liaison : " + (mode.equals("usb") ? "câble USB" : "Wi-Fi") + " (reçu par " + host[0] + ")"); if (!okMode) fails++;
        boolean okMidi = midi.contains("90") && midi.contains("80");
        System.out.println((okMidi ? "OK   " : "FAIL ") + "MIDI reçu : " + midi.size() + " messages"); if (!okMidi) fails++;
        boolean okTake = take != null && take.sampleRate == 48000 && take.channels == 2;
        System.out.println((okTake ? "OK   " : "FAIL ") + "Son reçu : " + (take == null ? "rien" : take.sampleRate + " Hz, " + take.channels + " canaux")); if (!okTake) fails++;
        if (take != null) {
            double sec = take.frames / (double) take.sampleRate;
            boolean okDur = sec > 2.8 && sec < 3.2;
            System.out.println((okDur ? "OK   " : "FAIL ") + String.format("Durée enregistrée : %.3f s (attendu ≈ 3 s), trous comblés : %d trames", sec, take.gapFrames)); if (!okDur) fails++;
            double startMs = (take.firstFrameNanos - t0) / 1e6;
            boolean okStart = startMs > -60 && startMs < 60;
            System.out.println((okStart ? "OK   " : "FAIL ") + String.format("Première trame à %.1f ms du début de l'enregistrement", startMs)); if (!okStart) fails++;
            System.out.println("     crête du son : " + String.format("%.2f", peak[0]) + "   fichier : " + wav.length() + " octets");
        }
        System.out.println(fails == 0 ? "\nCONNECT : TOUS LES TESTS PASSENT" : "\n" + fails + " ÉCHEC(S)");
        System.exit(fails == 0 ? 0 : 1);
    }
}
