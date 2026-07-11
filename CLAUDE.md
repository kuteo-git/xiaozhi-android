# CLAUDE.md — Xiaozhi Android (R1 thin client)

Onboarding cho AI. Đọc cùng `README.md` (human-facing, features/architecture) và master doc ở
`/Users/lucnguyen/Documents/git/robot ESP32/CLAUDE.md` (server + services + toàn hệ thống).

## Là gì
Android voice client cho self-hosted **xiaozhi-esp32-server**. **Thin client**: máy chỉ thu audio,
phát hiện wake word, stream lên server; STT/LLM/TTS chạy hết trên server (Mac mini). Fork từ
`douo/xiaozhi-android`, viết lại theo clean-architecture, wake "Alexa", nút cứng + LED.
Chạy chính trên **PHICOMM R1** (Android 5.1.1 / API 22) thay firmware gốc.

- Nhánh làm việc: **`refactor/clean-architecture`** (không phải main).
- `applicationId = info.dourok.voicebot.dev` (suffix `.dev` → cài **song song** app gốc aiboxplus, không đụng package `info.dourok.voicebot`).
- Toolchain: **JDK 17** (`/opt/homebrew/opt/openjdk@17`), compileSdk 35, minSdk 22, NDK (Snowboy + Opus native).

## Build + Install (đã verify 2026-07-02)
```bash
# BUILD
cd /Users/lucnguyen/Documents/git/xiaozhi-android
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
./gradlew :app:assembleDebug          # -> app/build/outputs/apk/debug/app-debug.apk (~25 MB)

# INSTALL lên R1 (10.25.113.209) — KHÔNG có adb USB, dùng adb TCP 5555
ADB=~/Library/Android/sdk/platform-tools/adb
$ADB connect 10.25.113.209:5555
# ⚠️ 'adb install' streamed HAY RỚT qua wifi 2.4GHz -> "waiting for device" loop vô tận.
# Cách BỀN: push rồi pm install QUA SHELL 8080 (độc lập kết nối adb):
$ADB -s 10.25.113.209:5555 push app/build/outputs/apk/debug/app-debug.apk /data/local/tmp/rc.apk   # ~60s
# rồi qua shell 8080 (xem "R1 shell" bên dưới): pm install -r /data/local/tmp/rc.apk   (~30-90s -> "Success")
# pm install -r KILL process app -> ControlServer :8088 tắt tới khi app relaunch (watchdog cứu, hoặc am start).
# xong: rm -f /data/local/tmp/rc.apk
```
- Verify cài xong: `dumpsys package info.dourok.voicebot.dev | grep lastUpdateTime`.
- INSTALL_FAILED_UPDATE_INCOMPATIBLE (đổi debug keystore) → `pm uninstall info.dourok.voicebot.dev` trước (mất SharedPreferences = mọi setting panel về default).

## Kiến trúc (clean architecture)
`domain/` = logic thuần (không Android); `data/` = impl platform; `ui/` = Compose; `protocol/` = WS/MQTT.

| Vùng | File chính | Vai trò |
|---|---|---|
| Runtime | `domain/voice/VoiceAssistant.kt` | Vòng đời **wake→listen→speak**. `runAudioLoop()` (dòng ~90) collect 1 flow mic DUY NHẤT, rẽ nhánh theo state. |
| Capture | `AudioRecorder.kt` + `data/voice/RecorderAudioCapture.kt` | 1 `AudioRecord` (16kHz mono PCM16) **giữ mic độc quyền liên tục** (bật AEC+NoiseSuppressor+AGC phần cứng theo AudioSource). |
| Wake | `data/voice/SnowboyWakeWordDetector.kt` (+ `ai/kitt/snowboy`) | Snowboy `alexa2.umdl`. `setStrict()` khi đang SPEAKING để TTS không tự kích. |
| AGC STT | `domain/voice/SttAgc.kt` | AGC phần mềm **CHỈ áp lúc LISTENING, TRƯỚC Opus** (kéo giọng xa lên `target`, trần `maxGain`, sàn `floor`). `gain = target/max(env,floor)`, clamp `[1,maxGain]`. Trần thật = `target/floor` (0.35/0.004 ≈ 87.5x) — maxGain > mức đó vô nghĩa. |
| Codec | `OpusEncoder/Decoder/StreamPlayer.kt` | Opus 16kHz. |
| Playback | `data/voice/OpusAudioPlayback.kt` | AudioTrack + Equalizer (`domain/voice/AudioPlayback.kt`). |
| LED | `data/voice/MsgCenterLedIndicator.kt` | LED ring QUA system service **msgcenter** (`sendMsg(4096,code,0)` reflection) — KHÔNG ghi sysfs. Không bật được cả 2 vòng đèn cùng lúc. |
| Config | `data/AppConfig.kt` (default) + `data/Settings.kt` (SharedPreferences, runtime) | Settings đổi live qua control panel; một số cần restart app (mic_source, sample rate). |
| Protocol | `protocol/WebsocketProtocol.kt` | WS tới server `ws://<mac>:8000/xiaozhi/v1/`. Connect-on-wake. |

