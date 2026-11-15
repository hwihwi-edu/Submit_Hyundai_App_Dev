#!/usr/bin/env bash
# 1번 개발자 도구: 노트북에서 친 문장을 에뮬레이터 앱(여행 탭 검색창·개발자 화면 6번)으로 보낸다.
# 앱 설정 > 앱 버전 5번 눌러 개발자 모드가 켜져 있어야 받는다. 여행 탭이 열려 있으면 바로 추천까지 한다.
# 사용: tools/dev_text.sh "여자친구랑 1박2일 부산"
#       tools/dev_text.sh            (인자 없으면 한 줄씩 입력, 빈 줄이면 끝)
set -euo pipefail
ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
command -v "$ADB" >/dev/null 2>&1 || ADB=adb
USER_ID="${USER_ID:-10}"   # Pleos(AAOS) 앱은 사용자 10

send() {
  local text="$1"
  # 작은따옴표를 안전하게 감싼다(셸 한 번 더 거치므로).
  local quoted=${text//\'/\'\\\'\'}
  "$ADB" shell "am broadcast --user $USER_ID -a com.team.travelmap.DEV_TEXT --es text '$quoted'" >/dev/null
  echo "보냄: $text"
}

if [ $# -gt 0 ]; then
  send "$*"
else
  echo "보낼 문장을 입력하세요(빈 줄이면 끝)."
  while IFS= read -r line; do
    [ -z "$line" ] && break
    send "$line"
  done
fi
