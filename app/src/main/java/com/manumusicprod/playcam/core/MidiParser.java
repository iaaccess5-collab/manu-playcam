package com.manumusicprod.playcam.core;

/**
 * Décode un flux d'octets MIDI (USB, Bluetooth, réseau) en messages complets.
 * Gère le « running status », ignore les SysEx et les messages temps réel (horloge, etc.).
 * Une instance par source : l'état du running status est propre à chaque flux.
 */
public final class MidiParser {

    public interface Listener {
        /** status inclut le canal (ex. 0x90 | canal). d2 vaut -1 pour les messages à une donnée. */
        void onMessage(int status, int d1, int d2, long timestampNanos);
    }

    private final Listener listener;
    private int running = 0;
    private int needed = 0;
    private int d1 = -1;
    private boolean inSysex = false;

    public MidiParser(Listener listener) { this.listener = listener; }

    public void reset() { running = 0; needed = 0; d1 = -1; inSysex = false; }

    public void feed(byte[] data, int offset, int count, long timestampNanos) {
        int end = offset + count;
        for (int i = offset; i < end; i++) {
            int b = data[i] & 0xFF;
            if (b >= 0xF8) continue;               // temps réel : horloge, start, stop, active sensing
            if (b == 0xF0) { inSysex = true; running = 0; continue; }
            if (b == 0xF7) { inSysex = false; continue; }
            if (inSysex) {
                if (b >= 0x80) inSysex = false; else continue; // un statut interrompt un SysEx mal terminé
            }
            if (b >= 0x80) {
                if (b >= 0xF0) {                    // messages système communs : pas de running status
                    running = 0; d1 = -1;
                    needed = (b == 0xF2) ? 2 : (b == 0xF1 || b == 0xF3) ? 1 : 0;
                    if (needed > 0) running = b;
                    continue;
                }
                running = b; d1 = -1;
                int type = b & 0xF0;
                needed = (type == 0xC0 || type == 0xD0) ? 1 : 2;
                continue;
            }
            if (running == 0) continue;            // donnée sans statut : ignorée
            if (needed == 1) {
                if (running < 0xF0) listener.onMessage(running, b, -1, timestampNanos);
                if (running >= 0xF0) running = 0;
                continue;
            }
            if (d1 < 0) { d1 = b; continue; }
            if (running < 0xF0) listener.onMessage(running, d1, b, timestampNanos);
            else running = 0;
            d1 = -1;
        }
    }
}
