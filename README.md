# Manu Play Cam — MANU MUSIC PROD

Application Android pour pianistes, claviéristes et créateurs de contenu musical : on filme sa performance
et l'application enregistre **en même temps** la vidéo, le son de l'instrument et le MIDI. Le clavier virtuel
et le nom des accords sont **incrustés dans la vidéo** pendant qu'on joue.

## Ce que fait la version 1.0

| Fonction | Détail |
|---|---|
| Caméra | Avant / arrière, 4K ou 1080p ou 720p selon le téléphone, 30 ou 60 i/s si pris en charge (repli automatique sinon), mise au point par toucher, exposition, zoom, portrait / paysage / auto, aperçu en direct |
| Audio | Micro du téléphone ou **interface audio USB** (choix explicite du périphérique), 44,1 / 48 kHz, stéréo si possible, gain numérique, limiteur anti-saturation, vumètre, source « sans traitement » quand le téléphone la propose |
| MIDI | Claviers **USB** (branchement à chaud), **Bluetooth MIDI (BLE)** avec recherche, ordinateur par **câble USB** (mode « MIDI » d'Android) ou par **Wi-Fi** (compagnon fourni). Note On / Off, vélocité, canal, sustain (CC 64), Program Change, autres contrôleurs, pitch bend |
| Accords | Reconnaissance à partir des notes MIDI : majeurs, mineurs, 6, 7, maj7, m7, m7♭5, dim7, sus2/4, 7sus4, add9, 9, maj9, m9, 11, 13, altérations (♭9, ♯9, ♯11, ♭13…), renversements (C/E) |
| Clavier virtuel | 88 / 76 / 61 / 49 / 37 / 25 touches, noms des notes (C D E ou Do Ré Mi), vélocité, pédale, opacité, 6 couleurs, taille de l'accord, **déplacement et redimensionnement directement sur l'image** |
| Fichiers | Par prise : `performance.mp4` (image + son de l'instrument + clavier incrusté), `audio.wav` (son d'origine), `performance.mid` (calé sur le début de la vidéo). Copie automatique dans Films › Manu Play Cam, export MIDI / WAV vers Téléchargements, partage |
| Écrans | Accueil, Caméra, Connexion MIDI, Audio, Clavier virtuel, Paramètres, Galerie, Lecture |

## Synchronisation (comment c'est fait)

Toutes les sources utilisent la même horloge du téléphone (`System.nanoTime`) :
- **Vidéo** : l'instant de la première image est calculé à partir des événements d'enregistrement de CameraX
  (instant de l'événement − durée déjà enregistrée, en gardant la meilleure valeur sur les premières secondes).
- **Audio** : l'instant de la première trame est donné par l'horodatage matériel `AudioRecord.getTimestamp`.
- **MIDI** : chaque message porte l'horodatage d'arrivée fourni par Android.
- **Clavier incrusté** : dessiné dans les images au moment où elles sont captées (CameraX `OverlayEffect`), il ne peut donc pas être décalé par rapport à l'image.

L'audio est ensuite placé dans le MP4 au bon décalage (encodage AAC, l'image n'est pas réencodée) et le
fichier MIDI est écrit avec un minutage relatif à la première image. Chaque position est mesurée depuis le même
point de départ : il n'y a pas de dérive qui s'accumule sur les longues prises. Comme chaque téléphone a sa
propre latence, **Paramètres › Synchronisation** permet une correction fine (± 300 ms).

## Limites connues (dites clairement)

- **Son de l'ordinateur par le Wi-Fi** : pas dans la version 1.0. Pour enregistrer le son de Logic / Ableton / MainStage,
  relie la sortie audio de l'ordinateur (ou de sa carte son) à une interface audio USB branchée sur le téléphone.
  Le MIDI de l'ordinateur, lui, passe par le Wi-Fi (compagnon) ou par le câble USB.
- **Accords à partir du micro seul** : pas de reconnaissance dans la version 1.0. L'application préfère n'afficher aucun
  accord plutôt qu'un accord faux ; la reconnaissance est faite à partir du MIDI, qui est exact.
- **4K à 60 i/s** : seulement sur les téléphones qui le proposent ; sinon l'application choisit la meilleure combinaison possible et l'indique.
- **Interfaces USB** : elles doivent être « class compliant » (reconnues par Android sans pilote).

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

## Compagnon ordinateur (MIDI par le Wi-Fi)

Dossier `companion/` : script Python pour Mac et Windows. Voir `companion/LISEZMOI.md`.

## Architecture

```
app/src/main/java/com/manumusicprod/playcam/
  core/      ChordDetector, MidiParser, MidiFileWriter, WavWriter   (Java pur, testé : app/src/test)
  midi/      MidiHub (USB, Bluetooth, USB périphérique), NetworkMidiServer (Wi-Fi), Performance (état des touches)
  audio/     AudioEngine (choix du périphérique, gain, limiteur, vumètre, WAV horodaté)
  camera/    CameraController (CameraX, OverlayEffect, qualité, i/s, zoom, mise au point, exposition)
  session/   SessionRecorder (synchronisation), AvMuxer (MP4 final), SessionStore (galerie, export, partage)
  ui/        Écrans Jetpack Compose et KeyboardRenderer (clavier virtuel)
```

© MANU MUSIC PROD · Emmanuel NOUHOU
