# Manu Play Cam Bridge — compagnon Mac / Windows

Envoie au téléphone le MIDI de **Logic Pro, Ableton Live, MainStage** (ou d'un clavier branché à l'ordinateur)
par le **Wi-Fi local**. Le téléphone affiche les notes et les accords et les enregistre avec la vidéo.

## Installation (une seule fois)

1. Installe **Python 3** : python.org (Windows : coche « Add Python to PATH »). Sur Mac, il est souvent déjà installé.
2. Dans le Terminal (Mac) ou l'Invite de commandes (Windows) :
   ```
   python3 -m pip install python-rtmidi
   ```
   (sous Windows, tape `python` au lieu de `python3`)

## Utilisation

1. Sur le téléphone : Manu Play Cam › **MIDI** › active « Recevoir le MIDI par le Wi-Fi ».
   Le téléphone et l'ordinateur doivent être sur **le même réseau Wi-Fi**.
2. Sur l'ordinateur, dans le dossier `companion` :
   ```
   python3 mmp_bridge.py --demo
   ```
   Des accords de test (Cmaj7, Am7, Dm7, G7) doivent s'afficher sur le téléphone. La liaison fonctionne.
3. **Mac** : lance `python3 mmp_bridge.py`. Un port MIDI « Manu Play Cam » apparaît : dans Logic, Ableton ou
   MainStage, choisis-le comme **sortie MIDI** de la piste (ou envoie-y ton clavier).
4. **Windows** : installe **loopMIDI** (gratuit), crée un port nommé « Manu Play Cam », envoie-y le MIDI depuis
   ton logiciel, puis lance `python mmp_bridge.py --port "Manu Play Cam"`.

Autres options :
- `--list` : liste les ports MIDI de l'ordinateur
- `--port "Nom"` : relaie un port existant (ton clavier USB, un bus IAC sur Mac…)
- `--ip 192.168.x.x` : si le téléphone n'est pas trouvé automatiquement (l'adresse est affichée dans l'écran MIDI de l'application)

Au démarrage, le compagnon affiche la **latence réseau** mesurée. Sur un Wi-Fi domestique, elle est en général de quelques millisecondes.

## Sans Wi-Fi : par câble

Relie le téléphone à l'ordinateur en USB, choisis **MIDI** dans la notification USB d'Android :
le téléphone devient un appareil MIDI pour l'ordinateur. Sélectionne-le comme sortie MIDI dans ton logiciel.
Aucun compagnon n'est nécessaire dans ce cas.
