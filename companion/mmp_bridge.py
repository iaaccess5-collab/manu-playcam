#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Manu Play Cam Bridge — compagnon Mac / Windows (MANU MUSIC PROD)

Envoie au téléphone, par le Wi-Fi local, le MIDI joué dans Logic Pro, Ableton Live, MainStage
ou sur un clavier branché à l'ordinateur. L'application Manu Play Cam affiche alors les notes
et les accords et les enregistre avec la vidéo.

Utilisation :
    python3 mmp_bridge.py                 # trouve le téléphone et relaie le port virtuel « Manu Play Cam »
    python3 mmp_bridge.py --list          # liste les ports MIDI de l'ordinateur
    python3 mmp_bridge.py --port "Nom"    # relaie un port MIDI existant (clavier, loopMIDI, IAC…)
    python3 mmp_bridge.py --ip 192.168.1.23
    python3 mmp_bridge.py --demo          # envoie une suite d'accords de test (sans clavier)

Installation : python3 -m pip install python-rtmidi
"""
import argparse
import socket
import struct
import sys
import time

PORT = 21928
VIRTUAL_NAME = "Manu Play Cam"


def discover(timeout=3.0):
    """Cherche le téléphone sur le réseau local (réponse « MMPA » + nom de l'appareil)."""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    s.settimeout(0.5)
    end = time.time() + timeout
    while time.time() < end:
        try:
            s.sendto(b"MMPD", ("255.255.255.255", PORT))
        except OSError:
            pass
        try:
            data, addr = s.recvfrom(512)
            if data.startswith(b"MMPA"):
                return addr[0], data[4:].decode("utf-8", "replace")
        except socket.timeout:
            continue
    return None, None


def ping(sock, ip, n=5):
    """Mesure l'aller-retour réseau (latence ressentie ≈ la moitié)."""
    sock.settimeout(0.5)
    rtts = []
    for _ in range(n):
        t0 = time.perf_counter()
        sock.sendto(b"MMPP" + struct.pack(">d", t0), (ip, PORT))
        try:
            data, _ = sock.recvfrom(64)
            if data.startswith(b"MMPQ"):
                rtts.append((time.perf_counter() - t0) * 1000)
        except socket.timeout:
            pass
    sock.settimeout(None)
    return rtts


def demo(sock, ip):
    """Cmaj7 – Am7 – Dm7 – G7, pour vérifier la liaison sans clavier."""
    chords = [("Cmaj7", [48, 64, 67, 71]), ("Am7", [45, 64, 67, 72]), ("Dm7", [50, 65, 69, 72]), ("G7", [43, 59, 65, 67])]
    for _ in range(2):
        for name, notes in chords:
            print("  ->", name)
            for n in notes:
                sock.sendto(b"MMPM" + bytes([0x90, n, 96]), (ip, PORT))
            time.sleep(1.2)
            for n in notes:
                sock.sendto(b"MMPM" + bytes([0x80, n, 0]), (ip, PORT))
            time.sleep(0.15)


def main():
    ap = argparse.ArgumentParser(description="Relaie le MIDI de l'ordinateur vers Manu Play Cam (Wi-Fi).")
    ap.add_argument("--ip", help="adresse IP du téléphone (affichée dans l'application, écran MIDI)")
    ap.add_argument("--port", help="nom (ou début du nom) d'un port MIDI d'entrée à relayer")
    ap.add_argument("--list", action="store_true", help="liste les ports MIDI et quitte")
    ap.add_argument("--demo", action="store_true", help="envoie des accords de test")
    a = ap.parse_args()

    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)

    if not a.list:
        ip = a.ip
        if not ip:
            print("Recherche du téléphone sur le Wi-Fi…")
            ip, name = discover()
            if not ip:
                print("Téléphone introuvable. Ouvre Manu Play Cam (écran MIDI, « Recevoir le MIDI par le Wi-Fi »)\n"
                      "puis relance avec l'adresse affichée :  python3 mmp_bridge.py --ip 192.168.x.x")
                sys.exit(1)
            print(f"Téléphone trouvé : {name} ({ip})")
        else:
            sock.sendto(b"MMPD", (ip, PORT))
        rtts = ping(sock, ip)
        if rtts:
            print(f"Latence réseau : {min(rtts) / 2:.1f} ms (aller simple, meilleure mesure)")
        else:
            print("Le téléphone ne répond pas aux mesures de latence : vérifie qu'il est sur le même Wi-Fi.")
        if a.demo:
            demo(sock, ip)
            return

    try:
        import rtmidi
    except ImportError:
        print("Module manquant. Installe-le avec :  python3 -m pip install python-rtmidi")
        sys.exit(1)

    midi_in = rtmidi.MidiIn()
    ports = midi_in.get_ports()
    if a.list:
        print("Ports MIDI d'entrée :")
        for i, p in enumerate(ports):
            print(f"  [{i}] {p}")
        return

    if a.port:
        idx = next((i for i, p in enumerate(ports) if p.lower().startswith(a.port.lower()) or a.port.lower() in p.lower()), None)
        if idx is None:
            print("Port introuvable. Ports disponibles :", ", ".join(ports) or "aucun")
            sys.exit(1)
        midi_in.open_port(idx)
        print(f"Relais du port : {ports[idx]}")
    else:
        try:
            midi_in.open_virtual_port(VIRTUAL_NAME)
            print(f"Port virtuel « {VIRTUAL_NAME} » créé : choisis-le comme sortie MIDI dans Logic, Ableton ou MainStage.")
        except Exception:
            print("Windows ne crée pas de port virtuel. Installe loopMIDI (gratuit), crée un port « Manu Play Cam »,\n"
                  "envoie-y le MIDI depuis ton logiciel, puis lance :  python mmp_bridge.py --port \"Manu Play Cam\"")
            sys.exit(1)
    midi_in.ignore_types(sysex=True, timing=True, active_sense=True)

    target = (ip, PORT)
    last_keepalive = 0.0
    print("Relais actif. Ctrl+C pour arrêter.")
    try:
        while True:
            msg = midi_in.get_message()
            if msg:
                data, _delta = msg
                sock.sendto(b"MMPM" + bytes(data), target)
            else:
                now = time.time()
                if now - last_keepalive > 5:
                    sock.sendto(b"MMPD", target)  # signale la présence de l'ordinateur
                    last_keepalive = now
                time.sleep(0.0005)
    except KeyboardInterrupt:
        print("\nArrêt du relais.")
    finally:
        midi_in.close_port()


if __name__ == "__main__":
    main()
