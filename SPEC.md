# XMicInject — Technical Spec

## What it does

LSPosed module that hooks `AudioRecord.read()` system-wide and replaces the microphone data with PCM received from a provider app over an abstract Unix domain socket.

Any VoIP app (Telegram, Discord, system dialer) reads from `AudioRecord` thinking it's getting real mic audio — instead it gets whatever the provider sends.

## Hook

### Target method

```
android.media.AudioRecord.read(byte[], int, int) → int
android.media.AudioRecord.read(short[], int, int) → int
```

Both overloads are hooked via `afterHookedMethod`. The hook fires after the real read fills the buffer, then overwrites the buffer content with PCM from `PcmRingBuffer`.

### Scope

All packages except `com.xmicinject` itself. No package filtering — works universally.

### Passthrough

If `PcmRingBuffer` is inactive (no provider connected) or has insufficient data, the original mic audio passes through unmodified.

## IPC Protocol

### Transport

Abstract Unix domain socket — no filesystem entry, accessible by any process.

```
Socket name : voimacher_mic   (abstract namespace, prefix \0)
Direction   : provider → module (write-only stream)
Format      : raw PCM bytes, no framing
```

### Audio format expected

```
Sample rate : 16 000 Hz
Channels    : mono
Encoding    : PCM 16-bit signed little-endian
```

The provider is responsible for sending audio in this format. The module does not resample.

## PcmRingBuffer

- Capacity: 128 000 bytes (4 seconds at 16 kHz mono PCM16)
- Thread-safe via `synchronized`
- `active` flag (`AtomicBoolean`) — set `true` on first write, `false` on disconnect
- If buffer has fewer bytes than requested read size → passthrough (no partial fill)

## SocketClient

- Daemon thread, auto-reconnects every 1 s on disconnect
- Reads in 4096-byte chunks, writes directly to `PcmRingBuffer`
- On disconnect: calls `PcmRingBuffer.clear()` → passthrough mode until provider reconnects

## Provider contract

Any app that wants to inject audio must:
1. Create a `LocalServerSocket("voimacher_mic")` with abstract namespace
2. Accept connections from this module
3. Write raw PCM bytes (16 kHz, mono, PCM16 LE) continuously

Reference implementation: `MicIpcServer` in the Voimacher app (`d:\Projects\voimacher`).

## Known limitations

- No resampling — provider must send 16 kHz PCM16. If the target app's `AudioRecord` uses a different sample rate, audio will be pitched.
- No per-app targeting — all apps get the hook. Use LSPosed scope to restrict if needed.
- Abstract sockets may be blocked by SELinux on non-rooted or strict-policy devices.
