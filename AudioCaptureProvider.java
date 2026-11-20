package ru.ethertv.client.capture;

import java.util.function.Consumer;

/** SPI for a platform-specific final game-mix loopback implementation. PCM format: mono, 48 kHz, signed 16-bit little-endian. */
public interface AudioCaptureProvider {
    void start(Consumer<byte[]> pcmConsumer) throws Exception;
    void stop() throws Exception;
    String providerName();
}
