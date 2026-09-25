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

## Build + Install (đã verify release end-to-end 2026-07-28)
```bash
# BUILD
cd /Users/lucnguyen/Documents/git/xiaozhi-android
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
./gradlew :app:assembleRelease        # -> app/build/outputs/apk/release/app-release.apk (~15 MB)  <-- BẢN ĐỂ CHẠY
./gradlew :app:assembleDebug          # -> app/build/outputs/apk/debug/app-debug.apk   (~25 MB)

# INSTALL lên R1 (10.25.113.209) — KHÔNG có adb USB, dùng adb TCP 5555
ADB=~/Library/Android/sdk/platform-tools/adb
$ADB connect 10.25.113.209:5555
$ADB -s 10.25.113.209:5555 push app/build/outputs/apk/release/app-release.apk /data/local/tmp/rc.apk   # 15MB ~36s
# rồi pm install QUA SHELL 8080 (xem "R1 shell"):  pm install -r /data/local/tmp/rc.apk   -> "Success" (~30-90s)
# xong: rm -f /data/local/tmp/rc.apk  VÀ  rm -f /sdcard/control.html  (nếu không panel vẫn hiện UI cũ)
```
- **Release là bản để chạy trên máy**, không phải debug: R8 minify + xoá sạch `android.util.Log`
  (`-assumenosideeffects`), 15 MB thay vì 25 MB. Logging nằm trên hot path (audio từng frame, điểm wake).
  - Release **ký bằng debug key** (cố ý, xem `app/build.gradle.kts`) → `pm install -r` đè thẳng lên bản
    đang cài, **KHÔNG cần uninstall, KHÔNG mất settings**. Đã verify: 43 thiết bị HA + ota_url còn nguyên.
  - Drawer Nhật ký vẫn chạy trong release vì `AppLog` cố tình KHÔNG đi qua `android.util.Log`.
  - Sửa native/JNI mà thêm class → nhớ thêm keep rule vào `proguard-rules.pro` (Snowboy/Opus/microWakeWord
    đã có; R8 đổi tên class là JNI bind hụt, chỉ lộ ra lúc chạy chứ build vẫn xanh).
- ⚠️ **CẢ HAI đường adb đều hay rớt qua wifi 2.4GHz**: `adb install` (stream) treo ở "waiting for device";
  `adb shell pm install` chết giữa chừng với `error: closed`. `adb push` thì ổn. → push bằng adb, **cài
  bằng shell 8080** (độc lập kết nối adb).
- ⚠️ Shell 8080 chạy uid=**system**, không đọc được `/data/local/tmp` (SELinux): `ls`/`rm` trên
  `/data/local/tmp/rc.apk` trả `Permission denied`. **Nhưng `pm install -r` từ đúng đường dẫn đó vẫn chạy**
  (pm là service đặc quyền, tự đọc file). Đừng thấy `ls` fail mà tưởng phải đổi chỗ đặt APK. Muốn xoá APK
  tạm thì dùng `adb shell rm`, không phải shell 8080.
- `pm install -r` KILL process app → ControlServer :8088 tắt tới khi app relaunch (watchdog kéo dậy ~4s;
  đo thực tế panel trả 200 ngay lần curl đầu sau khi cài).
- Verify cài xong: `dumpsys package info.dourok.voicebot.dev | grep lastUpdateTime`.
- Verify panel đúng bản: `curl -s http://10.25.113.209:8088/ | md5` so với
  `unzip -p app-release.apk assets/control.html | md5` và file trong repo — 3 cái phải bằng nhau.
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
| Playback | `data/voice/OpusAudioPlayback.kt` | AudioTrack + `domain/voice/AudioDsp.kt` (EQ 8 dải **tự làm trên PCM**, KHÔNG dùng `android.media.audiofx.Equalizer` — xem Gotchas) + `LoudnessEnhancer` (độ to). Hai curve: giọng / nhạc, chọn theo `MediaSessionState.isMusicPlaying`. |
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
- **Âm thanh phát: EQ là của app, không phải của platform.** Đo trên máy 25/09/2026, và đây là lý
  do `android.media.audiofx.Equalizer` bị bỏ:
  - `/system/etc/audio_effects.conf` chỉ đăng ký **effect mềm AOSP** — không có vendor conf, HW
    effect proxy comment hết, `pre_processing` (AEC/NS/AGC) **không nạp** nên
    `AcousticEchoCanceler.isAvailable()` là false dù `AudioRecorder` vẫn thử bật.
    Có sẵn mà chưa từng dùng: **`loudness_enhancer`** (`libldnhncr.so`) → giờ đã dùng.
  - `DynamicsProcessing` (EQ nhiều band + compressor tử tế) cần **API 28**; máy API 22 → loại thẳng.
  - EQ của AOSP cố định **5 band 60/230/910/3600/14000 Hz** — cách nhau 2 quãng tám nên filter rất
    rộng và chồng nhau: **xin +4 dB ở 230 Hz nhận +7.3 dB**, vì +10 dB xin ở 60 Hz rò sang. Slider
    không chỉnh đúng tần số ghi dưới nó, nên không ai chỉnh bằng tai được. Sau khi đổi sang
    `AudioDsp` (8 band, 1 quãng tám, Q=1.414): xin +10 dB ở 1280 Hz **nhận +9.7 dB**, và 320 Hz
    cách 2 quãng tám chỉ **+0.3 dB**.
  - Hai band ngoài cùng của EQ cũ vô nghĩa với giọng: TTS đo được **−45 dB ở 60 Hz** và **−68 dB ở
    14 kHz** so với đỉnh của chính nó (áp curve cũ lên file TTS chỉ đẩy peak 0.796 → 0.812).
  - Loa **roll-off dưới ~100 Hz** (60 Hz nằm ở sàn ồn của mic trong khi 120 Hz vống +35 dB) → có
    high-pass bảo vệ ở `dsp_highpass_hz` (mặc định 60, 0 = tắt).
  - **Phần cứng thì CÓ cái xịn hơn và Android không với tới**: codec phát là **AK7755** (card2,
    `/proc/asound/cards`), AKM codec có DSP 32-bit nạp firmware từ
    `/system/vendor/firmware/ak7755_{pram,cram,ofreg}_data{2,3}.bin` (CRAM = hệ số filter). Driver ở
    đây **không lộ ALSA control nào** cho nó, `mixer_paths.xml` là bản Rockchip generic, hệ số phải
    dựng bằng tool của AKM, và ghi vào `/system` trên máy đã mod thì rủi ro mất tiếng hẳn mà không
    A/B được. Ghi lại là **đã cân nhắc và xếp cuối vì rủi ro**, không phải chưa biết.

