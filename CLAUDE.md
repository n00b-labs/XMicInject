# CLAUDE.md

See @SPEC.md for protocol details and architecture.

## What this is

Standalone LSPosed module. No UI, no activities — just hooks and IPC.
Companion to the Voimacher app (`d:\Projects\voimacher`).

## Stack

- Kotlin, minSdk 29, Gradle Kotlin DSL
- XposedBridge API (`compileOnly`)
- `android.net.LocalSocket` for IPC (no OkHttp, no Hilt)

## Package structure

```
com.xmicinject/
├── XMicHook.kt       — entry point (IXposedHookLoadPackage)
├── PcmRingBuffer.kt  — singleton ring buffer
└── SocketClient.kt   — singleton daemon thread
```

## Conventions

- Kotlin only
- No DI framework — singletons via `object`
- No coroutines — IPC runs on a plain daemon `Thread`
- Comments in English only

## Behavioral guidelines

- Touch only what the task requires
- Hook logic stays in `XMicHook`, buffer logic in `PcmRingBuffer`, transport in `SocketClient`
- Socket name `voimacher_mic` is the IPC contract with Voimacher — do not rename without updating both sides
