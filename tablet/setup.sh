#!/usr/bin/env bash
# Android 태블릿을 HDMI 캡처카드용 전용 모니터로 구성한다.
#   1) UVC Monitor 설치 + 권한  2) 디블로팅  3) 시스템 최적화  4) 키오스크(홈 앱)
#
# 사용법:
#   ./setup.sh -s <serial|ip:port> [옵션]
#     -s SERIAL      adb 기기 (adb devices 의 값, 무선이면 ip:port)
#     -l LEVEL       디블로팅 수준: none | basic | extreme   (기본 extreme)
#     -K             키오스크(홈 앱 지정, 잠금화면 해제) 생략
#     -n             dry-run: 실제 변경 없이 할 일만 출력
#   환경변수 ADB    adb 경로 (기본: Windows adb → 없으면 PATH 의 adb)
#
# 무선 디버깅 페어링(Android 11+):
#   adb pair <ip>:<pair-port> <6자리코드> && adb connect <ip>:<connect-port>
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(dirname "$HERE")"
APK="$ROOT/dist/uvc-monitor.apk"
PKG=ai.waion.uvcmonitor
ACT=$PKG/.MainActivity

if [[ -z "${ADB:-}" ]]; then
  if [[ -x /mnt/c/Users/Public/platform-tools/adb.exe ]]; then ADB=/mnt/c/Users/Public/platform-tools/adb.exe
  else ADB=$(command -v adb || true); fi
fi
[[ -n "$ADB" ]] || { echo "adb 를 찾을 수 없습니다 (ADB=경로 로 지정)"; exit 1; }

SERIAL="" LEVEL=extreme KIOSK=1 DRY=0
while getopts "s:l:Kn" o; do
  case $o in
    s) SERIAL=$OPTARG ;; l) LEVEL=$OPTARG ;; K) KIOSK=0 ;; n) DRY=1 ;;
    *) sed -n '2,15p' "$0"; exit 1 ;;
  esac
done
[[ -n "$SERIAL" ]] || { sed -n '2,15p' "$0"; exit 1; }
[[ $LEVEL =~ ^(none|basic|extreme)$ ]] || { echo "LEVEL 은 none|basic|extreme"; exit 1; }

sh_() { "$ADB" -s "$SERIAL" shell "$@" </dev/null | tr -d '\r'; }
run() { if ((DRY)); then echo "  [dry-run] $*"; else sh_ "$@"; fi; }
# Windows adb.exe 는 Windows 경로가 필요하다.
hostpath() { if [[ $ADB == *.exe ]]; then wslpath -w "$1"; else echo "$1"; fi; }
step() { printf '\n== %s\n' "$*"; }

# ---------------------------------------------------------------- 0. 기기 확인
step "기기 확인"
state=$("$ADB" -s "$SERIAL" get-state 2>&1 | tr -d '\r')
[[ $state == device ]] || { echo "adb 연결 안 됨: $state"; exit 1; }
MODEL=$(sh_ getprop ro.product.model)
SDK=$(sh_ getprop ro.build.version.sdk)
ID=$(sh_ getprop ro.serialno)
echo "모델 $MODEL / Android SDK $SDK / 일련번호 $ID"
LOG="$HERE/logs/${MODEL}_${ID}"
if ((DRY)); then
  ORIGINAL_LOG="$LOG"
  LOG=$(mktemp -d)
  trap 'rm -rf "$LOG"' EXIT
  [[ ! -f $ORIGINAL_LOG/enabled-before.txt ]] || cp "$ORIGINAL_LOG/enabled-before.txt" "$LOG/"
fi
mkdir -p "$LOG"
[[ -f $LOG/enabled-before.txt ]] || sh_ pm list packages -e | sed 's/^package://' | sort > "$LOG/enabled-before.txt"

# 캡처카드가 꽂혀 있으면 호환성 검사 (bulk 엔드포인트 + MJPEG 필요)
for dev in $(sh_ 'for d in /sys/bus/usb/devices/*; do [ -f $d/bDeviceClass ] && [ -f $d/idVendor ] && [ "$(cat $d/idVendor)" != 1d6b ] && echo $d; done'); do
  echo "USB 장치 $dev: $(sh_ cat "$dev/idVendor"):$(sh_ cat "$dev/idProduct") $(sh_ cat "$dev/product" 2>/dev/null) speed=$(sh_ cat "$dev/speed")"
  sh_ "od -An -tx1 -v $dev/descriptors" > "$LOG/usb-descriptors.hex"
  command -v python3 >/dev/null && python3 -I "$HERE/uvc_descriptors.py" "$LOG/usb-descriptors.hex"
