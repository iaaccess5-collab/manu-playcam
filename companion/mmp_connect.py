#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Manu Play Cam Connect — MANU MUSIC PROD

Logiciel pour l'ordinateur (Mac / Windows). Il trouve le téléphone sur le Wi-Fi et lui envoie :
  - le SON de ton logiciel de musique (Logic, Cubase, Ableton, MainStage…) ;
  - le MIDI que tu joues (ton clavier relié à l'ordinateur, et le port virtuel « Manu Play Cam »).
L'application Manu Play Cam affiche les notes sur le clavier virtuel et met le son dans la vidéo.

Son capté :
  - Mac (macOS 13 ou plus récent) : le son du Mac, directement (ScreenCaptureKit), sans logiciel en plus ;
  - Windows : le son de l'ordinateur (WASAPI loopback) ;
  - ou n'importe quelle entrée audio (interface, BlackHole…).

Options (tests) :
  --console           mode texte, sans fenêtre
  --tone              envoie un son et des accords de test (aucun matériel requis)
  --phone 192.168.x.x adresse du téléphone si la recherche automatique échoue
  --selftest          vérifie que les modules sont présents (utilisé par la compilation automatique)
"""
import argparse
import faulthandler
import math
import os
import platform
import queue
import random
import socket
import struct
import sys
import threading
import time

APP = "Manu Play Cam Connect"
VERSION = "1.0.1"
PORT = 5010
RATE = 48000
BLOCK = 240                     # 5 ms par paquet : < 1 000 octets, pas de fragmentation Wi-Fi
VIRTUAL_PORT = "Manu Play Cam"
IS_MAC = sys.platform == "darwin"
IS_WIN = sys.platform.startswith("win")

EBONY, EBONY2, EBONY3 = "#0E0F0D", "#171916", "#262A22"
IVORY, SOFT, MUTE = "#F3EFE4", "#CFCBC0", "#A39E90"
GREEN, GREEN_DEEP, RED, AMBER = "#59C35F", "#0A7034", "#E5484D", "#E9B44C"


# ==================================================================== journal (diagnostic en cas de problème)
LOG_PATH = None


class _Tee:
    def __init__(self, *files):
        self.files = [f for f in files if f is not None]

    def write(self, d):
        for f in self.files:
            try:
                f.write(d)
                f.flush()
            except Exception:
                pass
        return len(d)

    def flush(self):
        pass


def setup_log():
    """Journal : Mac ~/Library/Logs/Manu Play Cam Connect.log · Windows %LOCALAPPDATA%\\Manu Play Cam Connect.log"""
    global LOG_PATH
    try:
        if IS_MAC:
            d = os.path.expanduser("~/Library/Logs")
        elif IS_WIN:
            d = os.environ.get("LOCALAPPDATA") or os.path.expanduser("~")
        else:
            d = os.path.expanduser("~")
        os.makedirs(d, exist_ok=True)
        LOG_PATH = os.path.join(d, APP + ".log")
        f = open(LOG_PATH, "a", buffering=1, encoding="utf-8")
        faulthandler.enable(f)          # trace même en cas de plantage natif
        sys.stdout = _Tee(sys.__stdout__, f)
        sys.stderr = _Tee(sys.__stderr__, f)
        print(f"\n=== {time.strftime('%Y-%m-%d %H:%M:%S')} · {APP} {VERSION} · {platform.platform()} · "
              f"{platform.machine()} · Python {platform.python_version()} · frozen={getattr(sys, 'frozen', False)}")

        def hook(t, v, tb):
            import traceback
            print("ERREUR :", "".join(traceback.format_exception(t, v, tb)))
        sys.excepthook = hook
        threading.excepthook = lambda a: hook(a.exc_type, a.exc_value, a.exc_traceback)
    except Exception:
        pass


def log(*a):
    print(time.strftime("%H:%M:%S"), *a)


def fatal_dialog(msg):
    """Affiche l'erreur même si la fenêtre n'a pas pu s'ouvrir."""
    txt = f"{APP} n'a pas pu démarrer.\n\n{msg}\n\nJournal : {LOG_PATH}"
    try:
        if IS_MAC:
            import subprocess
            esc = txt.replace("\\", "\\\\").replace('"', '\\"')
            subprocess.run(["osascript", "-e", f'display alert "{APP}" message "{esc}" as critical'], timeout=120)
        elif IS_WIN:
            import ctypes
            ctypes.windll.user32.MessageBoxW(0, txt, APP, 0x10)
    except Exception:
        pass


def computer_name():
    if IS_MAC:
        try:
            import subprocess
            n = subprocess.run(["scutil", "--get", "ComputerName"], capture_output=True, text=True, timeout=2).stdout.strip()
            if n:
                return n
        except Exception:
            pass
    n = platform.node() or "Ordinateur"
    return n.replace(".local", "")


# ==================================================================== réseau
class Link:
    """Découverte du téléphone, signal de présence, envoi du son et du MIDI (UDP 5010)."""

    def __init__(self):
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        self.sock.bind(("0.0.0.0", 0))
        self.sock.settimeout(0.3)
        self.phone = None            # (ip, nom)
        self.manual_ip = None
        self.last_reply = 0.0
        self.session = random.getrandbits(32)
        self.frame = 0
        self.running = True
        self.name = computer_name()
        self.send_audio = True
        self.send_midi = True
        self.peak_l = 0.0
        self.peak_r = 0.0
        self.midi_count = 0
        self.last_note = ""
        self.events = queue.Queue()
        self.lock = threading.Lock()
        threading.Thread(target=self._net_loop, daemon=True).start()

    def _broadcast_targets(self):
        targets = ["255.255.255.255"]
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            s.connect(("10.255.255.255", 1))
            ip = s.getsockname()[0]
            s.close()
            targets.append(".".join(ip.split(".")[:3] + ["255"]))
        except OSError:
            pass
        return targets

    def _net_loop(self):
        last_probe = 0.0
        while self.running:
            now = time.time()
            if now - last_probe > 1.0:
                last_probe = now
                try:
                    if self.phone:
                        self.sock.sendto(b"MMPN" + self.name.encode("utf-8"), (self.phone[0], PORT))
                        self.sock.sendto(b"MMPD", (self.phone[0], PORT))
                    else:
                        for t in ([self.manual_ip] if self.manual_ip else self._broadcast_targets()):
                            self.sock.sendto(b"MMPD", (t, PORT))
                except OSError:
                    pass
                if self.phone and now - self.last_reply > 6:
                    self.events.put(f"Téléphone perdu ({self.phone[1]}). Nouvelle recherche…")
                    self.phone = None
            try:
                data, addr = self.sock.recvfrom(512)
            except (socket.timeout, OSError):
                continue
            if data.startswith(b"MMPH"):
                self.last_reply = time.time()
                name = data[4:].decode("utf-8", "replace") or addr[0]
                if not self.phone or self.phone[0] != addr[0]:
                    self.phone = (addr[0], name)
                    self.events.put(f"Connecté à {name}")
                    self.sock.sendto(b"MMPN" + self.name.encode("utf-8"), (addr[0], PORT))

    def send_pcm(self, pcm_bytes, frames, channels=2, rate=RATE):
        """pcm_bytes : PCM 16 bits little-endian entrelacé."""
        with self.lock:
            frame = self.frame
            self.frame += frames
        if self.phone and self.send_audio:
            header = b"MMPS" + struct.pack(">IQIB3x", self.session, frame, rate, channels)
            try:
                self.sock.sendto(header + pcm_bytes, (self.phone[0], PORT))
            except OSError:
                pass

    def send_midi_bytes(self, data):
        self.midi_count += 1
        if len(data) >= 3 and (data[0] & 0xF0) == 0x90 and data[2] > 0:
            names = ["C", "C♯", "D", "E♭", "E", "F", "F♯", "G", "A♭", "A", "B♭", "B"]
            self.last_note = f"{names[data[1] % 12]}{data[1] // 12 - 1}"
        if self.phone and self.send_midi:
            try:
                self.sock.sendto(b"MMPM" + bytes(data), (self.phone[0], PORT))
            except OSError:
                pass

    def stop(self):
        self.running = False


class Packer:
    """Découpe un flux PCM en blocs de 5 ms et mesure les crêtes gauche / droite."""

    def __init__(self, link):
        self.link = link
        self.buf = bytearray()

    def push_float(self, left, right):
        """left, right : séquences numpy float32 de même longueur, entre -1 et 1."""
        import numpy as np
        l = np.asarray(left, dtype=np.float32)
        r = np.asarray(right, dtype=np.float32)
        if l.size == 0:
            return
        self.link.peak_l = max(self.link.peak_l, float(np.abs(l).max()))
        self.link.peak_r = max(self.link.peak_r, float(np.abs(r).max()))
        inter = np.empty(l.size * 2, dtype=np.float32)
        inter[0::2] = l
        inter[1::2] = r
        self.buf += (np.clip(inter, -1.0, 0.99997) * 32767).astype("<i2").tobytes()
        step = BLOCK * 4
        while len(self.buf) >= step:
            chunk = bytes(self.buf[:step])
            del self.buf[:step]
            self.link.send_pcm(chunk, BLOCK)


# ==================================================================== son : Mac (ScreenCaptureKit)
class MacSystemAudio:
    """Capte le son du Mac (toutes les applications, dont Logic / Cubase) avec ScreenCaptureKit (macOS 13+).
    macOS demande une fois l'autorisation « Enregistrement de l'écran et audio système »."""

    available = False
    try:
        if IS_MAC:
            import objc  # noqa: F401
            import ScreenCaptureKit as SCK  # noqa: F401
            import CoreMedia as CM  # noqa: F401
            available = int(platform.mac_ver()[0].split(".")[0] or 0) >= 13
    except Exception:
        available = False

    def __init__(self, link, on_error):
        self.link = link
        self.on_error = on_error
        self.packer = Packer(link)
        self.stream = None
        self.output = None

    def start(self):
        import objc
        import ScreenCaptureKit as SCK
        import CoreMedia as CM
        import numpy as np
        packer = self.packer
        on_error = self.on_error

        SCStreamOutput = objc.protocolNamed("SCStreamOutput")

        class AudioOutput(objc.lookUpClass("NSObject"), protocols=[SCStreamOutput]):
            def stream_didOutputSampleBuffer_ofType_(self, stream, sbuf, otype):
                if otype != 1:   # SCStreamOutputTypeAudio
                    return
                try:
                    n = CM.CMSampleBufferGetNumSamples(sbuf)
                    bb = CM.CMSampleBufferGetDataBuffer(sbuf)
                    if bb is None or n <= 0:
                        return
                    length = CM.CMBlockBufferGetDataLength(bb)
                    try:
                        err, raw = CM.CMBlockBufferCopyDataBytes(bb, 0, length, None)
                    except TypeError:
                        raw = bytearray(length)
                        err = CM.CMBlockBufferCopyDataBytes(bb, 0, length, raw)
                    if err != 0 or raw is None:
                        return
                    a = np.frombuffer(bytes(raw), dtype=np.float32)
                    if a.size >= n * 2:
                        # ScreenCaptureKit livre du float32 non entrelacé : gauche puis droite
                        packer.push_float(a[:n], a[n:2 * n])
                    elif a.size >= n:
                        packer.push_float(a[:n], a[:n])
                except Exception as e:
                    on_error("Son du Mac : " + str(e))

        self.output = AudioOutput.alloc().init()
        done = threading.Event()
        holder = {}

        def got_content(content, error):
            try:
                if error is not None or content is None or not content.displays():
                    on_error("Autorise « Enregistrement de l'écran et audio système » pour Manu Play Cam Connect "
                             "(Réglages Système › Confidentialité et sécurité), puis relance l'application.")
                    return
                display = content.displays()[0]
                flt = SCK.SCContentFilter.alloc().initWithDisplay_excludingWindows_(display, [])
                cfg = SCK.SCStreamConfiguration.alloc().init()
                cfg.setCapturesAudio_(True)
                cfg.setExcludesCurrentProcessAudio_(True)
                cfg.setSampleRate_(RATE)
                cfg.setChannelCount_(2)
                cfg.setWidth_(2)
                cfg.setHeight_(2)
                cfg.setMinimumFrameInterval_(CM.CMTimeMake(1, 2))
                stream = SCK.SCStream.alloc().initWithFilter_configuration_delegate_(flt, cfg, None)
                res = stream.addStreamOutput_type_sampleHandlerQueue_error_(self.output, 1, None, None)
                ok = res[0] if isinstance(res, tuple) else res
                if not ok:
                    on_error("Le son du Mac n'a pas pu être capté.")
                    return

                def started(err):
                    if err is not None:
                        log("son du Mac : refus", err)
                        on_error("Capture du son refusée par macOS : " + str(err.localizedDescription()))
                stream.startCaptureWithCompletionHandler_(started)
                holder["stream"] = stream
                log("son du Mac : capture lancée")
            except Exception as e:      # une exception ne doit jamais remonter dans macOS (plantage)
                log("son du Mac : erreur", repr(e))
                on_error("Son du Mac indisponible : " + str(e))
            finally:
                done.set()

        log("son du Mac : demande d'accès")
        SCK.SCShareableContent.getShareableContentWithCompletionHandler_(got_content)
        done.wait(10)
        self.stream = holder.get("stream")

    def stop(self):
        if self.stream is not None:
            try:
                self.stream.stopCaptureWithCompletionHandler_(lambda e: None)
            except Exception:
                pass
        self.stream = None


# ==================================================================== son : entrées (Windows loopback, interfaces, BlackHole)
def list_sources():
    """Liste des sources : (libellé, clé). La première est la recommandée."""
    out = []
    if MacSystemAudio.available:
        out.append(("Son du Mac (Logic, Cubase, Ableton…)", ("mac", None)))
    try:
        import soundcard as sc
        if IS_WIN:
            try:
                spk = sc.default_speaker()
                out.append((f"Son de l'ordinateur ({spk.name})", ("loop", spk.name)))
            except Exception:
                pass
        mics = sc.all_microphones(include_loopback=IS_WIN)
        for m in mics:
            if getattr(m, "isloopback", False):
                continue
            out.append(("Entrée : " + m.name, ("mic", m.name)))
    except Exception:
        pass
    if not MacSystemAudio.available:
        out.sort(key=lambda x: 0 if x[1][0] == "loop" or "blackhole" in x[0].lower() else 1)
    return out


class InputAudio:
    def __init__(self, link, key, on_error):
        self.link = link
        self.key = key
        self.on_error = on_error
        self.stop_flag = threading.Event()
        self.packer = Packer(link)
        self.thread = None

    def start(self):
        self.thread = threading.Thread(target=self._run, daemon=True)
        self.thread.start()

    def _run(self):
        import numpy as np
        import soundcard as sc
        kind, name = self.key
        try:
            mic = sc.get_microphone(id=str(name), include_loopback=(kind == "loop"))
            with mic.recorder(samplerate=RATE, channels=2, blocksize=BLOCK) as rec:
                while not self.stop_flag.is_set():
                    data = rec.record(numframes=BLOCK)
                    if data.ndim == 1:
                        data = data[:, None]
                    left = data[:, 0]
                    right = data[:, 1] if data.shape[1] > 1 else data[:, 0]
                    self.packer.push_float(left, right)
        except Exception as e:
            self.on_error("Entrée audio : " + str(e))

    def stop(self):
        self.stop_flag.set()
        if self.thread:
            self.thread.join(timeout=1.5)


class ToneAudio:
    """Son de test : un accord tenu, au rythme réel."""

    def __init__(self, link):
        self.link = link
        self.stop_flag = threading.Event()

    def start(self):
        threading.Thread(target=self._run, daemon=True).start()

    def _run(self):
        freqs = [261.63, 329.63, 392.0, 493.88]
        t, sent, start = 0, 0, time.perf_counter()
        while not self.stop_flag.is_set():
            buf = bytearray()
            for i in range(BLOCK):
                x = sum(math.sin(2 * math.pi * f * (t + i) / RATE) for f in freqs) * 0.12
                v = int(max(-1, min(0.99997, x)) * 32767)
                buf += struct.pack("<hh", v, v)
            t += BLOCK
            self.link.peak_l = self.link.peak_r = max(self.link.peak_l, 0.48)
            self.link.send_pcm(bytes(buf), BLOCK)
            sent += BLOCK
            ahead = sent / RATE - (time.perf_counter() - start)
            if ahead > 0:
                time.sleep(ahead)

    def stop(self):
        self.stop_flag.set()


def make_audio(link, key, on_error):
    if key is None:
        return None
    if key[0] == "mac":
        return MacSystemAudio(link, on_error)
    if key[0] == "tone":
        return ToneAudio(link)
    return InputAudio(link, key, on_error)


# ==================================================================== MIDI
class MidiPump:
    def __init__(self, link):
        self.link = link
        self.ports = []
        self.names = []
        self.error = None

    def start(self):
        try:
            import rtmidi
        except ImportError:
            self.error = "Module MIDI absent"
            return
        try:
            names = rtmidi.MidiIn().get_ports()
            for i, n in enumerate(names):
                if VIRTUAL_PORT.lower() in n.lower():
                    continue
                m = rtmidi.MidiIn()
                try:
                    m.open_port(i)
                except Exception:
                    continue           # port pris en exclusif par un autre logiciel (Windows)
                m.ignore_types(sysex=True, timing=True, active_sense=True)
                m.set_callback(self._cb)
                self.ports.append(m)
                self.names.append(n)
            try:
                v = rtmidi.MidiIn()
                v.open_virtual_port(VIRTUAL_PORT)
                v.ignore_types(sysex=True, timing=True, active_sense=True)
                v.set_callback(self._cb)
                self.ports.append(v)
                self.names.append(VIRTUAL_PORT + " (port virtuel)")
            except Exception:
                pass                   # Windows : pas de port virtuel natif
        except Exception as e:
            self.error = str(e)

    def _cb(self, event, _data=None):
        msg, _delta = event
        if msg:
            self.link.send_midi_bytes(msg)

    def stop(self):
        for p in self.ports:
            try:
                p.close_port()
            except Exception:
                pass
        self.ports, self.names = [], []


def demo_chords(link, stop):
    chords = [[48, 64, 67, 71], [45, 64, 67, 72], [50, 65, 69, 72], [43, 59, 65, 67]]
    while not stop.is_set():
        for c in chords:
            if stop.is_set():
                return
            for n in c:
                link.send_midi_bytes([0x90, n, 96])
            time.sleep(1.0)
            for n in c:
                link.send_midi_bytes([0x80, n, 0])


# ==================================================================== mode texte
def console(args):
    link = Link()
    if args.phone:
        link.manual_ip = args.phone
        if args.tone:
            link.phone = (args.phone, "test")     # test : on envoie sans attendre la réponse du téléphone
            link.last_reply = time.time() + 3600
    errors = queue.Queue()
    stop = threading.Event()
    print(f"{APP} {VERSION} — recherche du téléphone sur le Wi-Fi…")
    midi = MidiPump(link)
    if args.tone:
        audio = ToneAudio(link)
        threading.Thread(target=demo_chords, args=(link, stop), daemon=True).start()
    else:
        srcs = list_sources()
        print("Source du son :", srcs[0][0] if srcs else "aucune")
        audio = make_audio(link, srcs[0][1] if srcs else None, errors.put)
        midi.start()
        print("MIDI :", ", ".join(midi.names) or (midi.error or "aucun port"))
    if audio:
        audio.start()
    t_end = time.time() + args.seconds if args.seconds else None
    try:
        while t_end is None or time.time() < t_end:
            for q in (link.events, errors):
                try:
                    print(q.get_nowait())
                except queue.Empty:
                    pass
            time.sleep(0.2)
    except KeyboardInterrupt:
        pass
    stop.set()
    if audio:
        audio.stop()
    midi.stop()
    link.stop()
    print(f"Arrêt. Trames envoyées : {link.frame}, messages MIDI : {link.midi_count}")


def selftest():
    print(APP, VERSION, platform.platform())
    import numpy  # noqa: F401
    print("numpy OK")
    try:
        import rtmidi
        print("rtmidi OK, ports :", rtmidi.MidiIn().get_ports())
    except Exception as e:
        print("rtmidi :", e)
    if IS_MAC:
        import ScreenCaptureKit  # noqa: F401
        import CoreMedia  # noqa: F401
        print("ScreenCaptureKit OK · son du Mac disponible :", MacSystemAudio.available)
    try:
        import soundcard  # noqa: F401
        print("soundcard OK")
    except Exception as e:
        print("soundcard :", e)
    print("Sources :", [s[0] for s in list_sources()])
    import tkinter  # noqa: F401
    print("tkinter OK")
    print("AUTOTEST TERMINÉ")


# ==================================================================== fenêtre (branding MANU MUSIC PROD)
def gui(screenshot=None, quit_after=None):
    import tkinter as tk
    from tkinter import ttk

    log("fenêtre : démarrage de Tk")
    root = tk.Tk()
    log("fenêtre : Tk", root.tk.call("info", "patchlevel"))
    root.title(APP)
    root.configure(bg=EBONY)
    root.geometry("500x690")
    root.minsize(460, 640)
    base = "Helvetica Neue" if IS_MAC else "Segoe UI"

    def F(size, weight="normal", slant="roman"):
        return (base, size, weight, slant)

    link = Link()
    errors = queue.Queue()
    state = {"audio": None, "midi": MidiPump(link)}

    style = ttk.Style(root)
    try:
        style.theme_use("clam")
    except Exception:
        pass
    style.configure("MMP.TCombobox", fieldbackground=EBONY2, background=EBONY3, foreground=IVORY, arrowcolor=GREEN,
                    bordercolor=EBONY3, lightcolor=EBONY3, darkcolor=EBONY3, selectbackground=EBONY2, selectforeground=IVORY)
    style.map("MMP.TCombobox",
              fieldbackground=[("readonly", EBONY2), ("disabled", EBONY2)],
              foreground=[("readonly", IVORY), ("disabled", MUTE)],
              background=[("active", EBONY3), ("readonly", EBONY3)],
              selectbackground=[("readonly", EBONY2)], selectforeground=[("readonly", IVORY)],
              arrowcolor=[("readonly", GREEN)])
    root.option_add("*TCombobox*Listbox.background", EBONY2)
    root.option_add("*TCombobox*Listbox.foreground", IVORY)
    root.option_add("*TCombobox*Listbox.selectBackground", GREEN_DEEP)

    # ---- en-tête : monogramme EN + nom
    head = tk.Frame(root, bg=EBONY)
    head.pack(fill="x", padx=24, pady=(20, 6))
    logo = tk.Canvas(head, width=40, height=38, bg=EBONY, highlightthickness=0)
    logo.pack(side="left")
    s = 0.34
    for x, y, w, h in [(0, 0, 15, 100), (0, 0, 68, 15), (0, 42.5, 54, 15), (0, 85, 68, 15), (91, 0, 15, 100)]:
        logo.create_rectangle(2 + x * s, 2 + y * s, 2 + (x + w) * s, 2 + (y + h) * s, fill=IVORY, outline="")
    logo.create_line(2 + 5 * s, 2 + 3 * s, 2 + 100 * s, 2 + 97 * s, fill=GREEN, width=5)
    tk.Label(head, text="MANU MUSIC PROD", bg=EBONY, fg=IVORY, font=F(11, "bold")).pack(side="left", padx=10)

    title = tk.Frame(root, bg=EBONY)
    title.pack(fill="x", padx=24, pady=(6, 0))
    eb = tk.Frame(title, bg=EBONY)
    eb.pack(anchor="w")
    dash = tk.Canvas(eb, width=22, height=6, bg=EBONY, highlightthickness=0)
    dash.create_polygon(5, 0, 22, 0, 17, 6, 0, 6, fill=GREEN, outline="")
    dash.pack(side="left")
    tk.Label(eb, text="  SON + MIDI · WI-FI", bg=EBONY, fg=GREEN, font=F(9, "bold")).pack(side="left")
    tk.Label(title, text="MANU PLAY CAM", bg=EBONY, fg=IVORY, font=F(26, "bold", "italic")).pack(anchor="w")
    tk.Label(title, text="CONNECT", bg=EBONY, fg=GREEN, font=F(26, "bold", "italic")).pack(anchor="w")

    # ---- carte téléphone
    card = tk.Frame(root, bg=EBONY2, highlightthickness=1, highlightbackground=EBONY3)
    card.pack(fill="x", padx=24, pady=(14, 8))
    row = tk.Frame(card, bg=EBONY2)
    row.pack(fill="x", padx=16, pady=(14, 2))
    lamp = tk.Canvas(row, width=16, height=16, bg=EBONY2, highlightthickness=0)
    lamp.pack(side="left")
    lamp_dot = lamp.create_oval(3, 3, 13, 13, fill=MUTE, outline="")
    status = tk.Label(row, text="", bg=EBONY2, fg=IVORY, font=F(13, "bold"))
    status.pack(side="left", padx=8)
    hint = tk.Label(card, text="", bg=EBONY2, fg=MUTE, font=F(10), wraplength=420, justify="left")
    hint.pack(anchor="w", padx=16, pady=(0, 14))

    # ---- son
    sec = tk.Frame(root, bg=EBONY)
    sec.pack(fill="x", padx=24, pady=(10, 0))
    sound_head = tk.Frame(sec, bg=EBONY)
    sound_head.pack(fill="x")
    tk.Label(sound_head, text="SON ENVOYÉ", bg=EBONY, fg=GREEN, font=F(9, "bold")).pack(side="left")
    sig = tk.Label(sound_head, text="AUCUN SON", bg=EBONY, fg=MUTE, font=F(9, "bold"))
    sig.pack(side="right")
    sources = list_sources()
    names = [x[0] for x in sources] or ["Aucune source audio"]
    choice = tk.StringVar(value=names[0])
    combo = ttk.Combobox(sec, textvariable=choice, values=names, state="readonly", style="MMP.TCombobox", font=F(11))
    combo.pack(fill="x", pady=(8, 10))

    SEG = 30
    meters = {}
    for ch in ("L", "R"):
        r = tk.Frame(sec, bg=EBONY)
        r.pack(fill="x", pady=2)
        tk.Label(r, text=ch, bg=EBONY, fg=SOFT, font=F(9, "bold"), width=2).pack(side="left")
        c = tk.Canvas(r, height=12, bg=EBONY, highlightthickness=0)
        c.pack(side="left", fill="x", expand=True)
        meters[ch] = {"canvas": c, "segs": [], "hold": [-90.0, 0.0]}
    scale = tk.Frame(sec, bg=EBONY)
    scale.pack(fill="x", padx=(22, 0))
    for t in ["-48", "-36", "-24", "-12", "-6", "-3", "0"]:
        tk.Label(scale, text=t, bg=EBONY, fg=MUTE, font=F(8)).pack(side="left", expand=True)

    def seg_color(i):
        db = -48 + 48 * (i + 1) / SEG
        return RED if db > -3 else AMBER if db > -12 else GREEN

    def dim(hexc, f=0.16):
        r, g, b = int(hexc[1:3], 16), int(hexc[3:5], 16), int(hexc[5:7], 16)
        er, eg, ebb = 0x0E, 0x0F, 0x0D
        return "#%02x%02x%02x" % (int(er + (r - er) * f), int(eg + (g - eg) * f), int(ebb + (b - ebb) * f))

    def draw_meter(m, db):
        c = m["canvas"]
        w = max(c.winfo_width(), 200)
        if not m["segs"] or m.get("w") != w:
            c.delete("all")
            gap = 2
            sw = (w - gap * (SEG - 1)) / SEG
            m["segs"] = [c.create_rectangle(i * (sw + gap), 0, i * (sw + gap) + sw, 12, outline="", fill=dim(seg_color(i))) for i in range(SEG)]
            m["w"] = w
        now = time.time()
        if db >= m["hold"][0] or now - m["hold"][1] > 1.2:
            m["hold"] = [db, now]
        for i, it in enumerate(m["segs"]):
            th = -48 + 48 * (i + 1) / SEG
            lit = db >= th - 48 / SEG
            hold = (not lit) and m["hold"][0] > -60 and abs(m["hold"][0] - th) <= 48 / SEG
            c.itemconfig(it, fill=seg_color(i) if lit else dim(seg_color(i), 0.75) if hold else dim(seg_color(i)))

    help_txt = ("Mac : à la première utilisation, autorise « Enregistrement de l'écran et audio système » dans "
                "Réglages Système › Confidentialité et sécurité. Le son de Logic, Cubase… est alors envoyé tel quel, "
                "et tu continues de l'entendre normalement." if IS_MAC else
                "Windows : « Son de l'ordinateur » envoie ce que tu entends, ton logiciel de musique compris.")
    tk.Label(sec, text=help_txt, bg=EBONY, fg=MUTE, font=F(9), wraplength=440, justify="left").pack(anchor="w", pady=(8, 0))

    # ---- MIDI
    sec2 = tk.Frame(root, bg=EBONY)
    sec2.pack(fill="x", padx=24, pady=(16, 0))
    tk.Label(sec2, text="MIDI ENVOYÉ", bg=EBONY, fg=GREEN, font=F(9, "bold")).pack(anchor="w")
    midi_lbl = tk.Label(sec2, text="", bg=EBONY, fg=IVORY, font=F(10), wraplength=440, justify="left")
    midi_lbl.pack(anchor="w", pady=(6, 2))
    tk.Label(sec2, text=f"Ton clavier relié à l'ordinateur est capté directement. Depuis ton logiciel, tu peux aussi "
                        f"envoyer une piste MIDI vers le port « {VIRTUAL_PORT} ».",
             bg=EBONY, fg=MUTE, font=F(9), wraplength=440, justify="left").pack(anchor="w")

    # ---- adresse manuelle
    man = tk.Frame(root, bg=EBONY)
    man.pack(fill="x", padx=24, pady=(16, 0))
    tk.Label(man, text="Téléphone introuvable ? Saisis l'adresse affichée dans l'application :", bg=EBONY, fg=MUTE, font=F(9)).pack(anchor="w")
    mrow = tk.Frame(man, bg=EBONY)
    mrow.pack(anchor="w", pady=6)
    ip_var = tk.StringVar()
    tk.Entry(mrow, textvariable=ip_var, width=18, bg=EBONY2, fg=IVORY, insertbackground=IVORY, relief="flat", font=F(11)).pack(side="left", ipady=5)

    def use_ip():
        link.manual_ip = ip_var.get().strip() or None
        link.phone = None
    tk.Button(mrow, text="Utiliser", command=use_ip, bg=GREEN, fg=EBONY, activebackground=GREEN_DEEP, relief="flat",
              font=F(10, "bold"), padx=14).pack(side="left", padx=8)

    tk.Label(root, text=f"© MANU MUSIC PROD · Emmanuel NOUHOU   ·   v{VERSION}", bg=EBONY, fg=MUTE, font=F(8)).pack(side="bottom", pady=10)

    def start_audio(*_):
        if state["audio"]:
            state["audio"].stop()
        key = None
        if choice.get() in [x[0] for x in sources]:
            key = sources[[x[0] for x in sources].index(choice.get())][1]
        try:
            state["audio"] = make_audio(link, key, errors.put)
            if state["audio"]:
                log("son : source", choice.get())
                # démarrage hors du fil de la fenêtre : la fenêtre reste fluide pendant que macOS répond
                threading.Thread(target=_safe_start, args=(state["audio"],), daemon=True).start()
        except Exception as e:
            log("son : erreur", repr(e))
            errors.put("Son : " + str(e))

    def _safe_start(audio):
        try:
            audio.start()
        except Exception as e:
            log("son : erreur au démarrage", repr(e))
            errors.put("Son : " + str(e))
    combo.bind("<<ComboboxSelected>>", start_audio)
    root.after(300, start_audio)
    try:
        state["midi"].start()
        log("MIDI :", state["midi"].names or state["midi"].error or "aucun port")
    except Exception as e:
        log("MIDI : erreur", repr(e))

    def report(exc, val, tb):
        import traceback
        log("ERREUR fenêtre :", "".join(traceback.format_exception(exc, val, tb)))
        errors.put(str(val))
    root.report_callback_exception = report

    last_err = {"t": 0.0, "msg": ""}

    def tick():
        try:
            last_err["msg"] = errors.get_nowait()
            last_err["t"] = time.time()
        except queue.Empty:
            pass
        while not link.events.empty():
            link.events.get_nowait()
        if link.phone:
            status.config(text=f"Connecté à {link.phone[1]}")
            hint.config(text="Lance la vidéo dans l'application et joue : les notes et le son arrivent dans le téléphone.")
            lamp.itemconfig(lamp_dot, fill=GREEN)
        else:
            status.config(text="Recherche du téléphone…")
            hint.config(text="Ouvre Manu Play Cam sur le téléphone, connecté au même Wi-Fi que cet ordinateur.")
            lamp.itemconfig(lamp_dot, fill=MUTE if int(time.time() * 2) % 2 else EBONY3)
        if last_err["msg"] and time.time() - last_err["t"] < 15:
            hint.config(text=last_err["msg"], fg=AMBER)
        else:
            hint.config(fg=MUTE)
        pl, pr = link.peak_l, link.peak_r
        link.peak_l = link.peak_r = 0.0
        dbl = 20 * math.log10(pl) if pl > 1e-5 else -90.0
        dbr = 20 * math.log10(pr) if pr > 1e-5 else -90.0
        draw_meter(meters["L"], dbl)
        draw_meter(meters["R"], dbr)
        peak = max(dbl, dbr)
        sig.config(text="SATURATION" if peak > -0.2 else "SON ENTRANT" if peak > -60 else "AUCUN SON",
                   fg=RED if peak > -0.2 else GREEN if peak > -60 else MUTE)
        mp = state["midi"]
        ports = ", ".join(mp.names) if mp.names else (mp.error or "Aucun port MIDI détecté")
        last = f"   ·   dernière note : {link.last_note}" if link.last_note else ""
        midi_lbl.config(text=f"{ports}\n{link.midi_count} messages envoyés{last}")
        root.after(60, tick)

    def close():
        if state["audio"]:
            state["audio"].stop()
        state["midi"].stop()
        link.stop()
        root.destroy()
    root.protocol("WM_DELETE_WINDOW", close)
    tick()
    log("fenêtre : prête")
    if quit_after:
        def bye():
            log("ESSAI FENÊTRE OK")
            close()
        root.after(int(quit_after * 1000), bye)
    if screenshot:
        # capture de la fenêtre (vérification visuelle lors de la compilation automatique)
        def snap():
            try:
                from PIL import ImageGrab
                root.update()
                x, y = root.winfo_rootx(), root.winfo_rooty()
                ImageGrab.grab(bbox=(x, y, x + root.winfo_width(), y + root.winfo_height())).save(screenshot)
            finally:
                close()
        root.after(3500, snap)
    root.mainloop()


if __name__ == "__main__":
    ap = argparse.ArgumentParser(description=APP)
    ap.add_argument("--console", action="store_true", help="mode texte, sans fenêtre")
    ap.add_argument("--tone", action="store_true", help="son et accords de test (mode texte)")
    ap.add_argument("--phone", help="adresse IP du téléphone")
    ap.add_argument("--seconds", type=float, default=0, help="durée (tests)")
    ap.add_argument("--selftest", action="store_true", help="vérifie les modules et quitte")
    ap.add_argument("--screenshot", help="enregistre une capture de la fenêtre puis quitte")
    ap.add_argument("--smoketest", type=float, default=0, help="ouvre la fenêtre N secondes puis quitte")
    a, _unknown = ap.parse_known_args()   # macOS peut ajouter ses propres arguments au lancement
    setup_log()
    if a.selftest:
        selftest()
    elif a.console:
        console(a)
    else:
        try:
            gui(a.screenshot, a.smoketest or None)
        except Exception as e:
            import traceback
            log("ERREUR au démarrage :", traceback.format_exc())
            fatal_dialog(f"{type(e).__name__} : {e}")
            sys.exit(1)
