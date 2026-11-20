package ru.ethertv.network;

import org.bukkit.Material;
import org.bukkit.block.Block;

/** Legacy API compatibility. Relay graphs are intentionally disabled in EtherTV 1.3.0. */
public final class BroadcastNetworkService {
    public BroadcastNetworkService(org.bukkit.plugin.java.JavaPlugin plugin) { }

    /** @deprecated Radio relays are no longer used. */
    @Deprecated public boolean register(Block block, String channel) { return false; }
    /** @deprecated Radio relays are no longer used. */
    @Deprecated public boolean unregister(Block block) { return false; }
    /** @deprecated Radio relays are no longer used. */
    @Deprecated public java.util.List<String> describeNodes() { return java.util.List.of("Ретрансляторы отключены в EtherTV 1.3.0; используется прямая дальность от телевизора-передатчика."); }

    /** Compatibility check: direct 3D range from the source, using contiguous rods directly above it. */
    public boolean isConnected(Block source, Block destination, String channel) {
        if (source == null || destination == null || !source.getWorld().equals(destination.getWorld())) return false;
        int rods = 0;
        for (int y = source.getY() + 1; y < source.getWorld().getMaxHeight() && rods < 20; y++) {
            if (source.getWorld().getBlockAt(source.getX(), y, source.getZ()).getType() != Material.LIGHTNING_ROD) break;
            rods++;
        }
        if (rods == 0) return false;
        double dx = source.getX() - destination.getX();
        double dy = source.getY() - destination.getY();
        double dz = source.getZ() - destination.getZ();
        double range = Math.min(rods * 1000.0, 20000.0);
        return dx * dx + dy * dy + dz * dz <= range * range;
    }
}