### Đo âm thanh phát bằng mic của chính máy — và giới hạn của nó
`/api/mic/start` + `/api/say` + `/api/mic/rec.wav`, rồi so phổ A/B (EQ off vs on). Mic **không** bị
AEC cắt (xem trên) nên nghe rõ loa. **Null test (2 lần đều off) ra ±1.0 dB, level +0.1 dB** → lặp lại
được. Nhưng:
- **Mic 16 kHz** → Nyquist 8 kHz, band 10 kHz không đo được.
- **Mic nằm cùng thùng với loa.** S/N theo band, so với lúc im lặng: 60 Hz **+0.7 dB**, 230 Hz +19.2,
  910 Hz +4.9, 1800 Hz +15.0, 3600 Hz **+1.4 dB**, 6000 Hz +6.4. Nên **chỉ 120–2000 Hz là số thật**;
  ở 60 Hz và 3600 Hz mic chỉ nghe sàn ồn của chính nó, và mọi số đo ở đó là rác. Vùng presence
  ~2.5–5 kHz **phải nghe bằng tai** hoặc dùng mic ngoài. Dưới 200 Hz thì rung vỏ lấn (120 Hz vống
  +35 dB), nên không đo được đáp tuyến bass thật của loa bằng mic nội bộ.

### macOS chặn Local Network → `adb` và `python` không gọi được máy
Trên máy Mac này `adb connect` và socket của python trả `No route to host` tới 10.25.113.209 ở **mọi**
cổng, trong khi `curl` và `nc` (binary hệ thống trong `/usr/bin`) thì vào bình thường — python vẫn ra
được Internet và localhost, nên **không phải mạng, là quyền Local Network theo từng binary**.
- Shell 8080: dùng `tools/r1sh.sh` (WebSocket handshake + frame tự dựng, `nc` tải socket).
- `adb`: relay qua localhost — `mkfifo f; nc -l 127.0.0.1 15555 < f | nc 10.25.113.209 5555 > f`
  rồi `adb connect 127.0.0.1:15555`. Verify rồi: push 15 MB mất ~91s.

- **Máy kẹt "Đang trả lời" (panel) = `voice_awake && voice_state==SPEAKING` không bao giờ reset.**
  Đã trị 2026-09-24, xem `domain/voice/SessionEnd.kt`. Ba điều cần nhớ khi đụng lại vùng này:
  - Socket chết **chỉ lộ ra khi GHI**. Websocket của OkHttp bỏ qua `readTimeout` (SO_TIMEOUT=0), và
    lúc SPEAKING thì app không stream mic → không ghi gì → không phát hiện gì. `pingInterval(20s)`
    là cái ghi hộ; bỏ nó đi là bug quay lại. Server pong ~9ms, đã đo.
  - `onFailure` **phải** emit `AudioState.CLOSED` như `onClosed`. Trên wifi nhà, EPIPE mới là cách
    phiên chết phổ biến; chỉ emit `networkErrorFlow` là `MediaSessionState` không ai dọn.
  - `MediaSessionState` là state do **server đẩy**, sống dai hơn phiên. Đừng đọc "đang paused" thành
    "frame này là pause flush" — phải kèm mốc thời gian (`msSincePause`), không thì một bài nhạc
    pause hôm qua nuốt luôn `tts stop` của câu tạm biệt hôm nay.
- Sửa code Kotlin → **phải build+cài lại** (release, xem trên). Sửa chỉ `control.html` → đẩy thẳng
  `/sdcard/control.html` để lặp nhanh khỏi build — nhưng **chép ngược về `app/src/main/assets/` rồi
  `rm /sdcard/control.html`** trước khi build, không thì bản trên máy và bản trong repo lệch nhau âm thầm.
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
