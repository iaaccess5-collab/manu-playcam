# Manu Play Cam — MANU MUSIC PROD

Application Android pour pianistes, claviéristes et créateurs de contenu musical : on filme sa performance
et l'application enregistre **en même temps** la vidéo, le son de l'instrument et le MIDI. Le clavier virtuel
et le nom des accords sont **incrustés dans la vidéo** pendant qu'on joue.

## Comment ça marche (version 1.2)

Deux façons de relier le téléphone à l'ordinateur, qui marchent chacune seule :

- **Wi-Fi** : l'ordinateur et le téléphone sur le même Wi-Fi. Ouvre **Manu Play Cam Connect** : le téléphone voit
  l'ordinateur **par son nom**, l'ordinateur voit le téléphone par son nom. Touche « Connecter » d'un côté ou de l'autre.
  Aucune adresse à taper ; la fois suivante, ils se retrouvent tout seuls.
- **Câble USB** : active une fois le « Débogage USB » du téléphone, branche-le. Connect ouvre la liaison tout seul
  (adb est inclus). Quand le câble est branché, il passe avant le Wi-Fi.

Connect envoie au téléphone **le MIDI** que tu joues (ton clavier relié à l'ordinateur, ou une piste envoyée vers le port
« Manu Play Cam ») et **le son** de ton logiciel (Logic, Cubase, Ableton, MainStage…). Dans l'application, tu filmes :
le clavier virtuel s'allume, l'accord s'affiche, et le son du logiciel entre dans la vidéo. Sans son de l'ordinateur,
la vidéo prend automatiquement le micro du téléphone.

| Élément | Détail |
|---|---|
| Caméra | Avant / arrière, 4K / 1080p / 720p selon le téléphone, 30 ou 60 i/s si pris en charge, mise au point au toucher, exposition, zoom, portrait / paysage |
| Son | Son de l'ordinateur par le Wi-Fi ou le câble (48 kHz stéréo, sans compression), ou micro du téléphone. Vumètres stéréo à segments et voyant « SON ENTRANT » |
| Liaison | Wi-Fi (choix par le nom) ou câble USB, avec Manu Play Cam Connect |
| Accords | Reconnus depuis le MIDI : majeurs, mineurs, 6, 7, maj7, m7, m7♭5, dim7, sus, add9, 9, 11, 13, altérations, renversements |
| Clavier virtuel | 25 à 88 touches, noms des notes, vélocité, pédale, couleurs, opacité, position et taille réglables sur l'image |
| Fichiers | `performance.mp4` (image + son + clavier incrusté), `audio.wav`, `performance.mid` calé sur la vidéo |

## Manu Play Cam Connect (ordinateur)

- **Mac** (macOS 10.15 ou plus récent ; son du Mac capté directement à partir de macOS 13) : `.dmg`. Le son du Mac est capté directement (autorisation « Enregistrement de l'écran
  et audio système », demandée une fois), sans logiciel supplémentaire. Ton clavier relié au Mac est capté directement.
  Première ouverture : l'application n'étant pas signée par Apple, fais clic droit › Ouvrir, ou Réglages Système ›
  Confidentialité et sécurité › « Ouvrir quand même ».
- **Windows** (10 / 11) : installateur `.exe`. Le son de l'ordinateur est capté directement (WASAPI loopback).
  Un clavier déjà ouvert en exclusif par ton logiciel ne peut pas être lu en même temps : envoie alors la piste MIDI
  vers un port virtuel (loopMIDI) ou utilise la session rtpMIDI.

Protocole (UDP 5010 en Wi-Fi ; TCP 5011 par le câble, via `adb reverse`, chaque paquet précédé de sa longueur) : annonce `MMPA`, découverte `MMPD`/`MMPH`, choix `MMPC`, déconnexion `MMPX`, présentation `MMPN`, MIDI `MMPM`, son `MMPS`
(PCM 16 bits, blocs de 5 ms numérotés). Le téléphone recale chaque bloc sur sa propre horloge et comble les pertes par du silence.

## Synchronisation

Toutes les sources sont ramenées à l'horloge du téléphone : première image de la vidéo (événements CameraX), première
trame du son (index des blocs reçus, ou horodatage matériel du micro), messages MIDI (instant de réception).
Le clavier est dessiné dans les images au moment où elles sont captées. Paramètres › Synchronisation permet une
correction fine (± 300 ms) propre à chaque téléphone et à chaque Wi-Fi.

## Limites connues

- Le Wi-Fi doit être un réseau domestique ou de studio ; un Wi-Fi « invité » isole souvent les appareils.
- Sans MIDI, aucun accord n'est affiché (l'application ne devine pas d'accord à partir du son).
- Le son par le Wi-Fi a quelques dizaines de millisecondes de retard, compensées automatiquement à l'assemblage.

## Compiler

L'APK est compilé automatiquement par **GitHub Actions** à chaque modification (`.github/workflows/build-apk.yml`)
et publié dans l'onglet **Releases** du dépôt. Le flux exécute aussi les tests du cœur musical.

En local avec Android Studio (Koala ou plus récent) : ouvrir le dossier, puis `Build › Build APK(s)`.
En ligne de commande : `gradle :app:assembleRelease` (Gradle 8.9, JDK 17).

L'APK est signé avec une **clé de test** incluse (`app/mmp-test.keystore`, mot de passe `manuplaycam`),
pour qu'il s'installe directement et que les mises à jour gardent la même signature. Pour publier sur le
Google Play Store, crée ta propre clé et remplace-la dans `app/build.gradle.kts`.

## Installer sur le téléphone

1. Télécharge le fichier `.apk` depuis la page **Releases** du dépôt, sur le téléphone.
2. Ouvre-le. Android demande d'autoriser l'installation depuis le navigateur ou le gestionnaire de fichiers : accepte.
3. Lance **Manu Play Cam** et autorise la caméra et le micro.

## Architecture

```
app/src/main/java/com/manumusicprod/playcam/
  core/      ChordDetector, MidiParser, MidiFileWriter, WavWriter   (Java pur, testé : app/src/test)
  core/      + ConnectReceiver (son et MIDI de Connect, Wi-Fi et câble)
  midi/      MidiHub (liaison avec l'ordinateur), Performance (état des touches)
  audio/     AudioEngine (micro du téléphone : gain, limiteur, vumètre, WAV horodaté)
companion/   Manu Play Cam Connect (Python : interface, son, MIDI, construction .dmg et .exe)
  camera/    CameraController (CameraX, OverlayEffect, qualité, i/s, zoom, mise au point, exposition)
  session/   SessionRecorder (synchronisation), AvMuxer (MP4 final), SessionStore (galerie, export, partage)
  ui/        Écrans Jetpack Compose et KeyboardRenderer (clavier virtuel)
```

© MANU MUSIC PROD · Emmanuel NOUHOU
