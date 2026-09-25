#!/bin/bash
# Run a shell command on the R1 over the on-device shell on port 8080 (uid=system).
#   bash tools/r1sh.sh 'getprop ro.product.model'          # default host + 8s drain
#   bash tools/r1sh.sh 'pm install -r /data/local/tmp/rc.apk' 100
#
# Why not tools/r1sh.py: macOS refuses Local Network access per binary on this Mac, so python's
# socket cannot reach the speaker at all while `nc` can. python does the framing, `nc` the socket.
# See tools/r1ws.py.
#
# Careful: stdin is held open with `sleep` rather than closed, because `nc` sending EOF half-closes
# the connection and the shell then answers nothing at all.
set -u
DIR="$(cd "$(dirname "$0")" && pwd)"
HOST="${R1_IP:-10.25.113.209}"
CMD="$1"
WAIT="${2:-8}"
REQ="$(mktemp)"; RESP="$(mktemp)"
trap 'rm -f "$REQ" "$RESP"' EXIT
python3 "$DIR/r1ws.py" build "$HOST" "$CMD" > "$REQ"
{ cat "$REQ"; sleep "$WAIT"; } | nc "$HOST" 8080 > "$RESP" 2>/dev/null
python3 "$DIR/r1ws.py" parse "$RESP"
