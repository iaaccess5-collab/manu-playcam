package com.manumusicprod.playcam.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Identifie un accord à partir des notes MIDI réellement jouées.
 * Méthode : pour chaque fondamentale candidate, on compare l'ensemble des intervalles
 * à une bibliothèque de structures d'accords (tierce, quinte, septième, extensions…),
 * puis on garde la lecture la plus cohérente (notes couvertes, notes étrangères, basse).
 */
public final class ChordDetector {

    /** Résultat : nom prêt à afficher, ex. « A♭maj7 », « Bm7♭5 », « C7sus4/G ». */
    public static final class Chord {
        public final int root;      // classe de hauteur 0..11 (0 = C)
        public final int bass;      // classe de hauteur de la basse
        public final String suffix; // ex. "maj7"
        public final String name;   // nom complet
        Chord(int root, int bass, String suffix, String name) {
            this.root = root; this.bass = bass; this.suffix = suffix; this.name = name;
        }
        @Override public String toString() { return name; }
    }

    private static final class Shape {
        final String suffix; final int[] required; final int[] optional; final int rank;
        Shape(String suffix, int[] required, int[] optional, int rank) {
            this.suffix = suffix; this.required = required; this.optional = optional; this.rank = rank;
        }
    }

    private static int[] a(int... v) { return v; }

    // rank : plus petit = plus courant (départage les lectures équivalentes)
    private static final Shape[] SHAPES = {
        new Shape("",        a(0, 4, 7),        a(),            0),
        new Shape("m",       a(0, 3, 7),        a(),            1),
        new Shape("5",       a(0, 7),           a(),            30),
        new Shape("dim",     a(0, 3, 6),        a(),            12),
        new Shape("aug",     a(0, 4, 8),        a(),            14),
        new Shape("sus4",    a(0, 5, 7),        a(),            8),
        new Shape("sus2",    a(0, 2, 7),        a(),            9),
        new Shape("6",       a(0, 4, 9),        a(7),           10),
        new Shape("m6",      a(0, 3, 9),        a(7),           11),
        new Shape("7",       a(0, 4, 10),       a(7),           2),
        new Shape("maj7",    a(0, 4, 11),       a(7),           3),
        new Shape("m7",      a(0, 3, 10),       a(7),           4),
        new Shape("m7♭5",    a(0, 3, 6, 10),    a(),            5),
        new Shape("dim7",    a(0, 3, 6, 9),     a(),            6),
        new Shape("mMaj7",   a(0, 3, 11),       a(7),           15),
        new Shape("7sus4",   a(0, 5, 10),       a(7),           7),
        new Shape("maj7♯5",  a(0, 4, 8, 11),    a(),            22),
        new Shape("7♯5",     a(0, 4, 8, 10),    a(),            21),
        new Shape("7♭5",     a(0, 4, 6, 10),    a(),            23),
        new Shape("add9",    a(0, 2, 4, 7),     a(),            13),
        new Shape("madd9",   a(0, 2, 3, 7),     a(),            16),
        new Shape("6/9",     a(0, 2, 4, 9),     a(7),           17),
        new Shape("m6/9",    a(0, 2, 3, 9),     a(7),           18),
        new Shape("9",       a(0, 2, 4, 10),    a(7),           19),
        new Shape("maj9",    a(0, 2, 4, 11),    a(7),           20),
        new Shape("m9",      a(0, 2, 3, 10),    a(7),           20),
        new Shape("9sus4",   a(0, 2, 5, 10),    a(7),           24),
        new Shape("7♭9",     a(0, 1, 4, 10),    a(7),           25),
        new Shape("7♯9",     a(0, 3, 4, 10),    a(7),           26),
        new Shape("7♯11",    a(0, 4, 6, 10),    a(7, 2),        27),
        new Shape("maj7♯11", a(0, 4, 6, 11),    a(7, 2),        28),
        new Shape("11",      a(0, 2, 5, 10),    a(4, 7),        31),
        new Shape("m11",     a(0, 3, 5, 10),    a(2, 7),        29),
        new Shape("13",      a(0, 4, 9, 10),    a(2, 7),        32),
        new Shape("maj13",   a(0, 4, 9, 11),    a(2, 7),        33),
        new Shape("m13",     a(0, 3, 9, 10),    a(2, 7),        34),
        new Shape("7♭13",    a(0, 4, 8, 10),    a(2, 7),        35),
        new Shape("7♭9♭13",  a(0, 1, 4, 8, 10), a(7),           36),
        new Shape("7♯9♭13",  a(0, 3, 4, 8, 10), a(7),           37),
        new Shape("13♭9",    a(0, 1, 4, 9, 10), a(7),           38),
    };

