package com.manumusicprod.playcam.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Tests du cœur musical (sans dépendance Android).
 * Lancement : javac + java (voir README), ou ./gradlew test si JUnit est ajouté.
 */
public class CoreTest {

    static int fails = 0;

    static void check(String label, String got, String want) {
        boolean ok = want.equals(got);
        if (!ok) fails++;
        System.out.println((ok ? "OK   " : "FAIL ") + label + " -> " + got + (ok ? "" : "   (attendu " + want + ")"));
    }

    static String chord(int... notes) {
        ChordDetector.Chord c = ChordDetector.detect(notes, ChordDetector.SPELL_MIXED);
        return c == null ? "null" : c.name;
    }

    public static void main(String[] args) throws Exception {
        // C4 = 60
        check("Cmaj7", chord(48, 64, 67, 71), "Cmaj7");
        check("Dm7", chord(50, 65, 69, 72), "Dm7");
        check("G7", chord(43, 59, 65, 67), "G7");
        check("A♭maj7", chord(44, 60, 63, 67), "A♭maj7");
        check("Bm7♭5", chord(47, 62, 65, 69), "Bm7♭5");
        check("C7sus4", chord(48, 65, 67, 70), "C7sus4");
        check("D♭maj9", chord(49, 60, 63, 65, 68), "D♭maj9");
        check("C (triade)", chord(60, 64, 67), "C");
        check("Am", chord(57, 60, 64), "Am");
        check("C/E (renversement)", chord(52, 60, 67), "C/E");
        check("C6 basse C", chord(48, 64, 67, 69), "C6");
        check("Am7 basse A", chord(45, 64, 67, 72), "Am7");
        check("G7 sans quinte", chord(43, 59, 65), "G7");
        check("Cm9", chord(48, 63, 70, 74), "Cm9");
        check("F♯dim7", chord(54, 57, 60, 63), "F♯dim7");
        check("C7♯9", chord(48, 64, 70, 75), "C7♯9");
        check("G13", chord(43, 53, 59, 64), "G13");
        check("Csus2", chord(48, 62, 67), "Csus2");
        check("Une note", chord(60), "null");
        check("Rien", chord(), "null");

        // Parseur MIDI : running status + note on vélocité 0 + horloge intercalée
        final List<String> got = new ArrayList<>();
        MidiParser p = new MidiParser((s, d1, d2, t) -> got.add(Integer.toHexString(s) + ":" + d1 + ":" + d2));
        byte[] stream = {(byte) 0x90, 60, 100, (byte) 0xF8, 64, 90, 67, 0, (byte) 0xB0, 64, 127, (byte) 0xC0, 5, (byte) 0xF0, 1, 2, 3, (byte) 0xF7, (byte) 0x80, 60, 0};
        p.feed(stream, 0, stream.length, 0);
        check("Parseur", String.join(" ", got), "90:60:100 90:64:90 90:67:0 b0:64:127 c0:5:-1 80:60:0");

        // Fichier MIDI : tics et taille
        check("1 s = 1920 tics", String.valueOf(MidiFileWriter.nanosToTicks(1_000_000_000L)), "1920");
        MidiFileWriter w = new MidiFileWriter();
        w.add(0, 0x90, 60, 100); w.add(500_000_000L, 0x80, 60, 0);
        java.io.File f = new java.io.File(args.length > 0 ? args[0] : "test.mid");
        w.write(f, "Manu Play Cam", 1_000_000_000L);
        check("Fichier MIDI écrit", String.valueOf(f.length() > 30), "true");

        // WAV
        java.io.File wf = new java.io.File(args.length > 1 ? args[1] : "test.wav");
        try (WavWriter ww = new WavWriter(wf, 48000, 2)) { ww.write(new byte[48000 * 4], 0, 48000 * 4); }
        long[] info = WavWriter.readInfo(wf);
        check("WAV 48 kHz stéréo", info[0] + "/" + info[1] + "/" + info[3], "48000/2/192000");

        System.out.println(fails == 0 ? "\nTOUS LES TESTS PASSENT" : "\n" + fails + " ÉCHEC(S)");
        if (fails > 0) System.exit(1);
    }
}