## Control panel :8088 (`control/ControlServer.kt` + `assets/control.html`)
Web control on-device (NanoHTTPD) như control center của aiboxplus. Mở `http://10.25.113.209:8088`.
- ⚠️ **BẪY QUAN TRỌNG**: `serveAsset()` ưu tiên **`/sdcard/control.html`** nếu tồn tại → che asset trong APK.
  Dùng để sửa UI khỏi build. **Sau khi build lại app phải `rm /sdcard/control.html`** nếu không sẽ thấy UI cũ.
  (Đã dính bug này: build có card mới nhưng trang vẫn cũ vì file /sdcard 06-30 còn đó.)
- API: `GET/POST /api/state` (JSON toàn bộ settings + `chat[]` gồm `sender`,`text`,`time` — epoch-ms
  THẬT lấy từ `ConversationLog.Entry.time` lúc tin nhắn xảy ra, KHÔNG phải giờ client poll thấy),
  `/api/set?key=&value=`, `/api/say?text=`,
  `/api/led?state=`, `/api/restart`, và **mic test**: `/api/mic/start[?agc=1]`, `/api/mic/stop`, `/api/mic/rec.wav`.
- Trình duyệt cache HTML → sau đổi UI phải **hard-refresh**.

### Test mic (A/B) — `domain/voice/MicTest.kt`
Mic bị wake-detect giữ độc quyền → **KHÔNG mở AudioRecord thứ 2**. Cách làm: `VoiceAssistant.runAudioLoop`
bơm mỗi frame vào `MicTest.feed()` khi đang recording (tap ở ĐẦU loop = raw). 2 chế độ:
- **Thô** (`agc=0`): buffer PCM raw (trước SttAgc) → nghe mic thật.
- **+AGC** (`agc=1`): `MicTest` chạy 1 `SttAgc` RIÊNG (target/maxGain từ Settings hiện tại) trên bản COPY
  (không mutate buffer của loop), hoạt động cả khi app idle (luồng STT thật chỉ áp AGC lúc LISTENING).
Xuất WAV 16kHz mono, trần 30s tự dừng. Verify: raw peak ~0.01 ambient, +AGC peak chạm đúng target 0.35.

## R1 shell (cổng 8080) — chạy lệnh trên máy
WebSocket subprotocol `v1`, uid=**system**. Gửi `{"type":"shell","type_id":"myshell","shell":"<cmd>"}`,
nhận frames `{"data":...}`. Độc lập app (sống cả khi app crash). **Reboot máy bị chặn** (SELinux), nhưng
`am`/`pm` chạy được. R1 KHÔNG có wget/curl/busybox/toybox — chỉ `/system/bin/pm`. Helper mẫu:
`services/.venv/bin/python` + script gửi WS (xem master CLAUDE.md hoặc `services/r1_watchdog.py::_shell`).
Watchdog `com.user.robot-r1watchdog` tự `am force-stop; am start` khi app chết (~4s), mode `selfbuilt`.

## Gotchas
- Sửa code Kotlin → **phải build+cài lại**. Sửa chỉ `control.html` → có thể đẩy thẳng `/sdcard/control.html` (khỏi build) nhưng nhớ dọn sau.
- `mic_source` / sample rate đổi cần **Restart app** (AudioRecord mở 1 lần lúc start).
- Đổi `AGC_MAX_GAIN` slider max ở HTML; server `/api/set` KHÔNG clamp → set >slider được qua curl.
- Native (Snowboy/Opus) cần NDK; build đầu chậm.

## Suggestions
- Commit trên nhánh `refactor/clean-architecture`, đừng để lẫn `.idea/*` (đang bị track — cân nhắc gitignore).
- Muốn nghe hiệu ứng gain bằng tai: dùng nút **+AGC** trong Test mic, chỉnh slider rồi ghi lại.
- Nếu far-field vẫn yếu ở maxGain ~80: đòn thật là hạ `SttAgc.floor` (0.004→0.002) hoặc nâng `target`, KHÔNG phải maxGain (bị floor chặn ~87x). Đánh đổi: khuếch đại ồn nền → STT dễ bịa chữ.
