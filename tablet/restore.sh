#!/usr/bin/env bash
# setup.sh 원복: 해당 기기에서 setup.sh 가 비활성화한 패키지만 다시 켜고, 시스템 설정/홈 앱을 되돌린다.
# 사용: ./restore.sh <serial|ip:port> [--uninstall]   (--uninstall: UVC Monitor 도 삭제)
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
if [[ -z "${ADB:-}" ]]; then
  if [[ -x /mnt/c/Users/Public/platform-tools/adb.exe ]]; then ADB=/mnt/c/Users/Public/platform-tools/adb.exe
  else ADB=$(command -v adb); fi
fi
S=${1:?사용법: restore.sh <serial|ip:port> [--uninstall]}
sh_() { "$ADB" -s "$S" shell "$@" </dev/null | tr -d '\r'; }

LOG="$HERE/logs/$(sh_ getprop ro.product.model)_$(sh_ getprop ro.serialno)"
[[ -d $LOG ]] || { echo "이 기기의 setup 기록이 없습니다: $LOG"; exit 1; }

n=0
for f in "$LOG"/debloat*-result.log; do
  [[ -f $f ]] || continue
  for p in $(awk '$1=="OK"{print $2}' "$f"); do sh_ pm enable --user 0 "$p" >/dev/null; ((n++)); done
done
echo "패키지 $n 개 재활성화"

for k in window_animation_scale transition_animation_scale animator_duration_scale; do
  sh_ settings put global $k 1.0
done
sh_ settings put secure location_mode 3
sh_ settings put global wifi_scan_always_enabled 1
sh_ settings put global stay_on_while_plugged_in 0
sh_ locksettings set-disabled false >/dev/null

# 기본 런처 복구: UVC Monitor 이외의 첫 번째 HOME 액티비티
home=$(sh_ cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME \
  | grep '/' | grep -v ai.waion.uvcmonitor | grep -v -i fallback | head -1 | tr -d ' ')
[[ -n $home ]] && sh_ cmd package set-home-activity "$home" && echo "홈 앱: $home"

if [[ ${2:-} == --uninstall ]]; then
  "$ADB" -s "$S" uninstall ai.waion.uvcmonitor
fi
