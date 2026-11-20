# Client game-audio capture provider

The server accepts an optional mono 48 kHz signed 16-bit little-endian PCM track from the active recording operator. It writes this track to `game-audio.wav`, separately from the Simple Voice Chat microphone track `microphone.wav`.

`AudioCaptureProvider` is a Java Service Provider Interface. A client-side implementation is discovered with `ServiceLoader` and must:

- capture only the Minecraft process/game output, not arbitrary desktop audio;
- request and explain the relevant operating-system capture permission where required;
- emit bounded PCM chunks (no more than 48,000 bytes each);
- stop immediately when the server ends the recording or the player disconnects;
- close all native handles and worker threads.

The operator must also explicitly enable local game-audio consent with `/ethertv audio-consent`; this consent is session-only and resets when Minecraft closes. Turning it off stops the provider immediately. No provider is shipped in this dev source archive. Windows, Linux and macOS expose different loopback APIs, so a reliable provider needs platform-specific native backends and separate testing. A generic microphone recorder is not a substitute for the final game mix. The existing Simple Voice Chat integration remains the microphone track and still requires the operator's explicit `/ether consent`.
