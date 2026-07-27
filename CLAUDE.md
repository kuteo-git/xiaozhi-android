# CLAUDE.md — Xiaozhi Android (R1 thin client)

Onboarding cho AI. Đọc cùng `README.md` (human-facing, features/architecture) và repo server ở
`/Users/lucnguyen/Documents/git/robot-esp32/` (server + services + toàn hệ thống) — xem `README.md`
và `SETUP.md` bên đó; repo này KHÔNG có `CLAUDE.md` riêng.

## Là gì
Android voice client cho self-hosted **xiaozhi-esp32-server**. **Thin client**: máy chỉ thu audio,
phát hiện wake word, stream lên server; STT/LLM/TTS chạy hết trên server (Mac mini). Fork từ
`douo/xiaozhi-android`, viết lại theo clean-architecture, wake word (3 engine, xem bảng dưới),
nút cứng + LED.
Chạy chính trên **PHICOMM R1** (Android 5.1.1 / API 22) thay firmware gốc.

- Nhánh làm việc: **`main`**. Repo hiện chỉ có đúng 1 nhánh (`main` ↔ `origin/main`); nhánh
  `refactor/clean-architecture` doc cũ nhắc tới **không còn tồn tại** ở cả local lẫn remote.
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
| Wake | `data/voice/{Snowboy,MicroWakeWord,MaiOi}WakeWordDetector.kt` | **3 engine**, chọn bằng `wake_engine` (cần restart): `alexa` = Snowboy `alexa2.umdl` (+`ai/kitt/snowboy`), `nabu` = microWakeWord "OK Nabu" (ngưỡng compile cứng trong `libmicro_wake_word_jni.so` → KHÔNG chỉnh được), `mai_oi` = "Nabi ơi" (`mai_oi/mai_oi.tflite`, chỉnh bằng `mai_oi_threshold`). Snowboy `setStrict()` khi đang SPEAKING để TTS không tự kích. ⚠️ `wake_sensitivity` (Snowboy, cao = nhạy hơn) NGƯỢC hướng với `mai_oi_threshold` (điểm số, **thấp** = nhạy hơn). |
| AGC STT | `domain/voice/SttAgc.kt` | AGC phần mềm **CHỈ áp lúc LISTENING, TRƯỚC Opus** (kéo giọng xa lên `target`, trần `maxGain`, sàn `floor`). `gain = target/max(env,floor)`, clamp `[1,maxGain]`. Trần thật = `target/floor` (0.35/0.004 ≈ 87.5x) — maxGain > mức đó vô nghĩa. |
| Codec | `OpusEncoder/Decoder/StreamPlayer.kt` | Opus 16kHz. |
| Playback | `data/voice/OpusAudioPlayback.kt` | AudioTrack + Equalizer (`domain/voice/AudioPlayback.kt`). |
| LED | `data/voice/MsgCenterLedIndicator.kt` | LED ring QUA system service **msgcenter** (`sendMsg(4096,code,0)` reflection) — KHÔNG ghi sysfs. Không bật được cả 2 vòng đèn cùng lúc. |
| Config | `data/AppConfig.kt` (default) + `data/Settings.kt` (SharedPreferences, runtime) | Settings đổi live qua control panel; một số cần restart app (mic_source, sample rate). |
| Protocol | `protocol/WebsocketProtocol.kt` | WS tới server `ws://<mac>:8000/xiaozhi/v1/`. Connect-on-wake. |
| Media | `domain/voice/MediaSessionState.kt` + `MediaCommands.kt` | Nhạc đi CHUNG pipeline voice (không có player riêng — ExoPlayer đã gỡ). State `IDLE/DOWNLOADING/PLAYING/PAUSED/STOPPED` + queue, server đẩy xuống qua WS. |
| Logs | `domain/voice/AppLog.kt` | Ring buffer in-app cho drawer Nhật ký. **Tồn tại vì logcat trên R1 vô dụng**: driver 4 mic (UNI_4MIC) spam ngập buffer, log app bị đẩy ra sau vài giây. |
| Bản tin | `news/NewsAlarmScheduler.kt` + `NewsAlarmReceiver.kt` | Hẹn giờ đọc bản tin trên máy. Nội dung do **server** soạn (`core/news/*` bên robot-esp32); panel chỉ sửa config rồi push. |
| Debug | `domain/voice/VoiceDebugState.kt` | Snapshot `voiceState`/`awake` lộ qua `/api/state` (lý do: xem mục Logs). Panel dùng để vẽ voice orb ở header. |

## Control panel :8088 (`control/ControlServer.kt` + `assets/control.html`)
Web control on-device (NanoHTTPD) như control center của aiboxplus. Mở `http://10.25.113.209:8088`.
- ⚠️ **BẪY QUAN TRỌNG**: `serveAsset()` ưu tiên **`/sdcard/control.html`** nếu tồn tại → che asset trong APK.
  Dùng để sửa UI khỏi build. **Sau khi build lại app phải `rm /sdcard/control.html`** nếu không sẽ thấy UI cũ.
  (Đã dính bug này: build có card mới nhưng trang vẫn cũ vì file /sdcard 06-30 còn đó.)
