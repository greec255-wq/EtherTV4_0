package ru.ethertv.client.capture;

import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;
import ru.ethertv.client.EtherTVClient;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.imageio.ImageIO;
import java.nio.ByteBuffer;

/** Captures the rendered game view at a capped rate; compression happens off-thread. */
public final class ClientCaptureService {
    private static volatile UUID recordingId;
    private static volatile int frameSequence;
    private static volatile long lastCaptureNanos;
    private static final AtomicBoolean busy = new AtomicBoolean(false);
    private static final ExecutorService encoder = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "EtherTV-Client-FrameEncoder"); t.setDaemon(true); return t;
    });
    private static volatile AudioCaptureProvider audioProvider;
    private static volatile boolean gameAudioConsent;
    private static int audioSequence;

    private ClientCaptureService() { }

    public static void start(UUID id, boolean requestGameAudio) {
        recordingId = id;
        frameSequence = 0;
        audioSequence = 0;
        lastCaptureNanos = 0L;
        if (requestGameAudio && gameAudioConsent) {
            audioProvider = java.util.ServiceLoader.load(AudioCaptureProvider.class).findFirst().orElse(null);
            if (audioProvider != null) {
                try { audioProvider.start(pcm -> sendGameAudio(id, pcm)); }
                catch (Exception ex) { audioProvider = null; }
            }
        } else if (requestGameAudio && Minecraft.getInstance().player != null) {
            Minecraft.getInstance().player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                    "EtherTV: игровой звук не записывается без согласия. Включить: /ethertv audio-consent"), false);
        }
    }

    public static boolean toggleGameAudioConsent() {
        gameAudioConsent = !gameAudioConsent;
        if (!gameAudioConsent) {
            AudioCaptureProvider provider = audioProvider;
            audioProvider = null;
            if (provider != null) try { provider.stop(); } catch (Exception ignored) { }
        }
        return gameAudioConsent;
    }

    public static void stop() {
        recordingId = null;
        AudioCaptureProvider provider = audioProvider;
        audioProvider = null;
        if (provider != null) try { provider.stop(); } catch (Exception ignored) { }
    }
    public static void stop(UUID id) { if (id.equals(recordingId)) stop(); }

    public static void captureFrame() {
        UUID active = recordingId;
        if (active == null || busy.get()) return;
        long now = System.nanoTime();
        if (now - lastCaptureNanos < 250_000_000L) return; // 4 fps cap
        lastCaptureNanos = now;
        Minecraft mc = Minecraft.getInstance();
        int width = mc.getWindow().getWidth(), height = mc.getWindow().getHeight();
        if (width < 1 || height < 1 || width > 3840 || height > 2160) return;
        if (!busy.compareAndSet(false, true)) return;
        try {
            ByteBuffer pixels = ByteBuffer.allocateDirect(width * height * 4);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
            GL11.glReadPixels(0, 0, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
            int sequence = frameSequence++;
            encoder.execute(() -> {
                try {
                    BufferedImage source = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
                    for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
                        int i = (y * width + x) * 4;
                        int r = pixels.get(i) & 0xff, g = pixels.get(i + 1) & 0xff, b = pixels.get(i + 2) & 0xff;
                        source.setRGB(x, height - 1 - y, (r << 16) | (g << 8) | b);
                    }
                    int targetW = Math.min(640, source.getWidth());
                    int targetH = Math.max(1, (int) Math.round(source.getHeight() * (targetW / (double) source.getWidth())));
                    BufferedImage scaled = new BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_RGB);
                    Graphics2D g2 = scaled.createGraphics();
                    g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    g2.drawImage(source, 0, 0, targetW, targetH, null);
                    g2.dispose(); source.flush();
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    ImageIO.write(scaled, "jpg", out); scaled.flush();
                    byte[] jpeg = out.toByteArray();
                    if (jpeg.length > 400_000) return;
                    byte[] packetBytes = EtherTVClient.packet(packet -> {
                        packet.writeByte(2);
                        packet.writeLong(active.getMostSignificantBits()); packet.writeLong(active.getLeastSignificantBits());
                        packet.writeInt(sequence); packet.writeInt(jpeg.length); packet.write(jpeg);
                    });
                    Minecraft.getInstance().execute(() -> EtherTVClient.send(packetBytes));
                } catch (Exception ignored) { }
                finally { busy.set(false); }
            });
        } catch (Throwable ignored) { busy.set(false); }
    }

    private static void sendGameAudio(UUID id, byte[] pcm) {
        if (recordingId == null || !recordingId.equals(id) || pcm == null || pcm.length == 0 || pcm.length > 48_000 || (pcm.length & 1) != 0) return;
        int sequence = audioSequence++;
        EtherTVClient.send(EtherTVClient.packet(out -> {
            out.writeByte(3); out.writeLong(id.getMostSignificantBits()); out.writeLong(id.getLeastSignificantBits());
            out.writeInt(sequence); out.writeInt(pcm.length); out.write(pcm);
        }));
    }
}
