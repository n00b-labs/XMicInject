# XMicInject

LSPosed module that intercepts `AudioRecord.read()` system-wide and replaces microphone audio with PCM received from a provider app over TCP.

Designed for a speech-to-speech translation pipeline: the user speaks, the real mic audio is forwarded to the provider, the provider returns translated audio, and that audio is injected back so VoIP apps (Telegram, Discord, etc.) hear the translation instead of the original voice.

## Requirements

- Android 10+ (minSdk 29)
- Magisk with Zygisk enabled
- LSPosed (JingMatrix fork recommended)

## How it works

```
User speaks
  → AudioRecord.read() fires in VoIP app
    → XMicHook captures real mic audio → UplinkSender → IpcClient → [TCP] → the Provider
    → XMicHook overwrites buf with translated audio ← PcmRingBuffer ← IpcClient ← [TCP] ← the Provider
      → VoIP app hears the translation
```

The TCP connection is bidirectional over a single socket:
- **Inject stream** (the Provider → module): translated PCM written into `PcmRingBuffer`
- **Uplink stream** (module → the Provider): real mic audio forwarded so the Provider can send it to the S2S server

## Installation

1. Build the APK:
   ```
   ./gradlew assembleDebug
   ```
2. Install on device:
   ```
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```
3. In **LSPosed Manager** → Modules → enable **XMicInject** → reboot

## Usage

Start the Provider app. Once it listens on `127.0.0.1:38673`, XMicInject connects automatically and injection begins. Any app using `AudioRecord` will receive translated audio; the real mic is muted while connected.

## Protocol

See [SPEC.md](SPEC.md) for the full IPC protocol and audio format spec.

## Project structure

```
app/src/main/java/com/xmicinject/
├── XMicHook.kt        — entry point, registers AudioRecord.read() hooks
├── PcmRingBuffer.kt   — thread-safe ring buffer (stores inject audio at 16 kHz)
├── IpcClient.kt       — TCP connection to the Provider, feeds buffer, sends uplink
├── UplinkSender.kt    — captures real mic, converts to mono, resamples, sends
└── AudioResampler.kt  — PCM16 linear interpolation resampler (pure functions)
```