- API (đầy đủ, khớp `ControlServer.serve()`):

  | Nhóm | Endpoint |
  |---|---|
  | Core | `GET/POST /api/state`, `/api/set?key=&value=`, `/api/say?text=`, `/api/led?state=`, `/api/restart` |
  | Mic test | `/api/mic/start[?agc=1]`, `/api/mic/stop`, `/api/mic/rec.wav` |
  | Log | `/api/logs?since=<seq>` (chỉ trả entry mới hơn `seq`), `/api/logs/clear` |
  | Bản tin | `/api/news/save` (POST body JSON), `/api/news/test` |
  | Media | `/api/media/search?q=`, `/api/media/play` (POST body), `/api/media/{pause,resume,next,stop}`, `/api/media/seek?position_s=`, `/api/media/state` |
  | Setup | `/api/setup/server?ota=`, `/api/setup/wake?engine=`, `/api/setup/llm` |
  | LLM | `/api/llm/models`, `/api/llm/test` |
  | Home Assistant | `/api/ha/test`, `/api/ha/devices` |

  `/api/state.chat[]` gồm `sender`,`text`,`time` — epoch-ms THẬT lấy từ `ConversationLog.Entry.time`
  lúc tin nhắn xảy ra, KHÔNG phải giờ client poll thấy.
- Giá trị dài (persona, danh sách nhạc, config bản tin) gửi qua **POST body**, không qua query string
  — query string có trần độ dài và **cắt âm thầm** chứ không báo lỗi.
- `serveAsset()` gửi `Cache-Control: no-store` → không cần hard-refresh sau khi đổi UI nữa.
- `volume` bị lượng tử hoá theo số nấc phần cứng (`volume_steps` trong `/api/state`, R1 = 15 → 1 nấc
  ≈ 6,7%): % gửi xuống được làm tròn tới nấc gần nhất, `/api/state` trả về nấc đang thực sự áp dụng.
  Slider chạy theo **chỉ số nấc**, không phải %, nên không đặt được giá trị máy không giữ nổi.

### control.html — cấu trúc & bẫy
1 file duy nhất (~1550 dòng, không build step, không framework). "Component" = helper JS + class CSS:
- `createDrawer({drawerId,backdropId,fabId,bodyClass,onOpen,onClose})` — dựng Chat/Log drawer, tự lo
  `bindFab` + `attachSwipeClose`. Class CSS dùng chung: `.drawer`, `.fab`.
- `activateSeg(ids, activeId)` — mọi nhóm segmented (sample rate, wake engine, filter log).
- `bindSlider(rangeId, valId, key, fmt, sendFmt)` — mọi slider có nhãn giá trị.
- ⚠️ **`.btn-ico` phải gắn `.trail` khi icon đứng SAU chữ.** Trước dùng `:last-child` — sai, vì nhãn là
  text node, không phải element, nên icon đứng TRƯỚC vẫn khớp `:last-child` và mất margin.
- ⚠️ **iOS nuốt cú chạm đầu sau swipe-close.** Safari có fast path cho trang KHÔNG đăng ký touch/pointer
  listener cấp document; gesture tự chế để lại state thừa và bị tính vào tap kế tiếp. Cách trị: 4
  listener **rỗng** ở cuối file (`touchstart/touchend/pointerdown/pointerup`, capture) — chính việc
  đăng ký mới là bản sửa, không phải nội dung handler. **Đừng xoá.** Đã loại trừ: pointer capture, blur.
- Debug trên máy không có devtools: mở `?trace=1` → overlay ghi mọi event tầng thấp. Chính nó tìm ra
  bug iOS ở trên (bật tracer thì hết lỗi → bisect ra thủ phạm).
- Polling: `/api/state` mỗi 1.5s (luôn chạy), `/api/media/state` mỗi 1s (chỉ khi ở tab Media),
  `/api/logs` mỗi 1.5s (chỉ khi drawer Log mở). Field người dùng đang gõ được **guard** khỏi bị poll
  ghi đè (`setupInit`/`newsInit`/`document.activeElement`) — bỏ guard là mất chữ đang gõ mỗi 1.5s.

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
`services/.venv/bin/python` + script gửi WS — xem `robot-esp32/services/r1_watchdog.py::_shell`.
Watchdog `com.user.robot-r1watchdog` tự `am force-stop; am start` khi app chết (~4s), mode `selfbuilt`.

## Gotchas
- Sửa code Kotlin → **phải build+cài lại**. Sửa chỉ `control.html` → có thể đẩy thẳng `/sdcard/control.html` (khỏi build) nhưng nhớ dọn sau.
- `mic_source` / sample rate / `wake_engine` đổi cần **Restart app** (AudioRecord + detector mở 1 lần lúc start).
- Đo layout panel mà không có thiết bị: Chrome headless **bỏ qua `--window-size`** (kẹt ở viewport 500px).
  Muốn ép đúng khổ thì nhúng trang vào `<iframe width=390>` trong 1 file harness rồi
  `--headless=new --allow-file-access-from-files --dump-dom`, đọc số đo từ script chèn vào trang.
- Đổi `AGC_MAX_GAIN` slider max ở HTML; server `/api/set` KHÔNG clamp → set >slider được qua curl.
- Native (Snowboy/Opus) cần NDK; build đầu chậm.

## Suggestions
- Commit trên `main`, đừng để lẫn `.idea/*` (đang bị track — cân nhắc gitignore).
- Muốn nghe hiệu ứng gain bằng tai: dùng nút **+AGC** trong Test mic, chỉnh slider rồi ghi lại.
- Nếu far-field vẫn yếu ở maxGain ~80: đòn thật là hạ `SttAgc.floor` (0.004→0.002) hoặc nâng `target`, KHÔNG phải maxGain (bị floor chặn ~87x). Đánh đổi: khuếch đại ồn nền → STT dễ bịa chữ.