done

# ---------------------------------------------------------------- 1. 뷰어 설치
step "UVC Monitor 설치"
[[ -f $APK ]] || { echo "APK 없음: $APK (먼저 빌드: gradle :app:assembleRelease)"; exit 1; }
if ((DRY)); then echo "  [dry-run] install $APK"
else "$ADB" -s "$SERIAL" install -r "$(hostpath "$APK")" | tr -d '\r' | tail -1; fi
run pm grant $PKG android.permission.CAMERA
run dumpsys deviceidle whitelist +$PKG >/dev/null
run cmd appops set $PKG RUN_IN_BACKGROUND allow

# ---------------------------------------------------------------- 2. 디블로팅
lists=()
[[ $LEVEL == basic || $LEVEL == extreme ]] && lists+=("$HERE/debloat-list.txt")
[[ $LEVEL == extreme ]] && lists+=("$HERE/debloat-list-extreme.txt")
if ((${#lists[@]})); then
  step "디블로팅 ($LEVEL) — 삭제가 아닌 비활성화(pm disable-user), restore.sh 로 복구 가능"
  enabled=" $(tr '\n' ' ' < "$LOG/enabled-before.txt") "
  ok=0 skip=0 fail=0
  echo "# $(date '+%F %T') level=$LEVEL" >> "$LOG/debloat-result.log"  # 누적 기록 (restore.sh 가 OK 항목 사용)
  while read -r p; do
    if [[ $enabled != *" $p "* ]]; then ((skip++)); echo "SKIP $p (없음/이미 비활성)" >> "$LOG/debloat-result.log"; continue; fi
    if ((DRY)); then echo "  [dry-run] disable $p"; ((ok++)); continue; fi
    r=$(sh_ pm disable-user --user 0 "$p" 2>&1)
    if [[ $r == *disabled-user* ]]; then ((ok++)); echo "OK $p" >> "$LOG/debloat-result.log"
    else ((fail++)); echo "FAIL $p :: $r" >> "$LOG/debloat-result.log"; fi
  done < <(cat "${lists[@]}" | grep -v '^#' | grep .)
  echo "비활성화 $ok / 건너뜀 $skip / 실패 $fail  (상세: $LOG/debloat-result.log)"
fi

# ---------------------------------------------------------------- 3. 시스템 최적화
step "시스템 최적화"
for k in window_animation_scale transition_animation_scale animator_duration_scale; do
  run settings put global $k 0
done
run cmd power set-mode 0                       # 절전 모드 끔 (CPU 제한 해제)
run settings put global low_power 0
run settings put global wifi_scan_always_enabled 0
run settings put global ble_scan_always_enabled 0
run settings put secure location_mode 0
run settings put global stay_on_while_plugged_in 7
# Samsung 눈 보호(블루라이트) 필터
run settings put system blue_light_filter 0
run settings put system blue_light_filter_scheduled 0
echo "※ Samsung 기기는 설정값만으로 눈 보호 필터가 실제로 꺼지지 않을 수 있습니다."
echo "  화면이 누렇게 보이면: 설정 > 디스플레이 > 편안하게 화면 보기 스위치를 한 번 켰다 끄세요."

# ---------------------------------------------------------------- 4. 키오스크
if ((KIOSK)); then
  step "키오스크 (홈 앱 지정)"
  run cmd package set-home-activity $ACT
  if [[ $(sh_ locksettings verify 2>&1) == *successfully* ]]; then
    run locksettings set-disabled true
    echo "잠금 화면 해제 (비밀번호 없음)"
  else
    echo "화면 잠금 비밀번호가 설정되어 있어 잠금 화면은 유지합니다 (뷰어는 잠금 화면 위에 표시됨)."
  fi
fi

# ---------------------------------------------------------------- 5. 실행
step "뷰어 실행"
run am start -n $ACT >/dev/null
cat <<EOF

완료. 확인할 것:
  - PC: Win+P → '확장', 캡처카드 모니터 해상도 1920x1080
  - 태블릿에 USB 권한 창이 뜨면 '확인' (캡처카드를 다시 꽂을 때 자동 실행됨)
  - 뷰어 화면을 길게 누르면 메뉴(비율/상태표시/재시작/설정/삼성 홈)
  - 원복: $HERE/restore.sh $SERIAL
EOF
