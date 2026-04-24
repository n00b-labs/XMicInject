# XMicInject — Technical Spec

**the Provider** — any application that implements the IPC protocol described in this document to supply and/or receive audio from XMicInject.

## What it does

LSPosed module that hooks `AudioRecord.read()` system-wide. On each call:
1. Captures the real mic audio and forwards it to the Provider (uplink).
2. Replaces the buffer content with translated audio from the Provider (inject).

The VoIP app reads from `AudioRecord` thinking it's getting real mic audio — instead it gets the translated voice returned by the S2S server.

## Hook

### Target methods

```
android.media.AudioRecord.read(byte[],  int, int)      → int
android.media.AudioRecord.read(byte[],  int, int, int) → int
android.media.AudioRecord.read(short[], int, int)      → int
android.media.AudioRecord.read(short[], int, int, int) → int
android.media.AudioRecord.read(ByteBuffer, int)        → int
android.media.AudioRecord.read(ByteBuffer, int, int)   → int
```

All overloads are hooked via `afterHookedMethod`. The hook fires after Android fills the buffer with real mic audio, then:
- Sends the real audio to the Provider (uplink).
- Overwrites the buffer with audio from `PcmRingBuffer` (inject).

### Scope

All packages except `com.xmicinject`, `android`, and system UIDs below `FIRST_APPLICATION_UID`.

### Passthrough

If `PcmRingBuffer` is inactive (the Provider not connected) or has insufficient data for the requested read size, the original mic audio is not replaced. If the Provider is connected but the buffer is empty (`muteRealMic = true`), the buffer is filled with silence instead.

### Sample rate handling

`PcmRingBuffer` always stores audio at `16 000 Hz`. If the target app's `AudioRecord` uses a different sample rate (e.g. 48 000 Hz), `AudioResampler` resamples on both paths:
- **Inject**: buffer bytes are upsampled from 16 kHz to the app's rate before writing into the app's buffer.
- **Uplink**: mic bytes are downsampled from the app's rate to 16 kHz before sending to the Provider.

## IPC Protocol

### Transport

```
Address   : 127.0.0.1:38673 (TCP loopback)
Direction : bidirectional — single connection, full-duplex
Framing   : none — raw byte streams in both directions
```

The provider app listens; XMicInject connects. XMicInject reconnects every 1 s on failure.

### Connection lifecycle

```
Provider starts listening on :38673
  ↓
XMicInject connects
  → muteRealMic = true  (real mic is now silenced in all hooked apps)
  → inject stream starts flowing provider → module
  → uplink stream starts flowing module → provider
  ↓
Provider closes connection (or network error)
  → PcmRingBuffer is cleared
  → muteRealMic = false (real mic passes through again)
  → XMicInject waits 1 s and reconnects
```

### Audio format (both streams)

```
Sample rate : 16 000 Hz
Channels    : mono
Bit depth   : 16-bit signed integer
Byte order  : little-endian  (low byte first)
```

One sample = 2 bytes. One second of audio = 32 000 bytes.

Sample encoding:

```
byte 0 : low byte  of sample  (bits 0–7)
byte 1 : high byte of sample  (bits 8–15)

value range : -32768 … 32767
silence     : 0x00 0x00
```

### Inject stream (provider → XMicInject)

Provider writes translated PCM continuously. No headers, no length prefixes, no timestamps — just raw sample bytes in order.

```
[sample 0 low][sample 0 high][sample 1 low][sample 1 high] ...
```

XMicInject reads in 4096-byte chunks (128 ms at 16 kHz) and stores in `PcmRingBuffer`. The buffer holds up to 4 seconds; older data is overwritten if the app reads slower than the provider writes.

### Uplink stream (XMicInject → provider)

XMicInject writes real mic audio in the same format: 16 kHz, mono, PCM16 LE. Same raw byte layout, no framing.

Before sending, XMicInject:
1. Converts multi-channel audio to mono (channel average).
2. Resamples from the app's native rate to 16 kHz if needed.

The provider receives a continuous stream of the user's voice ready for the S2S server.

### Provider contract

```
1. Listen on 127.0.0.1:38673 (TCP)
2. Accept one connection from XMicInject (reconnects every 1 s on failure)
3. Write inject audio: 16 kHz mono PCM16 LE, continuously, no framing
4. Read uplink audio from the same socket: same format
5. Close the connection to stop injection
```

No handshake is performed. Injection begins as soon as the connection is established and the provider starts writing.

