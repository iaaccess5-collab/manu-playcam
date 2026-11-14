#!/usr/bin/env bash
# Test de bout en bout dans un téléphone Android virtuel :
# installe l'APK, simule un ordinateur « Manu Play Cam Connect » (son + accords), capture les écrans,
# filme 8 secondes puis vérifie la vidéo produite (pistes, durée, image avec le clavier incrusté).
set -x
OUT=previews; mkdir -p $OUT
PKG=com.manumusicprod.playcam
APK=$(ls apk/*.apk | head -1)
adb install -r "$APK"
adb shell pm grant $PKG android.permission.CAMERA
adb shell pm grant $PKG android.permission.RECORD_AUDIO
adb emu redir add udp:5010:5010 || true
python3 companion/mmp_connect.py --console --tone --phone 127.0.0.1 --seconds 240 > $OUT/connect-log.txt 2>&1 &
sleep 3
for S in HOME COMPUTER KEYBOARD SETTINGS; do
  adb shell am force-stop $PKG
  adb shell am start -n $PKG/.MainActivity --es mmp_screen $S
  sleep 9
  adb exec-out screencap -p > $OUT/ecran-$S.png
done
adb shell am force-stop $PKG
adb shell am start -n $PKG/.MainActivity --es mmp_screen CAMERA --ei mmp_autorecord 8
sleep 9
adb exec-out screencap -p > $OUT/ecran-CAMERA-enregistrement.png
sleep 30
adb exec-out screencap -p > $OUT/ecran-CAMERA-apres.png
adb shell ls -R /sdcard/Android/data/$PKG/files/sessions > $OUT/sessions.txt 2>&1
adb pull /sdcard/Android/data/$PKG/files/sessions $OUT/sessions || true
adb logcat -d -t 400 | grep -iE "playcam|AndroidRuntime|FATAL|CameraX" > $OUT/logcat.txt || true
V=$(find $OUT/sessions -name 'performance.mp4' -o -name 'video.mp4' | head -1)
if [ -n "$V" ]; then
  ffprobe -v error -show_entries stream=codec_type,codec_name,width,height,sample_rate,channels:format=duration -of default=nw=1 "$V" > $OUT/ffprobe.txt 2>&1
  ffmpeg -y -v error -ss 4 -i "$V" -frames:v 1 $OUT/image-video.png
  ffmpeg -y -v error -i "$V" -t 8 -af astats=metadata=1:reset=0 -f null - 2> $OUT/audio-stats.txt || true
  find $OUT/sessions -name '*.mid' -exec python3 -c "import mido,sys;m=mido.MidiFile(sys.argv[1]);print('MIDI', m.length, sum(1 for t in m.tracks for x in t if x.type=='note_on'))" {} \; >> $OUT/ffprobe.txt 2>&1 || true
fi
ls -la $OUT
exit 0