    private static final String[] FLAT = {"C", "D♭", "D", "E♭", "E", "F", "G♭", "G", "A♭", "A", "B♭", "B"};
    private static final String[] SHARP = {"C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B"};
    // Orthographe usuelle : bémols sauf F♯ (plus lu que G♭ en pratique)
    private static final String[] MIXED = {"C", "D♭", "D", "E♭", "E", "F", "F♯", "G", "A♭", "A", "B♭", "B"};

    public static final int SPELL_MIXED = 0, SPELL_FLATS = 1, SPELL_SHARPS = 2;

    public static String pitchName(int pc, int spelling) {
        pc = ((pc % 12) + 12) % 12;
        switch (spelling) {
            case SPELL_FLATS: return FLAT[pc];
            case SPELL_SHARPS: return SHARP[pc];
            default: return MIXED[pc];
        }
    }

    /**
     * @param notes   notes MIDI (0..127) actuellement entendues, dans n'importe quel ordre
     * @return l'accord reconnu, ou null s'il n'y a pas assez de notes (moins de 2 classes de hauteur)
     */
    public static Chord detect(int[] notes, int spelling) {
        if (notes == null || notes.length == 0) return null;
        int lowest = 128; int mask = 0;
        for (int n : notes) {
            if (n < 0 || n > 127) continue;
            if (n < lowest) lowest = n;
            mask |= 1 << (n % 12);
        }
        if (lowest == 128) return null;
        int bass = lowest % 12;
        int count = Integer.bitCount(mask);
        if (count < 2) return null;

        double bestScore = -1e9; int bestRoot = -1; Shape bestShape = null;
        for (int root = 0; root < 12; root++) {
            if ((mask & (1 << root)) == 0) continue;
            int rel = 0;
            for (int pc = 0; pc < 12; pc++) if ((mask & (1 << pc)) != 0) rel |= 1 << ((pc - root + 12) % 12);
            for (Shape s : SHAPES) {
                int req = 0, opt = 0;
                for (int i : s.required) req |= 1 << i;
                for (int i : s.optional) opt |= 1 << i;
                if ((rel & req) != req) {
                    // tolère l'absence de la quinte juste dans les accords de 3 sons ou plus
                    int noFifth = req & ~(1 << 7);
                    if (noFifth == req || Integer.bitCount(noFifth) < 3 || (rel & noFifth) != noFifth) continue;
                }
                int extra = rel & ~(req | opt);
                if (extra != 0) continue; // une lecture doit expliquer toutes les notes
                double score = 0;
                score += Integer.bitCount(rel & req) * 10;     // notes caractéristiques présentes
                score += Integer.bitCount(rel & opt) * 2;
                if ((rel & req) != req) score -= 6;              // quinte omise
                if (root == bass) score += 9;                    // fondamentale à la basse
                score -= s.rank * 0.15;                          // préfère les accords courants
                if (s.suffix.equals("5") && count > 2) score -= 50;
                if (score > bestScore) { bestScore = score; bestRoot = root; bestShape = s; }
            }
        }
        if (bestShape == null) return fallback(mask, bass, spelling);
        String name = pitchName(bestRoot, spelling) + bestShape.suffix;
        if (bestRoot != bass) name += "/" + pitchName(bass, spelling);
        return new Chord(bestRoot, bass, bestShape.suffix, name);
    }

    /** Agrégat sans lecture standard : on nomme la basse et le nombre de notes, sans inventer. */
    private static Chord fallback(int mask, int bass, int spelling) {
        if (Integer.bitCount(mask) == 2) {
            // intervalle de deux sons : on affiche l'intervalle plutôt qu'un faux accord
            int other = -1;
            for (int pc = 0; pc < 12; pc++) if ((mask & (1 << pc)) != 0 && pc != bass) other = pc;
            int iv = (other - bass + 12) % 12;
            String[] ivName = {"", "2m", "2M", "3m", "3M", "4", "4+", "5", "6m", "6M", "7m", "7M"};
            return new Chord(bass, bass, "", pitchName(bass, spelling) + " – " + pitchName(other, spelling) + " (" + ivName[iv] + ")");
        }
        return null;
    }

    /** Variante pratique pour une liste. */
    public static Chord detect(List<Integer> notes, int spelling) {
        int[] arr = new int[notes.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = notes.get(i);
        return detect(arr, spelling);
    }

    private ChordDetector() {}

    // Utilisé par les tests
    static List<String> suffixes() {
        List<String> l = new ArrayList<>();
        for (Shape s : SHAPES) l.add(s.suffix);
        return l;
    }
}
