# XMicInject

LSPosed module that replaces microphone input system-wide with audio from a provider app.

Any VoIP app (Telegram, Discord, system dialer) reads its `AudioRecord` and receives whatever audio the provider sends over a Unix socket — instead of the real microphone.

## Requirements

- Android 10+ (minSdk 29)
- Magisk with Zygisk enabled
- LSPosed (JingMatrix fork recommended)

## How it works

```
Provider app (e.g. Voimacher)
  └─ MicIpcServer — LocalServerSocket("voimacher_mic")
       └─ writes translated PCM (16 kHz, mono, PCM16)

XMicInject (this module)
  └─ SocketClient — connects to "voimacher_mic"
       └─ PcmRingBuffer — 4-second ring buffer
            └─ XMicHook — AudioRecord.read() hook
                 └─ overwrites buffer in any VoIP app
```

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

Start a provider app that streams PCM to the abstract Unix socket `voimacher_mic`. Once connected, any app using `AudioRecord` will receive the injected audio.

Reference provider: [Voimacher](https://github.com/your-org/voimacher)

## Protocol

See [SPEC.md](SPEC.md) for the full IPC protocol and audio format spec.

## Project structure

```
app/src/main/java/com/xmicinject/
├── XMicHook.kt       — IXposedHookLoadPackage, hooks AudioRecord.read()
├── PcmRingBuffer.kt  — thread-safe ring buffer (128 000 bytes / 4 sec)
└── SocketClient.kt   — daemon thread, reads PCM from Unix socket
```
