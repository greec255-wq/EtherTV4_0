package ru.ethertv.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import ru.ethertv.client.capture.ClientCaptureService;
import ru.ethertv.client.net.BridgePayload;
import ru.ethertv.client.youtube.YoutubeBrowserService;
import ru.ethertv.client.youtube.RinkuYoutubeBrowserSurface;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public final class EtherTVClient implements ModInitializer, ClientModInitializer {
    public static final int MAGIC = 0x45545631;
    public static final int PROTOCOL = 2;
    public static final String VERSION = "1.2.0-dev";

    @Override public void onInitialize() {
        PayloadTypeRegistry.serverboundPlay().register(BridgePayload.TYPE, BridgePayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(BridgePayload.TYPE, BridgePayload.CODEC);
    }

    @Override public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(BridgePayload.TYPE, (payload, context) ->
                context.client().execute(() -> handleServerPacket(payload.data())));
        try { RinkuYoutubeBrowserSurface.install(); }
        catch (Throwable error) { net.minecraft.client.Minecraft.getInstance().execute(() -> {
            if (net.minecraft.client.Minecraft.getInstance().player != null)
                net.minecraft.client.Minecraft.getInstance().player.displayClientMessage(net.minecraft.network.chat.Component.literal("EtherTV: модуль YouTube не запущен: " + error.getMessage()), false);
        }); }
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> sendHello());
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommandManager.literal("ethertv")
                        .then(ClientCommandManager.literal("audio-consent").executes(context -> {
                            boolean enabled = ClientCaptureService.toggleGameAudioConsent();
                            context.getSource().sendFeedback(net.minecraft.network.chat.Component.literal(
                                    enabled ? "EtherTV: разрешена запись игрового аудио на время этой сессии." : "EtherTV: запись игрового аудио запрещена."));
                            return 1;
                        }))));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> { ClientCaptureService.stop(); RinkuYoutubeBrowserSurface.stopAll(); });
        WorldRenderEvents.END.register(context -> ClientCaptureService.captureFrame());
    }

    private static void sendHello() {
        send(packet(out -> { out.writeByte(1); out.writeUTF(VERSION); out.writeInt(0b0000_0111); }));
    }

    private static void handleServerPacket(byte[] bytes) {
        try (var in = new java.io.DataInputStream(new java.io.ByteArrayInputStream(bytes))) {
            if (in.readInt() != MAGIC || in.readUnsignedByte() != PROTOCOL) return;
            int type = in.readUnsignedByte();
            switch (type) {
                case 20 -> { in.readUTF(); in.readInt(); }
                case 12 -> {
                    java.util.UUID id = new java.util.UUID(in.readLong(), in.readLong());
                    String dimension = in.readUTF();
                    int x = in.readInt(), y = in.readInt(), z = in.readInt();
                    boolean gameAudio = in.readBoolean();
                    ClientCaptureService.start(id, gameAudio);
                }
                case 13 -> {
                    java.util.UUID id = new java.util.UUID(in.readLong(), in.readLong());
                    ClientCaptureService.stop(id);
                }
                case 10 -> {
                    String dimension = in.readUTF();
                    int x = in.readInt(), y = in.readInt(), z = in.readInt();
                    String videoId = in.readUTF();
                    long startedAt = in.readLong();
                    boolean captureFrames = in.readBoolean();
                    YoutubeBrowserService.setSource(dimension, x, y, z, videoId, startedAt, captureFrames);
                }
                case 11 -> {
                    String dimension = in.readUTF();
                    int x = in.readInt(), y = in.readInt(), z = in.readInt();
                    YoutubeBrowserService.stopSource(dimension, x, y, z);
                }
                case 14 -> {
                    String dimension = in.readUTF();
                    int x = in.readInt(), y = in.readInt(), z = in.readInt();
                    String action = in.readUTF();
                    double seconds = in.readDouble();
                    YoutubeBrowserService.control(dimension, x, y, z, action, seconds);
                }
                default -> { }
            }
        } catch (Exception ignored) { }
    }

    public static void send(byte[] bytes) {
        if (bytes == null || bytes.length > BridgePayload.MAX_BYTES) return;
        if (ClientPlayNetworking.canSend(BridgePayload.TYPE)) ClientPlayNetworking.send(new BridgePayload(bytes));
    }

    public static byte[] packet(PacketWriter writer) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(MAGIC); out.writeByte(PROTOCOL); writer.write(out); out.flush();
            return bytes.toByteArray();
        } catch (IOException impossible) { throw new IllegalStateException(impossible); }
    }
    @FunctionalInterface public interface PacketWriter { void write(DataOutputStream out) throws IOException; }
}
