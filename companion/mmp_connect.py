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
VERSION = "1.1.2"
PORT = 5010
USB_PORT = 5011                 # câble : port TCP relayé par « adb reverse »
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


def _state_file(name):
    d = os.path.expanduser("~/Library/Application Support/" + APP) if IS_MAC else \
        os.path.join(os.environ.get("LOCALAPPDATA") or os.path.expanduser("~"), APP)
    try:
        os.makedirs(d, exist_ok=True)
    except Exception:
        pass
    return os.path.join(d, name)


def capture_lock_set(on):
    try:
        p = _state_file("capture.lock")
        if on:
            open(p, "w").write(str(time.time()))
        elif os.path.exists(p):
            os.remove(p)
    except Exception:
        pass


def capture_lock_present():
    try:
        return os.path.exists(_state_file("capture.lock"))
    except Exception:
        return False


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
    """Liaison avec le téléphone, par le Wi-Fi (UDP 5010, choix par le nom) ou par le câble USB (TCP 5011 via adb).

    Wi-Fi : l'ordinateur s'annonce (MMPA + nom) et cherche les téléphones (MMPD → MMPH + nom). Chaque côté affiche
    les noms trouvés ; un clic d'un côté ou de l'autre relie les deux (MMPC du téléphone, MMPN de l'ordinateur).
    MMPX = déconnexion. Le dernier téléphone choisi est retrouvé tout seul à l'ouverture suivante.
    Câble : quand un téléphone est branché (débogage USB autorisé), l'ordinateur ouvre « adb reverse » et le
    téléphone s'y connecte de lui-même. Le câble est alors prioritaire ; débranché, le Wi-Fi reprend.
    """

    def __init__(self, usb=True):
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        try:
            # priorité « voix » sur le Wi-Fi (WMM) : les routeurs font passer ces paquets avant les autres
            self.sock.setsockopt(socket.IPPROTO_IP, socket.IP_TOS, 0xB8)
        except (OSError, AttributeError):
            pass
        self.sock.bind(("0.0.0.0", 0))
        self.sock.settimeout(0.3)
        self.phone = None            # (ip ou "usb", nom)
        self.phones = {}             # ip -> [nom, dernière réponse]  (téléphones vus sur le Wi-Fi)
        self.manual_ip = None        # tests uniquement
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
        self.usb_sock = None
        self.usb_lock = threading.Lock()
        self.usb_state = "off"       # off · no_adb · waiting · unauthorized · ready · connected
        self.paused = set()          # téléphones déconnectés à la main : pas de reconnexion automatique
        self.remembered = self._load_remembered()
        threading.Thread(target=self._net_loop, daemon=True).start()
        if usb:
            threading.Thread(target=self._usb_server, daemon=True).start()
            threading.Thread(target=self._adb_loop, daemon=True).start()

    # ---- mémoire du dernier téléphone
    def _load_remembered(self):
        try:
            return open(_state_file("telephone.txt"), encoding="utf-8").read().strip() or None
        except Exception:
            return None

    def _remember(self, name):
        self.remembered = name
        try:
            open(_state_file("telephone.txt"), "w", encoding="utf-8").write(name)
        except Exception:
            pass

    @property
    def via_usb(self):
        return self.usb_sock is not None

    def phone_list(self):
        """Téléphones vus sur le Wi-Fi depuis moins de 5 s : [(ip, nom)]."""
        now = time.time()
        return sorted([(ip, v[0]) for ip, v in list(self.phones.items()) if now - v[1] < 5], key=lambda x: x[1].lower())

    # ---- choix du téléphone
    def connect_to(self, ip, name):
        self.paused.discard(name)
        if self.via_usb:
            return
        self.phone = (ip, name)
        self.last_reply = time.time()
        self._remember(name)
        self.events.put(f"Connecté à {name}")
        self._udp(b"MMPC" + self.name.encode("utf-8"), ip)
        self._udp(b"MMPN" + self.name.encode("utf-8"), ip)

    def disconnect(self):
        p = self.phone
        if not p:
            return
        self.paused.add(p[1])
        if p[0] != "usb":
            self._udp(b"MMPX", p[0])
            self.phone = None
            self.events.put(f"Déconnecté de {p[1]}")

    def _udp(self, data, ip):
        try:
            self.sock.sendto(data, (ip, PORT))
        except OSError:
            pass

    def _broadcast_targets(self):
        targets = ["255.255.255.255"]
        try:
            for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
                ip = info[4][0]
                if not ip.startswith("127."):
                    targets.append(".".join(ip.split(".")[:3] + ["255"]))
        except Exception:
            pass
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            s.connect(("10.255.255.255", 1))
            ip = s.getsockname()[0]
            s.close()
            targets.append(".".join(ip.split(".")[:3] + ["255"]))
        except OSError:
            pass
        return list(dict.fromkeys(targets))

    def _net_loop(self):
        last_probe = 0.0
        while self.running:
            now = time.time()
            if now - last_probe > 1.0:
                last_probe = now
                announce = b"MMPA" + self.name.encode("utf-8")
                for t in ([self.manual_ip] if self.manual_ip else self._broadcast_targets()):
                    self._udp(b"MMPD", t)            # qui est là ?
                    self._udp(announce, t)           # je suis là
                self.heartbeat_usb()
                if self.phone and self.phone[0] != "usb":
                    self._udp(b"MMPN" + self.name.encode("utf-8"), self.phone[0])
                    self._udp(b"MMPD", self.phone[0])
                    if now - self.last_reply > 6:
                        self.events.put(f"Téléphone perdu ({self.phone[1]}). Il sera retrouvé dès qu'il revient.")
                        self.phone = None
                if self.phone is None and not self.via_usb:
                    found = self.phone_list()
                    pick = next(((ip, n) for ip, n in found if n == self.remembered and n not in self.paused), None)
                    if pick is None and self.remembered is None and len(found) == 1 and found[0][1] not in self.paused:
                        pick = found[0]               # première utilisation : un seul téléphone, on le relie
                    if pick:
                        self.connect_to(*pick)
            try:
                data, addr = self.sock.recvfrom(512)
            except (socket.timeout, OSError):
                continue
            ip = addr[0]
            if data.startswith(b"MMPH"):
                name = data[4:].decode("utf-8", "replace") or ip
                self.phones[ip] = [name, time.time()]
                if self.phone and self.phone[0] == ip:
                    self.last_reply = time.time()
                    if self.phone[1] != name:
                        self.phone = (ip, name)
            elif data.startswith(b"MMPC"):                 # le téléphone nous a choisi
                name = data[4:].decode("utf-8", "replace") or ip
                self.phones[ip] = [name, time.time()]
                self.connect_to(ip, name)
            elif data.startswith(b"MMPX"):                 # le téléphone se déconnecte
                if self.phone and self.phone[0] == ip:
                    self.paused.add(self.phone[1])
                    self.events.put(f"{self.phone[1]} s'est déconnecté")
                    self.phone = None

    # ---- câble USB
    def _usb_server(self):
        try:
            srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            srv.bind(("127.0.0.1", USB_PORT))
            srv.listen(2)
        except OSError as e:
            log("câble : port indisponible", e)
            return
        while self.running:
            try:
                c, _ = srv.accept()
            except OSError:
                continue
            threading.Thread(target=self._usb_client, args=(c,), daemon=True).start()

    def _usb_client(self, c):
        try:
            c.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            c.settimeout(5)
            head = self._recv_exact(c, 2)
            body = self._recv_exact(c, struct.unpack(">H", head)[0])
            if not body.startswith(b"MMPH"):
                c.close()
                return
            name = body[4:].decode("utf-8", "replace") or "Téléphone"
            old = self.usb_sock
            with self.usb_lock:
                self.usb_sock = c
            if old is not None:
                try:
                    old.close()
                except OSError:
                    pass
            prev = self.phone
            if prev and prev[0] != "usb":
                self._udp(b"MMPX", prev[0])          # le câble remplace le Wi-Fi
            self.phone = ("usb", name)
            self.usb_state = "connected"
            self._remember(name)
            self.events.put(f"Connecté à {name} par câble")
            log("câble : connecté à", name)
            self._usb_send(b"MMPN" + self.name.encode("utf-8"))
            c.settimeout(None)
            while self.running:
                d = c.recv(64)
                if not d:
                    break
        except (OSError, struct.error):
            pass
        finally:
            if self.usb_sock is c:
                with self.usb_lock:
                    self.usb_sock = None
                if self.phone and self.phone[0] == "usb":
                    self.events.put("Câble débranché")
                    self.phone = None
                self.usb_state = "ready"
            try:
                c.close()
            except OSError:
                pass

    @staticmethod
    def _recv_exact(c, n):
        b = b""
        while len(b) < n:
            d = c.recv(n - len(b))
            if not d:
                raise OSError("fermé")
            b += d
        return b

    def _usb_send(self, data):
        c = self.usb_sock
        if c is None:
            return False
        try:
            with self.usb_lock:
                c.sendall(struct.pack(">H", len(data)) + data)
            return True
        except OSError:
            try:
                c.close()
            except OSError:
                pass
            return False

    def _adb_loop(self):
        adb = find_adb()
        if not adb:
            self.usb_state = "no_adb"
            return
        import subprocess
        kw = {"capture_output": True, "text": True, "timeout": 8}
        if IS_WIN:
            kw["creationflags"] = 0x08000000      # pas de fenêtre de console
        done = set()
        try:
            # le serveur adb reste en arrière-plan : on le lance sans tuyaux, sinon la lecture attendrait sa fin
            quiet = {k: v for k, v in kw.items() if k != "capture_output"}
            subprocess.run([adb, "start-server"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                           stdin=subprocess.DEVNULL, **{**quiet, "text": False, "timeout": 20})
        except OSError as e:
            log("câble : adb ne démarre pas", repr(e))
            self.usb_state = "no_adb"
            return
        except Exception as e:
            log("câble : démarrage adb", repr(e))
        while self.running:
            try:
                out = subprocess.run([adb, "devices"], **kw).stdout
                devs = [ln.split("\t") for ln in out.splitlines()[1:] if "\t" in ln]
                ready = [d for d, st in devs if st == "device"]
                if any(st == "unauthorized" for _, st in devs) and not ready:
                    self.usb_state = "unauthorized"
                elif not ready:
                    self.usb_state = "waiting" if not self.via_usb else "connected"
                    done.clear()
                for d in ready:
                    if d not in done:
                        r = subprocess.run([adb, "-s", d, "reverse", f"tcp:{USB_PORT}", f"tcp:{USB_PORT}"], **kw)
                        if r.returncode == 0:
                            done.add(d)
                            log("câble : téléphone branché", d)
                if ready and not self.via_usb:
                    self.usb_state = "ready"
                done &= set(ready)
            except OSError as e:
                log("câble : adb ne démarre pas", repr(e))
                self.usb_state = "no_adb"
                return
            except Exception as e:
                log("câble : adb", repr(e))
            time.sleep(2)

    # ---- envoi
    def send_pcm(self, pcm_bytes, frames, channels=2, rate=RATE):
        """pcm_bytes : PCM 16 bits little-endian entrelacé."""
        with self.lock:
            frame = self.frame
            self.frame += frames
        if self.phone and self.send_audio:
            pkt = b"MMPS" + struct.pack(">IQIB3x", self.session, frame, rate, channels) + pcm_bytes
            if self.phone[0] == "usb":
                self._usb_send(pkt)
            else:
                self._udp(pkt, self.phone[0])

    def send_midi_bytes(self, data):
        self.midi_count += 1
        if len(data) >= 3 and (data[0] & 0xF0) == 0x90 and data[2] > 0:
            names = ["C", "C♯", "D", "E♭", "E", "F", "F♯", "G", "A♭", "A", "B♭", "B"]
            self.last_note = f"{names[data[1] % 12]}{data[1] // 12 - 1}"
        if self.phone and self.send_midi:
            pkt = b"MMPM" + bytes(data)
            if self.phone[0] == "usb":
                self._usb_send(pkt)
            else:
                self._udp(pkt, self.phone[0])

    def heartbeat_usb(self):
        if self.via_usb:
            self._usb_send(b"MMPN" + self.name.encode("utf-8"))

    def stop(self):
        self.running = False
        p = self.phone
        if p and p[0] != "usb":
            self._udp(b"MMPX", p[0])


def find_adb():
    """adb livré avec l'application (dossier adb/), sinon celui de l'ordinateur."""
    import shutil
    exe = "adb.exe" if IS_WIN else "adb"
    bases = [getattr(sys, "_MEIPASS", None), os.path.dirname(os.path.abspath(sys.argv[0])),
             os.path.dirname(os.path.abspath(__file__))]
    for b in bases:
        if b:
            for p in (os.path.join(b, "adb", exe), os.path.join(b, "..", "Resources", "adb", exe)):
                if os.path.isfile(p):
                    return os.path.abspath(p)
    return shutil.which("adb")


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
                    capture_lock_set(False)
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
                    capture_lock_set(False)
                    on_error("Le son du Mac n'a pas pu être capté.")
                    return

                def started(err):
                    if err is not None:
                        log("son du Mac : refus", err)
                        capture_lock_set(False)
                        on_error("Capture du son refusée par macOS : " + str(err.localizedDescription()))
                stream.startCaptureWithCompletionHandler_(started)
                holder["stream"] = stream
                log("son du Mac : capture lancée")
                threading.Timer(4.0, capture_lock_set, args=(False,)).start()
            except Exception as e:      # une exception ne doit jamais remonter dans macOS (plantage)
                log("son du Mac : erreur", repr(e))
                capture_lock_set(False)
                on_error("Son du Mac indisponible : " + str(e))
            finally:
                done.set()

        log("son du Mac : demande d'accès")
        capture_lock_set(True)
        SCK.SCShareableContent.getShareableContentWithCompletionHandler_(got_content)
        done.wait(10)
        self.stream = holder.get("stream")

    def stop(self):
        stream = self.stream
        if stream is not None:
            done = threading.Event()
            try:
                stream.stopCaptureWithCompletionHandler_(lambda e: done.set())
                done.wait(2.0)        # attendre que macOS ait vraiment arrêté la capture
                log("son du Mac : capture arrêtée")
            except Exception as e:
                log("son du Mac : arrêt", repr(e))
        self._keep = (stream, self.output)   # garder les objets vivants jusqu'à la sortie


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
    """Écoute tous les claviers MIDI de l'ordinateur, y compris ceux branchés APRÈS l'ouverture de Connect,
    plus le port virtuel « Manu Play Cam ». Chaque note est envoyée au téléphone dès qu'elle arrive."""

    def __init__(self, link):
        self.link = link
        self.ports = []
        self.names = []
        self.opened = {}               # nom du port -> MidiIn ouvert
        self.virtual = None
        self.error = None
        self.running = False

    def start(self):
        try:
            import rtmidi  # noqa: F401
        except ImportError:
            self.error = "Module MIDI absent"
            return
        self.running = True
        try:
            import rtmidi
            v = rtmidi.MidiIn()
            v.open_virtual_port(VIRTUAL_PORT)
            v.ignore_types(sysex=True, timing=True, active_sense=True)
            v.set_callback(self._cb)
            self.virtual = v
        except Exception:
            pass                       # Windows : pas de port virtuel natif
        self._scan()
        threading.Thread(target=self._watch, daemon=True).start()

    def _scan(self):
        import rtmidi
        try:
            if getattr(self, "_probe", None) is None:
                self._probe = rtmidi.MidiIn()      # un seul client pour lister les ports (la liste se met à jour)
            names = self._probe.get_ports()
        except Exception as e:
            self.error = str(e)
            return
        current = set()
        for i, n in enumerate(names):
            if VIRTUAL_PORT.lower() in n.lower():
                continue
            current.add(n)
            if n in self.opened:
                continue
            m = rtmidi.MidiIn()
            try:
                m.open_port(i)
            except Exception:
                continue               # port pris en exclusif par un autre logiciel (Windows)
            m.ignore_types(sysex=True, timing=True, active_sense=True)
            m.set_callback(self._cb)
            self.opened[n] = m
            log("MIDI : clavier ouvert", n)
        for n in list(self.opened):
            if n not in current:       # clavier débranché
                try:
                    self.opened.pop(n).close_port()
                except Exception:
                    pass
                log("MIDI : clavier débranché", n)
        self.ports = list(self.opened.values()) + ([self.virtual] if self.virtual else [])
        self.names = list(self.opened) + ([VIRTUAL_PORT + " (port virtuel)"] if self.virtual else [])

    def _watch(self):
        while self.running:
            time.sleep(2)
            try:
                self._scan()
            except Exception as e:
                log("MIDI : recherche", repr(e))

    def _cb(self, event, _data=None):
        msg, _delta = event
        if msg:
            self.link.send_midi_bytes(msg)

    def stop(self):
        self.running = False
        for p in self.ports:
            try:
                p.close_port()
            except Exception:
                pass
        self.ports, self.names, self.opened = [], [], {}


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
    keep_awake()
    link = Link(usb=not args.no_usb)
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
_ACTIVITY = None


def keep_awake():
    """Mac : empêche macOS de ralentir Connect quand sa fenêtre est derrière Logic (App Nap),
    sinon le MIDI et le son arrivent en retard sur le téléphone."""
    global _ACTIVITY
    if not IS_MAC:
        return
    try:
        from Foundation import NSProcessInfo
        user_initiated, latency_critical = 0x00FFFFFF, 0xFF00000000
        _ACTIVITY = NSProcessInfo.processInfo().beginActivityWithOptions_reason_(
            user_initiated | latency_critical, "Manu Play Cam : MIDI et son en temps réel")
        log("Mac : mode temps réel actif (pas de mise en veille d'application)")
    except Exception as e:
        log("Mac : mode temps réel indisponible", repr(e))


def gui(screenshot=None, quit_after=None):
    import tkinter as tk
    from tkinter import ttk

    keep_awake()

    log("fenêtre : démarrage de Tk")
    root = tk.Tk()
    log("fenêtre : Tk", root.tk.call("info", "patchlevel"))
    root.title(APP)
    root.configure(bg=EBONY)
    root.geometry("520x760")
    root.minsize(480, 700)
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
    tk.Label(eb, text="  SON + MIDI · WI-FI OU CÂBLE", bg=EBONY, fg=GREEN, font=F(9, "bold")).pack(side="left")
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
    hint.pack(anchor="w", padx=16, pady=(0, 6))
    plist = tk.Frame(card, bg=EBONY2)                 # téléphones trouvés : un nom, un bouton
    plist.pack(fill="x", padx=16, pady=(0, 8))
    usb_row = tk.Frame(card, bg=EBONY2)
    usb_row.pack(fill="x", padx=16, pady=(2, 14))
    tk.Label(usb_row, text="CÂBLE USB", bg=EBONY2, fg=GREEN, font=F(8, "bold")).pack(side="left")
    usb_lbl = tk.Label(usb_row, text="", bg=EBONY2, fg=MUTE, font=F(9), wraplength=340, justify="left")
    usb_lbl.pack(side="left", padx=8)
    plist_state = {"key": None}

    def btn(parent, text, cmd, primary=True):
        # bouton dessiné (sur Mac, les boutons système ignorent les couleurs de la marque)
        bg, fg = (GREEN, EBONY) if primary else (EBONY3, IVORY)
        b = tk.Label(parent, text=text, bg=bg, fg=fg, font=F(10, "bold"), padx=14, pady=5, cursor="hand2")
        b.bind("<Button-1>", lambda e: cmd())
        b.bind("<Enter>", lambda e: b.config(bg=GREEN_DEEP if primary else EBONY2, fg=IVORY))
        b.bind("<Leave>", lambda e: b.config(bg=bg, fg=fg))
        return b

    def draw_phones():
        found = link.phone_list()
        cur = link.phone
        key = (tuple(found), cur, link.via_usb)
        if key == plist_state["key"]:
            return
        plist_state["key"] = key
        for w in plist.winfo_children():
            w.destroy()
        rows = [] if link.via_usb else list(found)
        if cur and cur[0] != "usb" and all(ip != cur[0] for ip, _ in rows):
            rows.insert(0, cur)
        for ip, name in rows:
            r = tk.Frame(plist, bg=EBONY2)
            r.pack(fill="x", pady=3)
            on = bool(cur) and cur[0] == ip
            tk.Label(r, text=("●  " if on else "○  ") + name, bg=EBONY2, fg=GREEN if on else IVORY,
                     font=F(11, "bold" if on else "normal")).pack(side="left")
            if on:
                btn(r, "Déconnecter", link.disconnect, primary=False).pack(side="right")
            elif not link.via_usb:
                btn(r, "Connecter", lambda ip=ip, name=name: link.connect_to(ip, name)).pack(side="right")

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

    help_txt = ("Ton macOS est antérieur à macOS 13 : le son du Mac ne peut pas être capté directement. Choisis une entrée "
                "audio (carte son, ou BlackHole pour le son des logiciels). Le MIDI et le câble marchent normalement."
                if IS_MAC and not MacSystemAudio.available else
                "Mac : à la première utilisation, autorise « Enregistrement de l'écran et audio système » dans "
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

    tk.Label(root, text=f"© MANU MUSIC PROD · Emmanuel NOUHOU   ·   v{VERSION}", bg=EBONY, fg=MUTE, font=F(8)).pack(side="bottom", pady=10)

    def start_audio(*args):
        capture_lock_set(False)
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
            status.config(text=f"Connecté à {link.phone[1]}" + ("  ·  câble" if link.via_usb else "  ·  Wi-Fi"))
            hint.config(text="Lance la vidéo dans l'application et joue : les notes et le son arrivent dans le téléphone.")
            lamp.itemconfig(lamp_dot, fill=GREEN)
        elif link.phone_list():
            status.config(text="Choisis ton téléphone")
            hint.config(text="Téléphones trouvés sur ton Wi-Fi :")
            lamp.itemconfig(lamp_dot, fill=AMBER)
        else:
            status.config(text="Recherche du téléphone…")
            hint.config(text="Ouvre Manu Play Cam sur le téléphone, sur le même Wi-Fi que cet ordinateur, ou branche-le avec un câble USB.")
            lamp.itemconfig(lamp_dot, fill=MUTE if int(time.time() * 2) % 2 else EBONY3)
        draw_phones()
        usb_lbl.config(text={
            "connected": "Téléphone relié par le câble.",
            "ready": "Téléphone branché. Ouvre Manu Play Cam dessus.",
            "unauthorized": "Sur le téléphone, accepte « Autoriser le débogage USB ».",
            "waiting": "Aucun téléphone branché. Le câble marche après avoir activé le débogage USB (voir l'application).",
            "no_adb": "Indisponible sur cet ordinateur.",
            "off": "",
        }.get(link.usb_state, ""), fg=GREEN if link.usb_state == "connected" else MUTE)
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
        log("fermeture")
        try:
            link.stop()                 # plus rien n'est envoyé au téléphone
            if state["audio"] and not IS_MAC:
                state["audio"].stop()
            state["midi"].stop()
        except Exception as e:
            log("fermeture :", repr(e))
        if IS_MAC:
            # Sur Mac, arrêter la capture ScreenCaptureKit pendant que Python se ferme peut planter :
            # on quitte tout de suite, macOS libère la capture avec le processus.
            log("fin")
            try:
                sys.stdout.flush()
            except Exception:
                pass
            os._exit(0)
        root.destroy()
    root.protocol("WM_DELETE_WINDOW", close)
    if IS_MAC:
        try:
            root.createcommand("::tk::mac::Quit", close)     # Cmd+Q passe aussi par la fermeture sûre
        except Exception:
            pass
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
    log("fin")
    # sortie immédiate : évite que macOS reçoive encore du son pendant que Python se ferme (plantage à la sortie)
    try:
        sys.stdout.flush()
    except Exception:
        pass
    os._exit(0)


if __name__ == "__main__":
    ap = argparse.ArgumentParser(description=APP)
    ap.add_argument("--console", action="store_true", help="mode texte, sans fenêtre")
    ap.add_argument("--tone", action="store_true", help="son et accords de test (mode texte)")
    ap.add_argument("--phone", help="adresse IP du téléphone (tests)")
    ap.add_argument("--no-usb", action="store_true", help="sans le câble (tests)")
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
