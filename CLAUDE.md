# CLAUDE.md

See @SPEC.md for protocol details and architecture.

## What this is

Standalone LSPosed module. No UI, no activities — just hooks and IPC.
Companion to the Provider app.

Used in a speech-to-speech translation pipeline: real mic audio is forwarded to the Provider → S2S server → translated audio is injected back into AudioRecord so VoIP apps hear the translation.

## Stack

- Kotlin, minSdk 29, Gradle Kotlin DSL
- XposedBridge API (`compileOnly`)
- TCP loopback socket `127.0.0.1:38673` for IPC (no OkHttp, no Hilt)

## Package structure

```
com.xmicinject/
├── XMicHook.kt        — entry point (IXposedHookLoadPackage), hook dispatch
├── PcmRingBuffer.kt   — singleton ring buffer, stores inject audio at 16 kHz
├── IpcClient.kt       — singleton TCP connection, feeds buffer, exposes write()
├── UplinkSender.kt    — singleton, captures mic audio and sends to the Provider
└── AudioResampler.kt  — stateless resampler (pure functions)
```

## Responsibilities

Each class has one job — do not mix them:

| Class | Job |
|---|---|
| `XMicHook` | Register hooks, call inject/uplink helpers |
| `PcmRingBuffer` | Store and read PCM bytes |
| `IpcClient` | Open/maintain TCP connection, read inject, write uplink |
| `UplinkSender` | Convert mic audio to wire format and send |
| `AudioResampler` | Resample PCM between sample rates |

## Conventions

- Kotlin only
- No DI framework — singletons via `object`
- No coroutines — IPC runs on a plain daemon `Thread`
- Comments in English only

## Behavioral guidelines

- Touch only what the task requires
- Resampling logic belongs in `AudioResampler` only — not in hook or buffer
- Wire format is always 16 kHz mono PCM16 LE — both inject and uplink
- TCP port `38673` and host `127.0.0.1` are the IPC contract with the Provider — do not change without updating both sides