### Provider implementation guide

The connection is full-duplex: inject and uplink flow simultaneously on the same socket.
The provider must handle both directions concurrently — use two threads (or async I/O).

**Thread 1 — inject writer:**

Write translated PCM at real-time rate: 32 000 bytes/second = ~4 096 bytes every 128 ms.

- If translated audio is ready → write it.
- If not ready (S2S server is still processing) → write silence: `0x00` bytes at the same rate.

Sending silence is required. If the provider writes nothing, XMicInject starves and the VoIP app hears silence anyway (muteRealMic is true while connected), but the ring buffer stays empty causing erratic passthrough on reconnect.

**Thread 2 — uplink reader:**

Read raw bytes from the socket continuously. Each chunk is a fragment of the user's voice, 16 kHz mono PCM16 LE, already converted from the app's native format. Chunk size varies (depends on AudioRecord buffer size of the target app — typically 1 920–8 192 bytes).

Pass chunks to the S2S pipeline as they arrive. Do not buffer more than necessary — latency compounds.

**Pseudocode:**

```
server = TCPServer("127.0.0.1", 38673)
conn = server.accept()

# Thread 1
def inject_loop():
    while connected:
        pcm = get_next_translated_chunk()   # or silence if not ready
        conn.write(pcm)
        sleep_until_next_chunk()            # pace to real-time (32 000 B/s)

# Thread 2
def uplink_loop():
    while connected:
        data = conn.read(4096)
        if not data: break
        s2s_pipeline.send(data)             # forward to speech-to-speech server
```

## Components

### PcmRingBuffer

- Capacity: 128 000 bytes (4 seconds at 16 kHz mono PCM16)
- Thread-safe via `synchronized`
- `active` flag (`AtomicBoolean`) — set `true` on first write, `false` on disconnect
- If buffer has fewer bytes than the requested read size → passthrough (no partial fill)

### IpcClient

- Daemon thread (`xmicinject-ipc`), auto-reconnects every 1 s on disconnect
- Reads inject audio in 4096-byte chunks → `PcmRingBuffer.write()`
- Exposes `write(data)` for uplink bytes
- Sets `muteRealMic = true` while connected, `false` on disconnect
- On disconnect: clears `PcmRingBuffer`, resets `UplinkSender`

### UplinkSender

- Receives raw mic bytes from each hook call
- Converts multi-channel to mono (channel average)
- Resamples to 16 kHz if needed
- Stream selection: tracks one active `AudioRecord` instance at a time (by RMS signal); switches when the active stream goes silent for 1.2 s

### AudioResampler

- Linear interpolation (lerp) resampler for PCM16 mono
- Pure functions, no state
- Used by both inject path (in `XMicHook`) and uplink path (in `UplinkSender`)

## Common problems

| Symptom | Likely cause | Where to fix |
|---|---|---|
| Audio is sped up or slowed down | S2S server returns audio at a rate other than 16 kHz; the Provider pipes it through without resampling | the Provider: resample to 16 kHz before writing to socket |
| Crackling / audio artifacts | the Provider writes inject audio in bursts instead of at a steady 32 000 B/s rate | the Provider: pace writes to real-time rate |
| Silence instead of translation | the Provider is not writing inject audio, or writing too infrequently causing buffer underruns | the Provider: write silence (0x00) when translated audio is not yet ready |
| Translation arrives with large delay | S2S server processing latency — the ring buffer cannot help here | S2S server / model selection |
| Counterpart hears echo | Uplink audio is looping back through the S2S pipeline | the Provider: do not re-inject uplink audio back into the inject stream |
| Injection not active (no logcat line) | Module not enabled in LSPosed scope for the target app | LSPosed Manager: add the target app to module scope and reboot |
| Audio passes through unmodified | the Provider not running or not listening on 127.0.0.1:38673 | Start the Provider before opening the VoIP app |

## Known limitations

- **Latency**: translated audio arrives with S2S server delay. The ring buffer absorbs jitter but cannot reduce the base latency.
- **Resampling quality**: linear interpolation introduces some distortion, especially when upsampling from 16 kHz to 48 kHz. Sufficient for voice; not suitable for music.
- **No per-app targeting**: all apps get the hook. Use the LSPosed module scope to restrict to specific apps.
- **SELinux**: loopback TCP may be restricted on strict-policy devices without root.
- **Audio must arrive at 16 kHz**: if the S2S server returns audio at a different rate, the Provider must resample before sending. XMicInject trusts the wire format.
