#!/bin/bash
# Test mic app tự build (.dev) trên R1 (Phicomm R1, 10.25.113.209).
# Chạy: bash tools/mictest.sh   -> rồi NÓI vào loa R1 khi nó báo.
# (App rớt kết nối sau mỗi lượt thoại -> chạy lại script cho mỗi lần test.)
DIR="$(cd "$(dirname "$0")" && pwd)"
PY=/opt/homebrew/anaconda3/envs/xiaozhi/bin/python
SH="$DIR/r1sh.py"
WLOG=/tmp/robot-whisper.log

echo "[1] tạm dừng watchdog R1 (khỏi nó tự restart aiboxplus giữa chừng)"
launchctl unload ~/Library/LaunchAgents/com.user.robot-r1watchdog.plist 2>/dev/null

echo "[2] tắt aiboxplus + mở app test .dev (nhường mic)"
$PY "$SH" "am force-stop info.dourok.voicebot; am force-stop info.dourok.voicebot.dev; am start -n info.dourok.voicebot.dev/info.dourok.voicebot.MainActivity 2>&1 | tail -1" 10 2>&1 | grep -aE "Starting|Error"

SRV="/Users/lucnguyen/Documents/git/robot ESP32/xiaozhi-esp32-server/main/xiaozhi-server/tmp/server.log"
WB=$(wc -l < "$WLOG" 2>/dev/null || echo 0)
SB=$(wc -l < "$SRV" 2>/dev/null || echo 0)
sleep 4
echo "[3] >>> Nói 'OK NABU' (chờ ~1 giây) RỒI nói câu hỏi rõ ràng <<<  (canh 90s)"
GOTW=0
for i in $(seq 1 18); do
  sleep 5
  lis=$(tail -n +$((SB+1)) "$SRV" 2>/dev/null | grep -aic "Received listen")
  [ "$lis" -gt 0 ] && [ "$GOTW" = 0 ] && { echo "  ✅ WAKE 'OK Nabu' ăn — server đang nghe, NÓI CÂU HỎI"; GOTW=1; }
  w=$(tail -n +$((WB+1)) "$WLOG" 2>/dev/null | grep -aiE "STT OK" | tail -2)
  if [ -n "$w" ]; then echo "=== STT (câu hỏi nghe được) ==="; echo "$w"; exit 0; fi
done
[ "$GOTW" = 1 ] && echo "(wake ăn nhưng câu hỏi rỗng — nói rõ hơn SAU khi wake ~1s)" || echo "(không bắt được wake — nói 'OK Nabu' to/rõ; chạy lại script)"
