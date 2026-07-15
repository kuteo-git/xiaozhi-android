# Xiaozhi Android — R1 thin client

An Android voice-assistant client for a self-hosted [xiaozhi-esp32-server](https://github.com/78/xiaozhi-esp32),
built as a **thin client**: the device only captures audio, detects the wake word, streams to the
server and plays back the response — all speech recognition, LLM and TTS happen on the server.

It is tuned to run on the **PHICOMM R1** smart speaker (Android 5.1.1 / API 22) as a clean
replacement for its stock firmware app, but it is a standard Android app and runs on newer devices too.

> **⚠️ Requires a server.** This app is a thin client only — it does not work standalone. You need a
> running instance of the companion server, [kuteo-git/robot-esp32](https://github.com/kuteo-git/robot-esp32),
> which handles STT, LLM and TTS. Point the app at your server instance from the control panel's
> Setup tab (see [Server endpoint](#server-endpoint)) before use.

> Forked from [douo/xiaozhi-android](https://github.com/douo/xiaozhi-android). Rewritten around a
> clean-architecture core, with a selectable wake word ("Alexa" / "OK Nabu" / a custom "Na Bi ơi"), hardware-button
> control, LED feedback, and an on-device web control panel for setup.

https://github.com/user-attachments/assets/1ee53869-1987-4e1f-a64a-26c7c0ec032f

## Features

- **Wake word** — on-device detection, selectable between three engines: [Snowboy](https://github.com/Kitt-AI/snowboy)
  "Alexa" (`alexa2.umdl`), [microWakeWord](https://github.com/kahrendt/microWakeWord) "OK Nabu", and a
  custom microWakeWord model **"Na Bi ơi"** (`assets/mai_oi/mai_oi.tflite`, trained under
  `services/wakeword_training` in the server repo). Alexa exposes an adjustable sensitivity (plus a
  stricter one while speaking); "Na Bi ơi" exposes a score threshold; "OK Nabu" has a fixed cutoff
  compiled into its `.so` and is not tunable.
- **Connect-on-wake** — no server connection until the wake word fires (avoids idle timeouts).
- **Continuous conversation** — stays in the session across turns; the server ends it after silence, and
  a client-side backstop sleeps the device after several replies with no real user speech (a runaway
  false-wake guard, reset whenever the user actually speaks).
- **Interrupt** — pressing the hardware button while the assistant is speaking or playing music stops it.
  The R1 has no working acoustic echo cancellation in the app's capture path (the 4-mic array's AEC
  lives in the vendor DSP, which the app bypasses by reading the raw Android mic), so *voice* barge-in
  while audio is playing is unreliable and is disabled for the "Na Bi ơi" engine — the button is the
  reliable interrupt.
- **No self-hearing** — while the assistant's own speaker is emitting audio, the mic is not streamed to
  the STT server (a playback-gated mute), so the robot never transcribes its own TTS as a user turn.
- **Far-field mic AGC** — a software automatic-gain-control stage applied only while actively
  listening for speech (before Opus encoding), so quiet/far speech reaches the STT server at a
  usable level without amplifying idle background noise.
- **Hardware button** (R1 `KEYCODE_PHICOMM_OK`): idle → wake · awake (listening or speaking) → sleep.
- **LED feedback** — the R1 LED ring lights up in different colors for listening / speaking / music,
  via the `msgcenter` system service.
- **Boot start** — launches automatically on device boot.
- **Music** — the server streams music (e.g. YouTube via a pytube service) back as normal audio.
- **On-device web control panel** (port `8088`) — configure the server, wake engine, LLM and Home
  Assistant integration, run a live A/B mic test, tail logs and view chat history, all from a
  browser — no rebuild required. See [Control panel](#control-panel-port-8088) below.
- **Assistant persona** — an optional custom system prompt (`custom_prompt`) sent to the server on
  connect, editable from the control panel's Setup tab.
- **Home Assistant integration** — fetch/search devices from a Home Assistant instance, annotate and
  save a device list that gets sent to the server as `ha_config` so the LLM can reference them.
- **Pluggable LLM config** — server URL, API key, model and transport (OpenAI-compatible / SSE) are
  configurable at runtime and sent to the server as `llm_config`; includes a one-click connectivity
  test.

## Architecture

The voice runtime follows clean architecture so the platform/device details stay out of the logic:

```
presentation (ui/)
  ChatViewModel ──────────── builds the Protocol, starts the runtime, exposes state to Compose
  control/ControlServer ──── on-device HTTP control panel (NanoHTTPD), reads/writes Settings
        │
domain (domain/voice/)
  VoiceAssistant ─────────── the wake → listen → speak state machine (no Android dependencies)
  SttAgc ─────────────────── far-field gain-control applied only while LISTENING, before Opus
  MicTest ────────────────── A/B mic capture (raw vs. +AGC) for the control panel's mic test
  ports: WakeWordDetector · AudioCapture · AudioPlayback · SoundEffects · LedIndicator
        │
data (data/voice/)
  SnowboyWakeWordDetector · MicroWakeWordDetector · MaiOiWakeWordDetector
  RecorderAudioCapture ───── owns the single AudioRecord (mic is held exclusively, continuously)
  OpusAudioPlayback · AudioTrackSoundEffects
  MsgCenterLedIndicator ──── LED ring via the msgcenter system service (reflection, not sysfs)
        │
protocol/
  WebsocketProtocol · MqttProtocol ─ transport to the xiaozhi server (ws://<server>:8000/xiaozhi/v1/)
        │
data/
  Settings (SharedPreferences, live/runtime) · AppConfig (compile-time defaults)
```

- **domain** depends on nothing Android-specific; the ports are plain interfaces.
- **data** implements the ports over the device infrastructure (snowboy/microWakeWord, `AudioRecorder`,
  Opus codec, `AudioTrack`, the `msgcenter` LED service).
- **presentation** is a thin `ViewModel` that wires a `Protocol` (WebSocket/MQTT) into the
  `VoiceAssistant` and surfaces its state flows; `ControlServer` is a parallel, independent entry
  point that reads and writes the same `Settings`.
- Dependencies are wired with Hilt (`data/voice/VoiceModule.kt`).

## Build

Requirements: JDK 17, Android SDK (platform 35, build-tools 35), NDK 27, CMake 3.22.1, `minSdk 22`.

```bash
JAVA_HOME=/path/to/jdk-17 ANDROID_HOME=~/Library/Android/sdk ./gradlew assembleRelease
# debug build:
JAVA_HOME=/path/to/jdk-17 ANDROID_HOME=~/Library/Android/sdk ./gradlew assembleDebug
```

The release build uses R8 minification; JNI classes (Opus, snowboy, microWakeWord) are kept via
`app/proguard-rules.pro`. Native libraries (Opus, Snowboy JNI) need the NDK, so the first build is
noticeably slower than incremental ones.

Debug builds use `applicationId` with a `.dev` suffix so they can be installed **side by side** with
a build that uses the base `applicationId`, without conflicting.

## Installing on the device

### Standard `adb install`

If the device is reachable over USB or a stable network, a normal install works:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### R1 over Wi-Fi (no USB) — push + `pm install`

The R1 has no USB debugging cable in normal operation, so ADB is connected over TCP
(`adb connect <device-ip>:5555`). Streaming `adb install` directly over 2.4 GHz Wi-Fi can drop mid-transfer
and hang on "waiting for device" indefinitely. The reliable path is to push the APK to local storage,
then install it from a shell:

```bash
adb connect <device-ip>:5555
adb -s <device-ip>:5555 push app/build/outputs/apk/debug/app-debug.apk /data/local/tmp/rc.apk
adb -s <device-ip>:5555 shell pm install -r /data/local/tmp/rc.apk
adb -s <device-ip>:5555 shell rm -f /data/local/tmp/rc.apk
```

- `pm install -r` kills the running app process; the control panel (port 8088) is unreachable until
  the app relaunches (a watchdog process or `am start` brings it back).
- Verify the install: `adb shell dumpsys package <applicationId> | grep lastUpdateTime`.
- `INSTALL_FAILED_UPDATE_INCOMPATIBLE` (e.g. after regenerating the debug keystore) requires
  `adb shell pm uninstall <applicationId>` first — this wipes the app's `SharedPreferences`, so every
  control-panel setting reverts to its default afterward.

### Server endpoint

The app has no deployment-specific endpoint baked in. On first run, use the control panel's Setup
tab (or the OTA URL) to point the app at your `xiaozhi-esp32-server` instance; it derives the
WebSocket URL (and, if applicable, an MQTT gateway) from the OTA response.

## Control panel (port 8088)

The app runs a small on-device HTTP server (`control/ControlServer.kt`, NanoHTTPD) that serves a
single-page control panel (`assets/control.html`) for configuring and debugging the client without
rebuilding it. Open `http://<device-ip>:8088` from any browser on the same network.

### What you can do from it

- **Setup tab** — server/OTA URL; a single **Wake word** card that groups the engine selector
  (Alexa / OK Nabu / Na Bi ơi) with the selected engine's own control (Alexa sensitivity, "Na Bi ơi"
  threshold, or a note that "OK Nabu" is not tunable); LLM provider/model/API key with a connectivity
  test; Home Assistant URL/token with device fetch + search + annotate; the Assistant persona (custom
  system prompt); and **Restart app** as the last card. All live-editable, some require an app restart
  to take effect (notably mic source and sample rate, since `AudioRecord` is opened once at start).
- **Mic test (A/B)** — since the wake-word detector holds the microphone exclusively, the test taps
  into the same audio loop instead of opening a second `AudioRecord`. Two modes:
  - **Raw** (`agc=0`): buffers the mic signal *before* any gain processing — hear the mic as-is.
  - **+AGC** (`agc=1`): runs a separate `SttAgc` instance over a copy of the buffer using the
    currently configured target/max-gain, so you can preview far-field gain settings live, even
    while the app is idle. Produces a downloadable 16 kHz mono WAV (auto-stops after 30s).
- **LED control** — trigger LED states directly for testing.
- **Chat log** — recent conversation turns with real timestamps (from when the message actually
  happened, not from when the browser polled for it).
- **Restart** — restart the app process from the panel (the last card in the Setup tab).

### HTTP API

| Endpoint | Method | Purpose |
|---|---|---|
| `/api/state` | GET | Full JSON snapshot of settings + recent chat log |
| `/api/set?key=&value=` | GET/POST | Set a single setting by key |
| `/api/say?text=` | GET | Speak arbitrary text via TTS |
| `/api/led?state=` | GET | Force an LED state |
| `/api/restart` | GET | Restart the app |
| `/api/mic/start[?agc=1]` | GET | Start the A/B mic test (raw or +AGC) |
| `/api/mic/stop` | GET | Stop the mic test |
| `/api/mic/rec.wav` | GET | Download the last mic-test recording |
| `/api/setup/server` | GET/POST | Server/OTA setup helper |
| `/api/llm/models` | GET | List models for the configured LLM provider |
| `/api/llm/test` | GET/POST | Test the configured LLM connection |
| `/api/ha/devices` | GET/POST | Fetch/search Home Assistant devices |
| `/api/ha/test` | GET | Test the Home Assistant connection |

Secrets (API keys, HA tokens) are never echoed back in full — `/api/state` reports only whether a
value `*_set` is present, and the panel masks them in the UI.

### Gotchas

- **`/sdcard/control.html` shadows the bundled UI.** `serveAsset()` checks for this file first, so
  it can be used to iterate on the UI without rebuilding the app — but if you forget to remove it
  after building a new APK, the control panel will keep showing the *old* UI even though the new
  one is bundled inside. Always `rm /sdcard/control.html` after a build that changes `control.html`.
- The browser aggressively caches the panel's HTML/JS — hard-refresh after any UI change.
- Settings changed via `/api/set` are **not clamped** server-side even if the corresponding slider in
  the UI has a max — a value outside the slider's range can still be set directly through the API.

## Settings reference

All runtime-configurable settings live in `data/Settings.kt`, backed by `SharedPreferences`, and are
readable/writable through the control panel or its HTTP API.

| Category | Keys |
|---|---|
| Wake word | `wakeEngine`, `wakeSensitivity`, `wakeSensitivitySpeaking`, `maiOiThreshold`, `maiOiThresholdSpeaking` |
| Mic / AGC | `micSource`, `micGain`, `agcEnabled`, `agcTarget`, `agcMaxGain` |
| LED | `ledIdle`, `ledListening`, `ledSpeaking`, `ledMusic` |
| Audio playback | `volume`, `eqEnabled`, `eqBands`, `playbackSampleRate` |
| Server / transport | `otaUrl`, `wsUrl`, `wsToken` |
| LLM | `llmProvider`, `llmBaseUrl`, `llmApiKey`, `llmModel`, `llmTransport` |
| Home Assistant | `haUrl`, `haToken`, `haDevices` |
| Assistant persona | `customPrompt` |

## Notes for Android 5.1.1 (R1)

- `AudioTrack.Builder` is API 23+, so the legacy `AudioTrack` constructor is used.
- `pm install` is slow (dex2oat) — install in the background and poll for completion.
- Java/Kotlin crashes are written to `/sdcard/voicebot-crash.log`.

## Native libraries

Reused from the stock R1 app (placed under `app/src/main/jniLibs/`):

- `libsnowboy-detect-android.so` + `assets/snowboy/{alexa2.umdl,common.res}` — "Alexa" wake word.
- `libmicro_wake_word_jni.so` — "OK Nabu" wake word (microWakeWord). Selectable at runtime from the
  control panel's Setup tab.

## Credits

- Upstream client: [douo/xiaozhi-android](https://github.com/douo/xiaozhi-android)
- Server: [kuteo-git/robot-esp32](https://github.com/kuteo-git/robot-esp32) (self-hosted, based on
  [xiaozhi-esp32](https://github.com/78/xiaozhi-esp32) / xiaozhi-esp32-server)
- Wake word: [snowboy](https://github.com/Kitt-AI/snowboy) · [microWakeWord](https://github.com/kahrendt/microWakeWord)
