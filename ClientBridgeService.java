package ru.ethertv.network;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;
import ru.ethertv.EtherTVPlugin;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Bounded, versioned Paper <-> EtherTV Client bridge. */
public final class ClientBridgeService implements PluginMessageListener, AutoCloseable {
    public static final String CHANNEL = "ethertv:bridge";
    public static final int MAGIC = 0x45545631; // ETV1
    public static final int PROTOCOL = 2;
    public static final int MAX_PACKET_BYTES = 512 * 1024;
    private static final int HELLO = 1;
    private static final int FRAME = 2;
    private static final int GAME_AUDIO = 3;
    private static final int YOUTUBE_FRAME = 4;
    private static final int YOUTUBE_SET = 10;
    private static final int YOUTUBE_STOP = 11;
    private static final int CAPTURE_START = 12;
    private static final int CAPTURE_STOP = 13;
    private static final int YOUTUBE_CONTROL = 14;

    private final EtherTVPlugin plugin;
    private final Set<UUID> capableClients = ConcurrentHashMap.newKeySet();
    private final Map<String, YoutubeSource> youtubeSources = new ConcurrentHashMap<>();
    private final Map<UUID, Set<String>> deliveredSources = new ConcurrentHashMap<>();
    private final Map<String, Long> youtubeFrameTimes = new ConcurrentHashMap<>();
    private final Map<String, java.util.concurrent.atomic.AtomicLong> youtubeFrameSequences = new ConcurrentHashMap<>();
    private final Map<String, ClientFrameState> youtubeClientSequences = new ConcurrentHashMap<>();

