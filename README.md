# XMicInject

LSPosed module for one narrow test mode: when Telegram reads microphone audio, the module captures that PCM, writes it to a WAV file, and mutes the mic buffer returned to Telegram.

This is a temporary debug architecture. There is no provider app, no TCP socket, and no audio injection path. The goal is to validate the capture stage in isolation before rebuilding IPC.

## Current behavior

- Hooks `AudioRecord.read()` only inside `org.telegram.messenger`
- Watches mic-like sources: `MIC`, `VOICE_COMMUNICATION`, `VOICE_RECOGNITION`, `UNPROCESSED`
- Chooses one active `AudioRecord` stream by RMS and source priority
- Converts captured audio to `16 kHz`, `mono`, `PCM16`
- Writes the result to a WAV file
- Zeroes the mic buffer before Telegram receives it

## Capture file location

Files are written inside Telegram app storage:

```text
/data/user/0/org.telegram.messenger/files/xmicinject-captures/
```

Each session creates a file like:

```text
20260425_190312_481_uplink.wav
```

## Installation

1. Build the APK:
   ```bash
   ./gradlew assembleDebug
   ```
2. Install it:
   ```bash
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```
3. Enable the module in LSPosed for Telegram.
4. Reboot or perform a soft reboot from LSPosed.

## Usage

1. Start a Telegram call.
2. Speak into the microphone.
3. End the call.
4. Pull the WAV file from Telegram app storage and inspect it.

Useful log tags:

- `XMicHook`
- `XMicUplink`
- `XMicCaptureFile`

## Project structure

```text
app/src/main/java/com/xmicinject/
├── XMicHook.kt          - registers AudioRecord hooks for Telegram
├── UplinkSender.kt      - selects one active mic stream and normalizes PCM
├── CaptureFileWriter.kt - writes 16 kHz mono PCM16 WAV files
└── AudioResampler.kt    - PCM16 resampling helpers
```
