# EtherTV Client bridge protocol v1

Channel: `ethertv:bridge` (Paper plugin message / Fabric custom payload)

Every inner packet starts with:

| Field | Type | Value |
|---|---:|---|
| Magic | int32 big-endian | `0x45545631` (`ETV1`) |
| Protocol | uint8 | `1` |
| Type | uint8 | packet-specific |

## Client -> server

- `1 HELLO`: UTF client version, int32 capability flags.
- `2 FRAME`: recording UUID (two int64), int32 sequence, int32 JPEG length, JPEG bytes. Maximum 400,000 bytes.
- `3 GAME_AUDIO`: recording UUID, int32 sequence, int32 PCM length, mono 48 kHz signed 16-bit little-endian PCM. Maximum 48,000 bytes.

## Server -> client

- `20 WELCOME`: UTF server message, int32 negotiated flags.
- `10 YOUTUBE_SET`: UTF dimension key, int32 x/y/z, UTF 11-character YouTube video ID, int64 server start timestamp in milliseconds.
- `11 YOUTUBE_STOP`: UTF dimension key, int32 x/y/z.
- `12 CAPTURE_START`: recording UUID, UTF dimension key, int32 camera x/y/z, boolean game-audio requested.
- `13 CAPTURE_STOP`: recording UUID.
- `14 YOUTUBE_CONTROL`: UTF dimension key, int32 x/y/z, UTF action (`play`, `pause`, `seek`), double seconds.

## Validation

Paper rejects unknown versions, oversized packets, malformed lengths and JPEG payloads without a JPEG signature. Frame/audio packets are accepted only when the recording UUID is active and the sender is the operator UUID stored in that recording. The server does not accept a client-supplied camera position as authority.

The transport is piggybacked on the player's Minecraft connection. It is not a general-purpose high-bandwidth streaming socket. The current caps and frame rate are deliberately conservative.


## Raw payload framing note

The Fabric `StreamCodec` writes the inner EtherTV bytes without an additional length prefix. The first four bytes on both directions are the `ETV1` magic, matching Paper's Bukkit plugin-message listener. The Minecraft custom-payload packet already frames the payload body; adding another integer length here breaks the Paper bridge.
