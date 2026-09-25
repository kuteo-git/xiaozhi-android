#!/bin/bash
# Does the equalizer move the band it says it moves? Measured through the R1's own mic.
#   bash tools/eqcheck.sh            # boost 1280 Hz by +10 dB and read the neighbours
#   bash tools/eqcheck.sh 2 1000     # boost band index 2 (320 Hz) by +10 dB
#
# This is the loop that caught the fault AudioDsp was written for: the platform equalizer answered a
# +4 dB request at 230 Hz with +7.3 dB because its 60 Hz band reached that far. After the change, a
# +10 dB request at 1280 Hz measured +9.7 dB with 320 Hz -- two octaves away -- moving +0.3 dB.
#
# Null test first if you doubt the reading: run it with band gain 0 and every number should come back
# within +/-1.0 dB. Measured, it does.
#
# Loudness and the high-pass are turned off for the duration: they are off in the A run too, so
# leaving them on measures three changes as one. They are put back at the end.
set -u
DIR="$(cd "$(dirname "$0")" && pwd)"
HOST="${R1_IP:-10.25.113.209}"
# The analysis needs numpy; the same env tools/crashlog.sh uses. Plain python3 here has none.
PY="${PY:-/opt/homebrew/anaconda3/envs/xiaozhi/bin/python}"
D="$HOST:8088"
BAND="${1:-4}"
GAIN="${2:-1000}"
TEXT="M%E1%BB%99t%20hai%20ba%20b%E1%BB%91n%20n%C4%83m%20s%C3%A1u%20b%E1%BA%A3y%20t%C3%A1m%20ch%C3%ADn%20m%C6%B0%E1%BB%9Di%20m%E1%BB%99t%20hai%20ba%20b%E1%BB%91n%20n%C4%83m"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
set_() { curl -s -m 6 "http://$D/api/set?key=$1&value=$2" >/dev/null; }
get()  { curl -s -m 6 "http://$D/api/state"; }

# A song playing makes this loop lie twice over, and it did once: the recording catches the song
# instead of the test phrase, AND the curve under test is never used, because a song is played
# through eq_bands_music while this writes eq_bands. Measured that way the boosted band came back
# at -5.5 dB for a +10 dB request. A signal that is absent for two different reasons cannot tell
# them apart, so refuse rather than report.
MEDIA="$(curl -s -m 6 "http://$D/api/media/state" | python3 -c 'import json,sys
try: print(json.load(sys.stdin).get("state",""))
except Exception: print("")')"
case "$MEDIA" in
  playing|downloading)
    echo "REFUSING: a song is $MEDIA on the speaker. Stop it (panel -> Media -> stop) and re-run:"
    echo "  this loop writes eq_bands (the voice curve) and a song is played through eq_bands_music,"
    echo "  and the recording would be the song rather than the test phrase."
    exit 2;;
esac

SAVED="$(get)"
OLD_BANDS=$(printf '%s' "$SAVED" | python3 -c 'import json,sys;print(",".join(map(str,json.load(sys.stdin)["eq_bands"])))')
OLD_LOUD=$(printf '%s' "$SAVED" | python3 -c 'import json,sys;print(json.load(sys.stdin)["loudness_mb"])')
OLD_HP=$(printf '%s' "$SAVED" | python3 -c 'import json,sys;print(json.load(sys.stdin)["dsp_highpass_hz"])')
restore() { set_ eq_bands "$OLD_BANDS"; set_ loudness_mb "$OLD_LOUD"; set_ dsp_highpass_hz "$OLD_HP"; set_ eq_enabled true; }
trap 'restore; rm -rf "$OUT"' EXIT

CURVE=$(python3 -c "
n=8; b=['0']*n; b[$BAND]='$GAIN'; print(','.join(b))")
echo "curve under test: $CURVE   (was $OLD_BANDS)"
set_ loudness_mb 0; set_ dsp_highpass_hz 0; set_ eq_bands "$CURVE"

record() {
  set_ eq_enabled "$2"; sleep 1
  curl -s -m 5 "http://$D/api/mic/start?agc=0" >/dev/null
  curl -s -m 8 "http://$D/api/say?text=$TEXT" >/dev/null
  sleep 11
  curl -s -m 5 "http://$D/api/mic/stop" >/dev/null
  curl -s -m 25 "http://$D/api/mic/rec.wav" -o "$OUT/$1.wav"
}
record off false
record on true
# Two takes of the same sentence should be the same length and about the same level. When they are
# not, something else was making noise and the numbers below are about that instead.
A_SZ=$(stat -f%z "$OUT/off.wav"); B_SZ=$(stat -f%z "$OUT/on.wav")
if [ "$A_SZ" -lt 200000 ] || [ "$B_SZ" -lt 200000 ]; then
  echo "WARNING: a take is short ($A_SZ / $B_SZ bytes) -- the phrase may not have played."
fi
"$PY" "$DIR/eqspectrum.py" "$OUT/off.wav" "$OUT/on.wav"
