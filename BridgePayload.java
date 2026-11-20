package ru.ethertv.client.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Single bounded payload; inner bytes use the same ETV1 framing as the Paper bridge. */
public record BridgePayload(byte[] data) implements CustomPacketPayload {
    public static final int MAX_BYTES = 512 * 1024;
    public static final Type<BridgePayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("ethertv", "bridge"));
    public static final StreamCodec<RegistryFriendlyByteBuf, BridgePayload> CODEC = new StreamCodec<>() {
        @Override public BridgePayload decode(RegistryFriendlyByteBuf buf) {
            // Bukkit plugin messages expose the custom-payload body verbatim.
            // Do not add/read a second length prefix: ETV1 must be byte zero.
            int length = buf.readableBytes();
            if (length < 6 || length > MAX_BYTES) throw new IllegalArgumentException("Invalid EtherTV payload size");
            byte[] bytes = new byte[length];
            buf.readBytes(bytes);
            return new BridgePayload(bytes);
        }
        @Override public void encode(RegistryFriendlyByteBuf buf, BridgePayload value) {
            if (value.data == null || value.data.length < 6 || value.data.length > MAX_BYTES) throw new IllegalArgumentException("EtherTV payload too large");
            buf.writeBytes(value.data);
        }
    };
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
