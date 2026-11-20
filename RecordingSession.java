package ru.ethertv.camera;

import de.maxhenkel.voicechat.api.opus.OpusDecoder;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import ru.ethertv.renderer.FrameEncoder;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public final class RecordingSession {
    private static final int SAMPLE_RATE = 48_000;
    private final JavaPlugin plugin;
    private final Block camera;
    private final UUID id;
    private final UUID operator;
    private final Path directory;
    private final Path wavPath;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final Consumer<RecordingSession> onFinished;
    private final FrameEncoder frameEncoder;
    private long startedAt;
    private BukkitTask captureTask;
    private BukkitTask timeoutTask;
    private OpusDecoder decoder;
    private RandomAccessFile wav;
    private RandomAccessFile gameAudio;
    private long pcmBytes;
    private int lastClientFrameSequence = -1;
    private int lastGameAudioSequence = -1;
    private long gameAudioBytes;

    public RecordingSession(JavaPlugin plugin, Block camera, UUID id, UUID operator, Path directory, Consumer<RecordingSession> onFinished, FrameEncoder frameEncoder) {
        this.plugin = plugin;
        this.camera = camera;
        this.id = id;
        this.operator = operator;
        this.directory = directory;
        this.wavPath = directory.resolve("microphone.wav");
        this.onFinished = onFinished;
        this.frameEncoder = frameEncoder;
    }

    public void start(OpusDecoder decoder, int intervalTicks, int maxSeconds, int width, int height, double range) throws IOException {
        start(decoder, intervalTicks, maxSeconds, width, height, range, true);
    }

    public void start(OpusDecoder decoder, int intervalTicks, int maxSeconds, int width, int height, double range, boolean captureServerFrames) throws IOException {
        this.decoder = decoder;
        this.startedAt = System.currentTimeMillis();
        Files.createDirectories(directory.resolve("frames"));
        wav = new RandomAccessFile(wavPath.toFile(), "rw");
        wav.setLength(0);
        wav.write(new byte[44]); // microphone WAV header is finalized on stop.
        gameAudio = new RandomAccessFile(directory.resolve("game-audio.wav").toFile(), "rw");
        gameAudio.setLength(0);
        gameAudio.write(new byte[44]);
        Files.writeString(directory.resolve("recording.txt"),
                "id=" + id + "\noperator=" + operator + "\nstarted=" + startedAt + "\n" +
                "video=" + (captureServerFrames ? "server block-raytrace fallback" : "client framebuffer via EtherTV Client") + "\n" +
                "audio=microphone of consenting camera operator only\n" +
                "game-audio=optional client-side mix track; requires an installed capture provider\n");
        if (captureServerFrames) {
            captureTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                if (closed.get()) return;
                if (camera.getType() != org.bukkit.Material.DISPENSER) {
                    stop("camera block removed");
                    return;
                }
                try {
                    var frame = CameraRenderer.capture(camera, width, height, range);
                    long frameNo = (System.currentTimeMillis() - startedAt) / Math.max(1, intervalTicks * 50L);
                    Path framePath = directory.resolve("frames").resolve(String.format("frame-%06d.png", frameNo));
                    frameEncoder.submit(frame, framePath);
                } catch (Throwable t) {
                    plugin.getLogger().warning("Camera frame capture failed: " + t.getMessage());
                }
            }, 1L, intervalTicks);
        }
        timeoutTask = Bukkit.getScheduler().runTaskLater(plugin, () -> stop("maximum duration reached"), maxSeconds * 20L);
    }


    /** Receives client-captured final game mix as mono 48 kHz signed 16-bit PCM. */
    public synchronized void acceptGameAudioPcm(int sequence, byte[] pcm) {
        if (closed.get() || gameAudio == null || sequence <= lastGameAudioSequence || pcm == null
                || pcm.length == 0 || pcm.length > 48_000 || (pcm.length & 1) != 0) return;
        try {
            gameAudio.write(pcm);
            gameAudioBytes += pcm.length;
            lastGameAudioSequence = sequence;
        } catch (IOException exception) {
            plugin.getLogger().warning("Не удалось сохранить игровой звук EtherTV: " + exception.getMessage());
        }
    }

    public synchronized void acceptOpus(byte[] opus) {
        if (closed.get() || decoder == null || wav == null || opus == null || opus.length == 0) return;
        try {
            short[] samples = decoder.decode(opus);
            if (samples == null) return;
            byte[] pcmFrame = new byte[samples.length * 2];
            for (int i = 0; i < samples.length; i++) {
                pcmFrame[i * 2] = (byte) (samples[i] & 0xff);
                pcmFrame[i * 2 + 1] = (byte) ((samples[i] >>> 8) & 0xff);
            }
            wav.write(pcmFrame);
            pcmBytes += pcmFrame.length;
        } catch (Throwable t) {
            plugin.getLogger().warning("Microphone packet decode failed: " + t.getMessage());
        }
    }


    public synchronized void acceptClientJpeg(int sequence, byte[] jpeg) {
        if (closed.get() || sequence <= lastClientFrameSequence || jpeg == null || jpeg.length < 3 || jpeg.length > 400_000) return;
        if ((jpeg[0] & 0xff) != 0xff || (jpeg[1] & 0xff) != 0xd8 || (jpeg[2] & 0xff) != 0xff) return;
        lastClientFrameSequence = sequence;
        Path framePath = directory.resolve("frames").resolve(String.format("frame-%06d.jpg", sequence));
        frameEncoder.submitEncodedJpeg(jpeg, framePath);
    }

    public UUID getOperator() { return operator; }
    public UUID getId() { return id; }
    public Block getCamera() { return camera; }
    public boolean isClosed() { return closed.get(); }
    public Path getDirectory() { return directory; }
    public Path getWavPath() { return wavPath; }
    public long getPcmBytes() { return pcmBytes; }

    public synchronized void stop(String reason) {
        if (!closed.compareAndSet(false, true)) return;
        if (captureTask != null) captureTask.cancel();
        if (timeoutTask != null) timeoutTask.cancel();
        if (frameEncoder != null) frameEncoder.close();
        try {
            if (wav != null) {
                writeWavHeader(wav, pcmBytes);
                wav.close();
            }
            if (gameAudio != null) {
                writeWavHeader(gameAudio, gameAudioBytes);
                gameAudio.close();
            }
            Files.writeString(directory.resolve("finished.txt"), "reason=" + reason + "\nended=" + System.currentTimeMillis() + "\n");
        } catch (IOException e) {
            plugin.getLogger().severe("Could not finalize recording " + id + ": " + e.getMessage());
        } finally {
            onFinished.accept(this);
        }
    }

    private static void writeWavHeader(RandomAccessFile file, long dataLength) throws IOException {
        file.seek(0);
        file.writeBytes("RIFF"); writeIntLE(file, (int) (36 + dataLength));
        file.writeBytes("WAVEfmt "); writeIntLE(file, 16);
        writeShortLE(file, 1); writeShortLE(file, 1);
        writeIntLE(file, SAMPLE_RATE); writeIntLE(file, SAMPLE_RATE * 2);
        writeShortLE(file, 2); writeShortLE(file, 16);
        file.writeBytes("data"); writeIntLE(file, (int) dataLength);
    }
    private static void writeIntLE(RandomAccessFile f, int v) throws IOException {
        f.write(v & 255); f.write((v >>> 8) & 255); f.write((v >>> 16) & 255); f.write((v >>> 24) & 255);
    }
    private static void writeShortLE(RandomAccessFile f, int v) throws IOException { f.write(v & 255); f.write((v >>> 8) & 255); }
}
