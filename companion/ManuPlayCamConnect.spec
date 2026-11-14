# -*- mode: python ; coding: utf-8 -*-
# Construction de Manu Play Cam Connect (Mac : .app, Windows : dossier + .exe) avec PyInstaller.
import sys
from PyInstaller.utils.hooks import collect_data_files

datas = collect_data_files("soundcard")
hidden = ["rtmidi", "soundcard", "numpy"]
if sys.platform == "darwin":
    hidden += ["ScreenCaptureKit", "CoreMedia", "objc", "Foundation"]
icon = "assets/icon.icns" if sys.platform == "darwin" else "assets/icon.ico"

a = Analysis(["mmp_connect.py"], pathex=[], binaries=[], datas=datas, hiddenimports=hidden,
             hookspath=[], runtime_hooks=[], excludes=["matplotlib", "scipy", "pandas"], noarchive=False)
pyz = PYZ(a.pure)
exe = EXE(pyz, a.scripts, [], exclude_binaries=True, name="Manu Play Cam Connect", debug=False,
          bootloader_ignore_signals=False, strip=False, upx=False, console=False, icon=icon)
coll = COLLECT(exe, a.binaries, a.datas, strip=False, upx=False, name="Manu Play Cam Connect")

if sys.platform == "darwin":
    app = BUNDLE(coll, name="Manu Play Cam Connect.app", icon=icon, bundle_identifier="com.manumusicprod.playcamconnect",
                 info_plist={
                     "CFBundleName": "Manu Play Cam Connect",
                     "CFBundleDisplayName": "Manu Play Cam Connect",
                     "CFBundleShortVersionString": "1.0.1",
                     "CFBundleVersion": "2",
                     "LSMinimumSystemVersion": "13.0",
                     "NSHighResolutionCapable": True,
                     "NSMicrophoneUsageDescription": "Manu Play Cam Connect capte le son d'une entrée audio pour l'envoyer à ton téléphone.",
                     "NSLocalNetworkUsageDescription": "Manu Play Cam Connect envoie le son et le MIDI à ton téléphone sur le Wi-Fi.",
                     "NSScreenCaptureUsageDescription": "Manu Play Cam Connect capte le son du Mac (Logic, Cubase…) pour l'envoyer à ton téléphone. Aucune image n'est enregistrée.",
                 })