    public ClientBridgeService(EtherTVPlugin plugin) {
        this.plugin = plugin;
        plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, CHANNEL, this);
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, CHANNEL);
        Bukkit.getPluginManager().registerEvents(new BridgeJoinListener(this), plugin);
    }

    public boolean hasClient(Player player) {
        return capableClients.contains(player.getUniqueId())
                && player.getListeningPluginChannels().contains(CHANNEL);
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!CHANNEL.equals(channel) || message == null || message.length < 6 || message.length > MAX_PACKET_BYTES) return;
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(message))) {
            if (in.readInt() != MAGIC || in.readUnsignedByte() != PROTOCOL) return;
            int type = in.readUnsignedByte();
            if (type == HELLO) {
                String clientVersion = in.readUTF();
                int capabilities = in.readInt();
                capableClients.add(player.getUniqueId());
                player.sendPluginMessage(plugin, CHANNEL, packet(out -> {
                    out.writeByte(20); // WELCOME
                    out.writeUTF("EtherTV server connected; client=" + clientVersion);
                    out.writeInt(capabilities);
                }));
                plugin.getLogger().info("EtherTV Client handshake: " + player.getName() + " / " + clientVersion);
                syncNearbySources(player);
            } else if (type == YOUTUBE_FRAME) {
                String dimension = in.readUTF();
                int x = in.readInt(), y = in.readInt(), z = in.readInt();
                int clientSequence = in.readInt();
                int length = in.readInt();
                if (length < 4 || length > 180_000 || length != in.available()) return;
                byte[] jpeg = in.readNBytes(length);
                acceptYoutubeFrame(player, dimension, x, y, z, clientSequence, jpeg);
            } else if (type == FRAME || type == GAME_AUDIO) {
                UUID recordingId = new UUID(in.readLong(), in.readLong());
                int sequence = in.readInt();
                int length = in.readInt();
                int configuredMaximum = type == FRAME
                        ? plugin.getConfig().getInt("client-capture.max-frame-bytes", 400_000)
                        : plugin.getConfig().getInt("client-capture.max-game-audio-bytes", 48_000);
                int hardMaximum = type == FRAME ? 400_000 : 48_000;
                int maximum = Math.max(1, Math.min(hardMaximum, configuredMaximum));
                if (length < 1 || length > maximum || length != in.available()) return;
                byte[] data = in.readNBytes(length);
                if (type == FRAME) plugin.acceptClientFrame(player, recordingId, sequence, data);
                else plugin.acceptClientGameAudio(player, recordingId, sequence, data);
            }
        } catch (IOException | RuntimeException exception) {
            plugin.getLogger().fine("Rejected malformed EtherTV client packet from " + player.getName());
        }
    }

    public void startCapture(Player operator, UUID recordingId, Block camera) {
        if (!hasClient(operator)) return;
        send(operator, packet(out -> {
            out.writeByte(CAPTURE_START);
            out.writeLong(recordingId.getMostSignificantBits());
            out.writeLong(recordingId.getLeastSignificantBits());
            out.writeUTF(camera.getWorld().getKey().asString());
            out.writeInt(camera.getX()); out.writeInt(camera.getY()); out.writeInt(camera.getZ());
            out.writeBoolean(plugin.getConfig().getBoolean("client-capture.game-audio-enabled", true));
        }));
    }

    public void stopCapture(UUID operatorId, UUID recordingId) {
        Player operator = Bukkit.getPlayer(operatorId);
        if (operator == null || !hasClient(operator)) return;
        send(operator, packet(out -> {
            out.writeByte(CAPTURE_STOP);
            out.writeLong(recordingId.getMostSignificantBits());
            out.writeLong(recordingId.getLeastSignificantBits());
        }));
    }

    public int setYoutube(Block television, String videoId, Player preferredProvider) {
        String key = blockKey(television.getWorld(), television.getX(), television.getY(), television.getZ());
        Player provider = isNearbyClient(preferredProvider, television) ? preferredProvider : findFrameProvider(television);
        YoutubeSource source = new YoutubeSource(television.getWorld().getUID(), television.getWorld().getKey().asString(), television.getX(), television.getY(), television.getZ(), videoId, System.currentTimeMillis(), provider == null ? null : provider.getUniqueId());
        youtubeSources.put(key, source);
        youtubeFrameTimes.remove(key);
        youtubeFrameSequences.put(key, new java.util.concurrent.atomic.AtomicLong());
        youtubeClientSequences.remove(key);
        plugin.startYoutubeScreen(television);
        int sent = 0;
        for (Player viewer : television.getWorld().getPlayers()) {
            if (!hasClient(viewer) || viewer.getLocation().distanceSquared(television.getLocation()) > 64.0 * 64.0) continue;
            deliveredSources.computeIfAbsent(viewer.getUniqueId(), ignored -> ConcurrentHashMap.newKeySet()).add(key);
            sendYoutube(viewer, source); // Always resend on an explicit source change.
            sent++;
        }
        return sent;
    }

    private void acceptYoutubeFrame(Player player, String dimension, int x, int y, int z, int clientSequence, byte[] jpeg) {
        if (player == null || !hasClient(player) || !player.hasPermission("ethertv.use") || clientSequence < 0 || jpeg == null
                || jpeg.length < 4 || jpeg.length > 180_000 || (jpeg[0] & 0xff) != 0xff
                || (jpeg[1] & 0xff) != 0xd8 || (jpeg[2] & 0xff) != 0xff || !isExpectedYoutubeFrame(jpeg)) return;
        World world = player.getWorld();
        if (!world.getKey().asString().equals(dimension)) return;
        String key = blockKey(world, x, y, z);
        YoutubeSource source = youtubeSources.get(key);
        if (source == null || !source.worldId.equals(world.getUID()) || !player.getUniqueId().equals(source.frameProvider)) return;
        double dx = player.getLocation().getX() - (x + 0.5);
        double dy = player.getLocation().getY() - (y + 0.5);
        double dz = player.getLocation().getZ() - (z + 0.5);
        if (dx * dx + dy * dy + dz * dz > 64.0 * 64.0) return;
        ClientFrameState previousClient = youtubeClientSequences.get(key);
        if (previousClient != null && previousClient.provider.equals(player.getUniqueId()) && clientSequence <= previousClient.sequence) return;
        youtubeClientSequences.put(key, new ClientFrameState(player.getUniqueId(), clientSequence));
        long now = System.currentTimeMillis();
        Long previous = youtubeFrameTimes.putIfAbsent(key, now);
        if (previous != null) {
            if (now - previous < 450L || !youtubeFrameTimes.replace(key, previous, now)) return;
        }
        long sequence = youtubeFrameSequences.computeIfAbsent(key, ignored -> new java.util.concurrent.atomic.AtomicLong()).getAndIncrement();
        plugin.acceptYoutubeFrame(world, x, y, z, sequence, jpeg);
    }

    public int controlYoutube(Block television, String action, double seconds) {
        int sent = 0;
        for (Player viewer : television.getWorld().getPlayers()) {
            if (!hasClient(viewer) || viewer.getLocation().distanceSquared(television.getLocation()) > 64.0 * 64.0) continue;
            send(viewer, packet(out -> {
                out.writeByte(YOUTUBE_CONTROL);
                out.writeUTF(television.getWorld().getKey().asString());
                out.writeInt(television.getX()); out.writeInt(television.getY()); out.writeInt(television.getZ());
                out.writeUTF(action);
                out.writeDouble(Math.max(0.0, Math.min(seconds, 86_400.0)));
            }));
            sent++;
        }
        return sent;
    }

    public int stopYoutube(Block television) {
        String key = blockKey(television.getWorld(), television.getX(), television.getY(), television.getZ());
        youtubeSources.remove(key);
        youtubeFrameTimes.remove(key);
        youtubeFrameSequences.remove(key);
        youtubeClientSequences.remove(key);
        plugin.stopYoutubeScreen(television);
        int sent = 0;
        for (Player viewer : television.getWorld().getPlayers()) {
            Set<String> delivered = deliveredSources.get(viewer.getUniqueId());
            if (delivered == null || !delivered.remove(key) || !hasClient(viewer)) continue;
            sendYoutubeStop(viewer, television.getWorld().getKey().asString(), television.getX(), television.getY(), television.getZ());
            sent++;
        }
        return sent;
    }

    public void syncNearbySources(Player viewer) {
        if (!hasClient(viewer)) return;
        refreshFrameProviders(viewer.getWorld());
        Set<String> delivered = deliveredSources.computeIfAbsent(viewer.getUniqueId(), ignored -> ConcurrentHashMap.newKeySet());
        Set<String> nearby = ConcurrentHashMap.newKeySet();
        for (Map.Entry<String, YoutubeSource> entry : youtubeSources.entrySet()) {
            YoutubeSource source = entry.getValue();
            if (!source.worldId.equals(viewer.getWorld().getUID())) continue;
            double dx = viewer.getLocation().getX() - (source.x + 0.5);
            double dy = viewer.getLocation().getY() - (source.y + 0.5);
            double dz = viewer.getLocation().getZ() - (source.z + 0.5);
            if (dx * dx + dy * dy + dz * dz <= 64.0 * 64.0) {
                nearby.add(entry.getKey());
                if (delivered.add(entry.getKey())) sendYoutube(viewer, source);
            }
        }
        for (String oldKey : Set.copyOf(delivered)) {
            if (nearby.contains(oldKey)) continue;
            YoutubeSource old = youtubeSources.get(oldKey);
            delivered.remove(oldKey);
            if (old != null) sendYoutubeStop(viewer, old.worldKey, old.x, old.y, old.z);
        }
        if (delivered.isEmpty()) deliveredSources.remove(viewer.getUniqueId());
    }

    private void sendYoutubeStop(Player viewer, String dimension, int x, int y, int z) {
        send(viewer, packet(out -> {
            out.writeByte(YOUTUBE_STOP);
            out.writeUTF(dimension);
            out.writeInt(x); out.writeInt(y); out.writeInt(z);
        }));
    }

    private void sendYoutube(Player viewer, YoutubeSource source) {
        send(viewer, packet(out -> {
            out.writeByte(YOUTUBE_SET);
            out.writeUTF(source.worldKey);
            out.writeInt(source.x); out.writeInt(source.y); out.writeInt(source.z);
            out.writeUTF(source.videoId);
            out.writeLong(source.startedAt);
            out.writeBoolean(viewer.getUniqueId().equals(source.frameProvider));
        }));
    }

    private void send(Player player, byte[] body) {
        if (body.length <= MAX_PACKET_BYTES && player.isOnline() && player.getListeningPluginChannels().contains(CHANNEL)) {
            player.sendPluginMessage(plugin, CHANNEL, body);
        }
    }

    private static byte[] packet(IoWriter writer) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(MAGIC); out.writeByte(PROTOCOL); writer.write(out); out.flush();
            return bytes.toByteArray();
        } catch (IOException impossible) { throw new IllegalStateException(impossible); }
    }

    private static String blockKey(World world, int x, int y, int z) {
        return world.getUID() + ":" + x + ":" + y + ":" + z;
    }

    private void clientJoined(Player player) {
        // The client sends HELLO after its play connection is established. This method
        // deliberately does not assume that a player has the optional mod installed.
    }

    @Override
    public void close() {
        capableClients.clear();
        youtubeSources.clear();
        deliveredSources.clear();
        youtubeFrameTimes.clear();
        youtubeFrameSequences.clear();
        youtubeClientSequences.clear();
        plugin.getServer().getMessenger().unregisterIncomingPluginChannel(plugin, CHANNEL, this);
        plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin, CHANNEL);
    }

    private static boolean isExpectedYoutubeFrame(byte[] jpeg) {
        try (javax.imageio.stream.ImageInputStream input = javax.imageio.ImageIO.createImageInputStream(new java.io.ByteArrayInputStream(jpeg))) {
            if (input == null) return false;
            var readers = javax.imageio.ImageIO.getImageReaders(input);
            if (!readers.hasNext()) return false;
            javax.imageio.ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                return reader.getWidth(0) == 320 && reader.getHeight(0) == 180;
            } finally { reader.dispose(); }
        } catch (IOException | RuntimeException invalid) { return false; }
    }

    private record ClientFrameState(UUID provider, int sequence) { }

    private boolean isNearbyClient(Player player, Block television) {
        return player != null && hasClient(player) && player.getWorld().equals(television.getWorld())
                && player.getLocation().distanceSquared(television.getLocation()) <= 64.0 * 64.0;
    }

    private Player findFrameProvider(Block television) {
        return television.getWorld().getPlayers().stream()
                .filter(player -> isNearbyClient(player, television))
                .min(java.util.Comparator.comparingDouble(player -> player.getLocation().distanceSquared(television.getLocation())))
                .orElse(null);
    }

    private void refreshFrameProviders(World world) {
        for (Map.Entry<String, YoutubeSource> entry : Map.copyOf(youtubeSources).entrySet()) {
            YoutubeSource source = entry.getValue();
            if (!source.worldId.equals(world.getUID())) continue;
            Player current = source.frameProvider == null ? null : Bukkit.getPlayer(source.frameProvider);
            Block television = world.getBlockAt(source.x, source.y, source.z);
            if (isNearbyClient(current, television)) continue;
            Player replacement = findFrameProvider(television);
            UUID replacementId = replacement == null ? null : replacement.getUniqueId();
            if (java.util.Objects.equals(source.frameProvider, replacementId)) continue;
            YoutubeSource updated = new YoutubeSource(source.worldId, source.worldKey, source.x, source.y, source.z,
                    source.videoId, source.startedAt, replacementId);
            if (!youtubeSources.replace(entry.getKey(), source, updated)) continue;
            youtubeFrameTimes.remove(entry.getKey());
            youtubeClientSequences.remove(entry.getKey());
            for (Player candidate : world.getPlayers()) {
                if (hasClient(candidate) && candidate.getLocation().distanceSquared(television.getLocation()) <= 64.0 * 64.0) {
                    sendYoutube(candidate, updated);
                }
            }
        }
    }

    private record YoutubeSource(UUID worldId, String worldKey, int x, int y, int z, String videoId, long startedAt, UUID frameProvider) { }
    @FunctionalInterface private interface IoWriter { void write(DataOutputStream out) throws IOException; }

    private static final class BridgeJoinListener implements org.bukkit.event.Listener {
        private final ClientBridgeService bridge;
        private BridgeJoinListener(ClientBridgeService bridge) { this.bridge = bridge; }
        @org.bukkit.event.EventHandler public void onJoin(org.bukkit.event.player.PlayerJoinEvent event) {
            bridge.clientJoined(event.getPlayer());
        }
        @org.bukkit.event.EventHandler public void onMove(org.bukkit.event.player.PlayerMoveEvent event) {
            if (event.getTo() == null) return;
            if (!event.getFrom().getWorld().equals(event.getTo().getWorld())
                    || event.getFrom().getBlockX() != event.getTo().getBlockX()
                    || event.getFrom().getBlockY() != event.getTo().getBlockY()
                    || event.getFrom().getBlockZ() != event.getTo().getBlockZ()) {
                bridge.syncNearbySources(event.getPlayer());
            }
        }
        @org.bukkit.event.EventHandler public void onQuit(org.bukkit.event.player.PlayerQuitEvent event) {
            bridge.capableClients.remove(event.getPlayer().getUniqueId());
            bridge.deliveredSources.remove(event.getPlayer().getUniqueId());
            for (Player remaining : event.getPlayer().getWorld().getPlayers()) bridge.syncNearbySources(remaining);
        }
    }

    public static String extractYoutubeId(String raw) {
        if (raw == null || raw.length() > 512) return null;
        try {
            java.net.URI uri = java.net.URI.create(raw.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme())) return null;
            String host = uri.getHost();
            if (host == null) return null;
            host = host.toLowerCase(java.util.Locale.ROOT);
            String id = null;
            if (host.equals("youtu.be")) id = uri.getPath().replaceFirst("^/", "").split("/", 2)[0];
            else if (host.equals("youtube.com") || host.equals("www.youtube.com") || host.equals("m.youtube.com") || host.equals("youtube-nocookie.com") || host.equals("www.youtube-nocookie.com")) {
                String path = uri.getPath();
                if ("/watch".equals(path)) id = java.util.Arrays.stream(java.util.Optional.ofNullable(uri.getQuery()).orElse("").split("&"))
                        .filter(p -> p.startsWith("v=")).map(p -> p.substring(2)).findFirst().orElse(null);
                else if (path != null && (path.startsWith("/embed/") || path.startsWith("/shorts/") || path.startsWith("/live/"))) id = path.substring(path.lastIndexOf('/') + 1);
            }
            return id != null && id.matches("[A-Za-z0-9_-]{11}") ? id : null;
        } catch (IllegalArgumentException ignored) { return null; }
    }
}
