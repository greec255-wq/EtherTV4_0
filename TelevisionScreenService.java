package ru.ethertv.television;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Rotation;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapView;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Creates map screens and binds a complete adjacent map-frame grid to one video feed. */
public final class TelevisionScreenService implements AutoCloseable {
    private final JavaPlugin plugin;
    private final Map<String, List<BoundRenderer>> activeRenderers = new HashMap<>();

    public TelevisionScreenService(JavaPlugin plugin) { this.plugin = plugin; }

    public ItemStack createScreenMap(World world) {
        MapView view = Bukkit.createMap(world);
        view.setTrackingPosition(false);
        view.setUnlimitedTracking(false);
        view.setLocked(true);
        ItemStack item = new ItemStack(Material.FILLED_MAP);
        MapMeta meta = (MapMeta) item.getItemMeta();
        meta.setMapView(view);
        meta.displayName(net.kyori.adventure.text.Component.text("Экран ЭТ-1"));
        item.setItemMeta(meta);
        return item;
    }

    public boolean bindNearbyFrame(org.bukkit.block.Block television, Path framesDirectory, long intervalMillis) {
        return bindNearbyFrame(television, framesDirectory, intervalMillis, false);
    }

    public boolean bindNearbyFrame(org.bukkit.block.Block television, Path framesDirectory,
                                   long intervalMillis, boolean startAtBeginning) {
        List<ItemFrame> nearby = new ArrayList<>();
        for (Entity entity : television.getWorld().getNearbyEntities(
                television.getLocation().add(0.5, 0.5, 0.5), 5.5, 5.5, 5.5)) {
            if (entity instanceof ItemFrame frame && mapView(frame) != null) nearby.add(frame);
        }
        if (nearby.isEmpty()) return false;

        ItemFrame seed = nearby.stream()
                .min(java.util.Comparator.comparingDouble(frame -> frame.getLocation().distanceSquared(television.getLocation())))
                .orElse(null);
        if (seed == null || seed.getRotation() != Rotation.NONE) return false;

        BlockFace facing = seed.getFacing();
        if (facing != BlockFace.NORTH && facing != BlockFace.SOUTH
                && facing != BlockFace.EAST && facing != BlockFace.WEST) return false;
        Map<Position, ItemFrame> candidates = new HashMap<>();
        int seedDepth = planeDepth(seed, facing);
        for (ItemFrame frame : nearby) {
            if (frame.getFacing() != facing || planeDepth(frame, facing) != seedDepth
                    || frame.getRotation() != Rotation.NONE) continue;
            Position position = position(frame, facing);
            candidates.put(position, frame);
        }
        Position seedPosition = position(seed, facing);
        Map<Position, ItemFrame> group = collectConnected(seedPosition, candidates);
        if (group.isEmpty() || group.size() > 16) return false;

        int minU = group.keySet().stream().mapToInt(Position::u).min().orElse(0);
        int maxU = group.keySet().stream().mapToInt(Position::u).max().orElse(0);
        int minV = group.keySet().stream().mapToInt(Position::v).min().orElse(0);
        int maxV = group.keySet().stream().mapToInt(Position::v).max().orElse(0);
        int columns = maxU - minU + 1;
        int rows = maxV - minV + 1;
        if (columns < 1 || rows < 1 || columns > 4 || rows > 4 || columns * rows != group.size()) return false;

        // Only replace renderers after the entire screen layout has passed validation.
        MapVideoRenderer.PlaybackFeed feed = new MapVideoRenderer.PlaybackFeed(
                plugin, framesDirectory, intervalMillis, startAtBeginning, columns, rows);
        List<BoundRenderer> newRenderers = new ArrayList<>();
        for (var entry : group.entrySet()) {
            int tileX = entry.getKey().u() - minU;
            int tileY = maxV - entry.getKey().v(); // top row first
            MapView view = mapView(entry.getValue());
            if (view == null) { feed.close(); return false; }
            MapVideoRenderer renderer = new MapVideoRenderer(feed, tileX, tileY);
            view.addRenderer(renderer);
            newRenderers.add(new BoundRenderer(view, renderer));
        }
        String televisionKey = television.getWorld().getUID() + ":" + television.getX() + ":" + television.getY() + ":" + television.getZ();
        closeActiveRenderers(televisionKey);
        activeRenderers.put(televisionKey, newRenderers);
        return true;
    }

    private static MapView mapView(ItemFrame frame) {
        ItemStack held = frame.getItem();
        if (held == null || held.getType() != Material.FILLED_MAP || !(held.getItemMeta() instanceof MapMeta meta)) return null;
        return meta.getMapView();
    }

    private static Position position(ItemFrame frame, BlockFace facing) {
        int x = frame.getLocation().getBlockX();
        int y = frame.getLocation().getBlockY();
        int z = frame.getLocation().getBlockZ();
        if (facing == BlockFace.NORTH) return new Position(-x, y);
        if (facing == BlockFace.SOUTH) return new Position(x, y);
        if (facing == BlockFace.EAST) return new Position(-z, y);
        return new Position(z, y); // WEST
    }

    private static int planeDepth(ItemFrame frame, BlockFace facing) {
        return switch (facing) {
            case NORTH, SOUTH -> frame.getLocation().getBlockZ();
            case EAST, WEST -> frame.getLocation().getBlockX();
            case UP, DOWN -> frame.getLocation().getBlockY();
            default -> Integer.MIN_VALUE;
        };
    }

    private static Map<Position, ItemFrame> collectConnected(Position seed, Map<Position, ItemFrame> candidates) {
        Map<Position, ItemFrame> result = new HashMap<>();
        ArrayDeque<Position> queue = new ArrayDeque<>();
        queue.add(seed);
        while (!queue.isEmpty() && result.size() < 17) {
            Position current = queue.removeFirst();
            ItemFrame frame = candidates.get(current);
            if (frame == null || result.putIfAbsent(current, frame) != null) continue;
            queue.add(new Position(current.u() + 1, current.v()));
            queue.add(new Position(current.u() - 1, current.v()));
            queue.add(new Position(current.u(), current.v() + 1));
            queue.add(new Position(current.u(), current.v() - 1));
        }
        return result;
    }

    private record Position(int u, int v) { }
    private record BoundRenderer(MapView view, MapVideoRenderer renderer) { }

    private void closeActiveRenderers(String televisionKey) {
        List<BoundRenderer> bindings = activeRenderers.remove(televisionKey);
        if (bindings == null) return;
        for (BoundRenderer binding : bindings) {
            binding.view().removeRenderer(binding.renderer());
            binding.renderer().close();
        }
    }

    @Override
    public void close() {
        for (String key : List.copyOf(activeRenderers.keySet())) closeActiveRenderers(key);
    }
}
