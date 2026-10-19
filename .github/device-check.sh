#!/usr/bin/env bash
# Проверка на эмуляторе «как человек»: нажатия по экрану, галерея, камера, углы, фильтры,
# страницы, экспорт PDF/JPG с проверкой файлов, переименование, удаление, падения.
# Аргументы: имя набора; apk для проверки (по умолчанию отладочный).
set -uo pipefail
NAME="$1"
APK="${2:-app/build/outputs/apk/debug/app-debug.apk}"
PKG=io.github.etc51.skaner
OUT="device-check/$NAME"
mkdir -p "$OUT"
FAIL=0
STEP=0
log() { echo "== $*"; }
fail() { echo "!! ПРОВАЛ: $*"; echo "ПРОВАЛ: $*" >> "$OUT/result.txt"; FAIL=1; }
ok() { echo "ok: $*"; echo "ok: $*" >> "$OUT/result.txt"; }
info() { echo "info: $*"; echo "info: $*" >> "$OUT/result.txt"; }
shot() { STEP=$((STEP+1)); adb exec-out screencap -p > "$OUT/$(printf %02d $STEP)-$1.png"; }

dump() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb pull /sdcard/ui.xml /tmp/ui.xml >/dev/null 2>&1 || echo "" > /tmp/ui.xml
}
# Координаты центра элемента. Режим: text (точно), desc (content-desc содержит), id (resource-id содержит), textin (text содержит).
find_xy() {
  python3 - "$1" "$2" <<'PY'
import re, sys, html
mode, want = sys.argv[1], sys.argv[2]
xml = open('/tmp/ui.xml', encoding='utf-8', errors='ignore').read()
for m in re.finditer(r'<node [^>]*>', xml):
    n = m.group(0)
    def attr(a):
        t = re.search(' ' + a + r'="([^"]*)"', n)
        return html.unescape(t.group(1)) if t else ''
    hit = (mode == 'text' and attr('text') == want) or \
          (mode == 'textin' and want.lower() in attr('text').lower()) or \
          (mode == 'desc' and want.lower() in attr('content-desc').lower()) or \
          (mode == 'id' and want in attr('resource-id'))
    if hit:
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
        x1, y1, x2, y2 = map(int, b.groups())
        if x2 > x1 and y2 > y1:
            print((x1 + x2) // 2, (y1 + y2) // 2)
            break
PY
}
tap() { dump; local xy; xy=$(find_xy "$1" "$2"); [ -z "$xy" ] && return 1; adb shell input tap $xy; sleep "${3:-2}"; }
tap_text() { tap text "$1" "${2:-2}"; }
has() { dump; [ -n "$(find_xy "$1" "$2")" ]; }
has_text() { has text "$1"; }
wait_text() { for i in $(seq 1 "${2:-20}"); do has_text "$1" && return 0; sleep 1; done; return 1; }
dismiss_anr() {
  dump
  if grep -q "isn't responding" /tmp/ui.xml; then info "системное окно ANR, закрываю"; tap_text "Wait" || adb shell input keyevent 4; fi
}
start() { adb shell am start -n $PKG/ru.skaner.ui.MainActivity --es screen "$1" >/dev/null; sleep 3; dismiss_anr; }
in_app() { adb shell dumpsys window | grep -E "mCurrentFocus|mFocusedApp" | grep -q "$PKG"; }
crashes() { adb logcat -d -b crash | grep -c "Process: $PKG" || true; }
# Файлы приложения: только отладочная сборка разрешает run-as.
app_ls() { adb shell run-as $PKG sh -c "'ls $1'" 2>/dev/null | tr -d '\r'; }
app_pull() { adb shell run-as $PKG cat "$1" > "$2" 2>/dev/null; }

read -r W H < <(adb shell wm size | grep -o '[0-9]*x[0-9]*' | tail -1 | tr 'x' ' ')
API=$(adb shell getprop ro.build.version.sdk | tr -d '\r')
info "Android API $API, экран ${W}x${H}, apk $APK"

adb logcat -c; adb logcat -b crash -c
adb uninstall $PKG >/dev/null 2>&1 || true
adb install -r "$APK" || { fail "APK не устанавливается"; exit 1; }
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
DEBUG=0; adb shell run-as $PKG true 2>/dev/null && DEBUG=1

# Фото в галерею эмулятора.
adb shell mkdir -p /sdcard/Pictures
for f in .github/test-photos/*.jpg; do adb push "$f" /sdcard/Pictures/ >/dev/null; done
adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Pictures/list1.jpg >/dev/null 2>&1
adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Pictures/list2.jpg >/dev/null 2>&1
adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Pictures/bokom-exif.jpg >/dev/null 2>&1
adb shell content call --method scan_volume --uri content://media --arg external_primary >/dev/null 2>&1 || true
sleep 3

# Выбрать в системном окне одно фото с именем $1 (или первое). Разные версии Android показывают разные окна.
pick_photo() {
  local want="$1"
  sleep 3
  for attempt in 1 2 3 4 5 6; do
    dismiss_anr
    dump
    cp /tmp/ui.xml "$OUT/picker-$attempt.xml"
    # Новый выбор фото (Android 11+): плитки с описанием «Photo taken on…».
    if [ -n "$(find_xy desc "Photo taken")" ] || [ -n "$(find_xy desc "Фото")" ]; then
      xy=$(find_xy desc "Photo taken"); [ -z "$xy" ] && xy=$(find_xy desc "Фото")
      adb shell input tap $xy; sleep 2
      tap textin "Add" 3 || tap textin "Done" 3 || true
      return 0
    fi
    # Старое окно «Файлы» (Android 7–10): ищем файл по имени, при необходимости открываем меню.
    if [ -n "$(find_xy textin "$want")" ]; then
      xy=$(find_xy textin "$want"); adb shell input tap $xy; sleep 3
      in_app && return 0
      dump; cp /tmp/ui.xml "$OUT/picker-after-tap.xml"
      # В режиме выбора нескольких файлов нажатие только отмечает файл, дальше нужна кнопка «Выбрать».
      tap textin "Select" 3 || tap desc "Select" 3 || tap id "action_menu_select" 3 || tap textin "Open" 3 || tap desc "Open" 3 || tap textin "Done" 3 || true
      return 0
    fi
    # «Недавние» бывают пустыми: открываем боковое меню, раздел «Изображения», папку Pictures.
    tap textin "Pictures" 3 || tap text "Images" 3 || tap desc "Show roots" 3 || tap desc "Navigate up" 3 || sleep 2
  done
  return 1
}

log "1. Пустой экран"
start home
sleep 3; dismiss_anr
shot pusto
has_text "Сканируйте документы" && ok "пустой экран с подсказкой" || fail "нет подсказки на пустом экране"
has desc "Из галереи" && ok "кнопка галереи есть" || fail "нет кнопки галереи"

log "2. Фото из галереи → углы → Готово"
tap desc "Из галереи" 3 || fail "не нажимается кнопка галереи"
shot vybor-foto
if pick_photo "list1"; then
  if wait_text "Края листа" 30; then
    ok "из галереи открылся экран углов"
    sleep 2; shot ugly
    # Тянем левый верхний уголок: ищем его как точку рядом с краем листа нельзя, поэтому проверяем лупу жестом из угла изображения.
    adb shell input swipe $((W*30/100)) $((H*35/100)) $((W*32/100)) $((H*37/100)) 800
    tap_text "Готово" 3
    if wait_text "Поделиться" 40; then ok "страница выровнена, открыт документ"; else fail "после «Готово» не открылся документ"; fi
  else
    fail "после выбора фото не открылся экран углов"
  fi
else
  fail "не удалось выбрать фото в системном окне"
  if [ $DEBUG = 1 ]; then
    adb push .github/test-photos/list1.jpg /data/local/tmp/list1.jpg >/dev/null; adb shell chmod 644 /data/local/tmp/list1.jpg; adb shell run-as $PKG sh -c "'mkdir -p files && cp /data/local/tmp/list1.jpg files/in.jpg'"; adb shell am broadcast -n $PKG/ru.skaner.debug.DemoDataReceiver -a ru.skaner.debug.IMPORT --es path files/in.jpg >/dev/null; sleep 15
    start doc:imported
  fi
fi
shot dokument
in_app || fail "приложение закрылось после импорта"

log "3. Фильтры, поворот"
for f in "Ч/Б" "Серый" "Оригинал" "Улучшить"; do
  tap_text "$f" 6 && ok "фильтр $f нажат" || fail "нет фильтра $f"
done
shot filtry
tap_text "Повернуть" 6 && ok "поворот" || fail "нет кнопки поворота"
shot povorot
tap_text "Повернуть" 6; tap_text "Повернуть" 6; tap_text "Повернуть" 6

log "4. Вторая страница из галереи (фото боком с EXIF, 4000×3000)"
tap_text "Добавить" 2 || fail "нет кнопки «Добавить»"
tap_text "Выбрать из галереи" 3 || fail "нет пункта «Выбрать из галереи»"
if pick_photo "bokom"; then
  if wait_text "Края листа" 40; then
    shot ugly-bokom
    tap_text "Авто" 8 || fail "нет кнопки «Авто»"
    tap_text "Готово" 3
    wait_text "Страница 2 из 2" 40 && ok "вторая страница добавлена" || fail "вторая страница не добавилась"
  else
    fail "для второго фото не открылся экран углов"
  fi
else
  fail "не удалось выбрать второе фото"
fi
shot dve-stranitsy

if [ $DEBUG = 1 ]; then
  DOC=$(app_ls files/docs | head -1)
  for p in $(app_ls files/docs/$DOC | grep -v src | grep '\.jpg$'); do
    app_pull files/docs/$DOC/$p "$OUT/$p"
    python3 - "$OUT/$p" <<'PY' >> "$OUT/result.txt"
import sys, struct
d = open(sys.argv[1], 'rb').read()
i = 2
while i < len(d):
    if d[i] != 0xFF: break
    m = d[i+1]; l = struct.unpack('>H', d[i+2:i+4])[0]
    if m in (0xC0, 0xC1, 0xC2):
        h, w = struct.unpack('>HH', d[i+5:i+9]); print(f"info: страница {sys.argv[1].split('/')[-1]} {w}x{h}{' (книжная)' if h > w else ' (АЛЬБОМНАЯ)'}"); break
    i += 2 + l
PY
  done
  grep -q "АЛЬБОМНАЯ" "$OUT/result.txt" && fail "страница получилась альбомной: не учтён поворот EXIF или лишний поворот" || ok "все страницы книжные, EXIF учтён"
fi

log "5. Перестановка и меню"
tap desc "Ещё" 2 && tap_text "Страницу левее" 4 && ok "страница переставлена" || fail "не переставить страницу"
shot perestanovka

log "6. Экспорт PDF через «Поделиться»"
tap_text "Поделиться" 2 || fail "нет кнопки «Поделиться»"
shot share-menu
tap_text "Отправить PDF" 8 || fail "нет пункта «Отправить PDF»"
shot share-pdf
if in_app; then fail "окно «Поделиться» не открылось"; else ok "открылось системное окно «Поделиться»"; fi
adb shell input keyevent 4; sleep 2
if [ $DEBUG = 1 ]; then
  PDF=$(app_ls cache/export | grep '\.pdf$' | head -1)
  if [ -n "$PDF" ]; then
    app_pull "cache/export/$PDF" "$OUT/document.pdf"
    python3 - "$OUT/document.pdf" <<'PY' >> "$OUT/result.txt"
import sys, re
d = open(sys.argv[1], 'rb').read()
t = d.decode('latin-1')
pages = len(re.findall(r'/Type /Page\b', t))
sx = int(t[t.rfind('startxref')+9:].split()[0])
okx = t.startswith('xref', sx)
n = int(re.search(r'/Size (\d+)', t).group(1))
entries = t[sx:].split('\n')[3:3+n-1]
bad = [e for k, e in enumerate(entries) if not t.startswith(f"{k+1} 0 obj", int(e[:10]))]
jpeg = d.count(b'\xff\xd8\xff')
print(f"{'ok' if pages == 2 and okx and not bad and jpeg == 2 else 'ПРОВАЛ'}: PDF {len(d)//1024} КБ, страниц {pages}, JPEG {jpeg}, xref {'верный' if okx and not bad else 'НЕВЕРНЫЙ'}")
PY
    command -v pdfinfo >/dev/null && pdfinfo "$OUT/document.pdf" > "$OUT/pdfinfo.txt" 2>&1 && ok "pdfinfo читает PDF" || info "pdfinfo недоступен или не прочитал файл"
    command -v pdftoppm >/dev/null && pdftoppm -png -r 40 "$OUT/document.pdf" "$OUT/pdf-stranica" && ok "PDF отрисован в картинки"
  else
    fail "PDF не создан"
  fi
fi

log "7. Экспорт JPG"
tap_text "Поделиться" 2; tap_text "Отправить фото JPG" 8 || fail "нет пункта JPG"
if in_app; then fail "окно «Поделиться» для JPG не открылось"; else ok "JPG переданы в «Поделиться»"; fi
adb shell input keyevent 4; sleep 2
[ $DEBUG = 1 ] && { n=$(app_ls cache/export | grep -c '\.jpg$'); [ "$n" = 2 ] && ok "2 JPG подготовлены" || fail "JPG подготовлено: $n"; }

log "8. Сохранение PDF в «Файлы»"
tap_text "Поделиться" 2; tap_text "Сохранить PDF в файлы" 5 || fail "нет пункта сохранения"
shot sohranit
tap textin "SAVE" 2 || tap textin "Save" 2 || tap textin "Сохранить" 2 || tap id "action_menu_save" 2 || info "кнопка сохранения в «Файлах» не найдена"
sleep 6
shot posle-sohraneniya
if adb shell ls /sdcard/Download/ 2>/dev/null | grep -q '\.pdf'; then ok "PDF сохранён в Download"; else info "PDF в Download не найден (окно «Файлы» могло открыться в другой папке)"; adb shell input keyevent 4; fi
start home

log "9. Переименование и удаление страницы"
DOCNAME_TAP=$(true)
start home; sleep 2
adb shell input tap $((W/4)) $((H*35/100)); sleep 3
shot otkryt-iz-spiska
has_text "Поделиться" && ok "документ открывается из списка" || fail "документ не открывается из списка"
tap desc "Ещё" 2; tap_text "Переименовать" 2 || fail "нет переименования"
adb shell input keyevent 123; for i in $(seq 1 40); do adb shell input keyevent 67; done
adb shell input text "Test%sdoc"; sleep 1
tap_text "Сохранить" 3
has_text "Test doc" && ok "переименовано" || fail "название не изменилось"
tap_text "Удалить" 2; tap_text "Удалить" 4
has_text "Страница 1 из 1" && ok "страница удалена" || fail "страница не удалилась"
shot posle-udaleniya

log "10. Выгрузка процесса и возврат"
adb shell am kill $PKG; adb shell input keyevent 3; sleep 2
adb shell am start -n $PKG/ru.skaner.ui.MainActivity >/dev/null; sleep 4
has_text "Test doc" && ok "после перезапуска документ на месте" || fail "после перезапуска документа нет"
shot perezapusk

log "11. Камера"
start home
tap_text "Сканировать" 6
shot kamera
if in_app; then
  info "камера не открылась (в образе эмулятора может не быть приложения камеры)"
else
  adb shell input keyevent 27; sleep 4
  tap id "shutter_button" 4 || true
  tap id "done_button" 4 || tap desc "Done" 4 || tap id "btn_done" 4 || tap desc "Готово" 4 || true
  if wait_text "Края листа" 30; then ok "снимок с камеры открылся на экране углов"; shot kamera-ugly; tap_text "Готово" 3; wait_text "Поделиться" 40 && ok "снимок с камеры сохранён" || fail "снимок с камеры не сохранился"
  else info "камеру эмулятора не удалось нажать автоматически"; shot kamera-ne-nazhalas; fi
fi

log "12. Отмена нового снимка не оставляет пустых документов"
if [ $DEBUG = 1 ]; then
  before=$(app_ls files/docs | wc -l)
  adb shell am broadcast -n $PKG/ru.skaner.debug.DemoDataReceiver -a ru.skaner.debug.NEW >/dev/null; sleep 8
  PG=$(app_ls files/docs/new | grep -- '-src.jpg' | head -1 | sed 's/-src.jpg//')
  start "crop:new:$PG:new"
  adb shell input keyevent 4; sleep 4
  after=$(app_ls files/docs | wc -l)
  [ "$after" = "$before" ] && ok "отмена удалила черновик" || fail "после отмены осталось документов: $after (было $before)"
fi

log "13. Удаление документа"
start home
adb shell input swipe $((W/4)) $((H*35/100)) $((W/4)) $((H*35/100)) 1200; sleep 2
tap_text "Удалить" 2 && tap_text "Удалить" 3
shot posle-udaleniya-dokumenta

log "Итог"
c=$(crashes)
adb logcat -d -b crash > "$OUT/crash.txt" 2>&1
adb logcat -d | grep -E "$PKG|AndroidRuntime|OutOfMemory" | tail -300 > "$OUT/logcat.txt"
[ "$c" = 0 ] && ok "падений нет" || fail "падений приложения: $c"
cat "$OUT/result.txt"
grep -q "ПРОВАЛ" "$OUT/result.txt" && exit 1 || exit 0
