#!/usr/bin/env bash
# Снимает скриншоты отладочной сборки в эмуляторе на демо-документах.
set -euo pipefail
PKG=io.github.etc51.skaner
RECEIVER=$PKG/ru.skaner.debug.DemoDataReceiver
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell settings put global window_animation_scale 0
mkdir -p screenshots

shot() {
  adb shell am start -n $PKG/ru.skaner.ui.MainActivity --es screen "$1"
  sleep 4
  adb exec-out screencap -p > "screenshots/$2.png"
}

# Пустой экран до демо-данных.
shot home 0-pusto
adb shell am broadcast -n $RECEIVER -a ru.skaner.debug.SEED
sleep 25
shot home 1-dokumenty
shot doc:demo1 2-dokument
shot doc:demo3 3-chb
adb shell am broadcast -n $RECEIVER -a ru.skaner.debug.NEW
sleep 8
shot crop:new:$(adb shell run-as $PKG ls files/docs/new | grep -- '-src.jpg' | head -1 | sed 's/-src.jpg//' | tr -d '\r'):new 4-ugly

# Тёмная тема.
adb shell cmd uimode night yes
shot home 5-temnaya
shot doc:demo1 6-temnaya-dokument
adb shell cmd uimode night no
adb logcat -d -b crash > screenshots/crash.txt || true
ls -la screenshots
