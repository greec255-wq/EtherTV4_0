package ru.ethertv.television;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.TileState;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Persistent ET-1 channel/mode state and direct, same-world 3D radio range. */
public final class TelevisionControlService {
    public static final int CHANNEL_COUNT = 15;
    private final JavaPlugin plugin;
    private final org.bukkit.NamespacedKey channelKey;
    private final org.bukkit.NamespacedKey modeKey;
    private final org.bukkit.NamespacedKey idKey;

    public TelevisionControlService(JavaPlugin plugin) {
        this.plugin = plugin;
        channelKey = new org.bukkit.NamespacedKey(plugin, "tv_channel");
        modeKey = new org.bukkit.NamespacedKey(plugin, "tv_mode");
        idKey = new org.bukkit.NamespacedKey(plugin, "tv_id");
    }

    public void initialize(Block block) {
        if (!(block.getState() instanceof TileState tile)) return;
        var pdc = tile.getPersistentDataContainer();
        if (!pdc.has(channelKey, PersistentDataType.INTEGER)) pdc.set(channelKey, PersistentDataType.INTEGER, 1);
        if (!pdc.has(modeKey, PersistentDataType.STRING)) pdc.set(modeKey, PersistentDataType.STRING, "RECEIVER");
        if (!pdc.has(idKey, PersistentDataType.STRING)) pdc.set(idKey, PersistentDataType.STRING, UUID.randomUUID().toString());
        tile.update(true, false);
    }

    public int channel(Block block) {
        if (!(block.getState() instanceof TileState tile)) return 1;
        Integer value = tile.getPersistentDataContainer().get(channelKey, PersistentDataType.INTEGER);
        return value == null || value < 1 || value > CHANNEL_COUNT ? 1 : value;
    }

    public int cycleChannel(Block block) {
        int next = channel(block) % CHANNEL_COUNT + 1;
        update(block, channelKey, PersistentDataType.INTEGER, next);
        return next;
    }

    public boolean isTransmitter(Block block) {
        if (!(block.getState() instanceof TileState tile)) return false;
        return "TRANSMITTER".equals(tile.getPersistentDataContainer().get(modeKey, PersistentDataType.STRING));
    }

    public boolean toggleMode(Block block) {
        boolean next = !isTransmitter(block);
        update(block, modeKey, PersistentDataType.STRING, next ? "TRANSMITTER" : "RECEIVER");
        return next;
    }

    public UUID id(Block block) {
        if (!(block.getState() instanceof TileState tile)) return new UUID(0, 0);
        String value = tile.getPersistentDataContainer().get(idKey, PersistentDataType.STRING);
        try { return UUID.fromString(value); }
        catch (RuntimeException invalid) { return new UUID(0, 0); }
    }

    /** Each lightning rod in the contiguous vertical stack directly above the TV adds 1000 blocks. */
    public int radius(Block transmitter) {
        int rods = 0;
        int x = transmitter.getX(), z = transmitter.getZ();
        World world = transmitter.getWorld();
        for (int y = transmitter.getY() + 1; y < world.getMaxHeight() && rods < 20; y++) {
            if (world.getBlockAt(x, y, z).getType() != Material.LIGHTNING_ROD) break;
            rods++;
        }
        return Math.min(rods * 1000, 20000);
    }

    public boolean inRange(Block transmitter, Block receiver) {
        if (!transmitter.getWorld().equals(receiver.getWorld()) || radius(transmitter) <= 0) return false;
        double dx = transmitter.getX() + .5 - (receiver.getX() + .5);
        double dy = transmitter.getY() + .5 - (receiver.getY() + .5);
        double dz = transmitter.getZ() + .5 - (receiver.getZ() + .5);
        double r = radius(transmitter);
        return dx * dx + dy * dy + dz * dz <= r * r;
    }

    /** Finds the nearest eligible transmitter; UUID breaks exact distance ties deterministically. */
    public Block nearestTransmitter(Block receiver, List<Block> loadedTelevisions) {
        return loadedTelevisions.stream()
                .filter(this::isTransmitter)
                .filter(b -> channel(b) == channel(receiver))
                .filter(b -> inRange(b, receiver))
                .min(Comparator.comparingDouble((Block b) -> b.getLocation().distanceSquared(receiver.getLocation()))
                        .thenComparing(b -> id(b).toString()))
                .orElse(null);
    }

    private <T> void update(Block block, org.bukkit.NamespacedKey key, PersistentDataType<T, T> type, T value) {
        if (!(block.getState() instanceof TileState tile)) return;
        tile.getPersistentDataContainer().set(key, type, value);
        tile.update(true, false);
    }
}
