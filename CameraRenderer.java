package ru.ethertv.camera;

import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Directional;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.bukkit.World;

import java.awt.Color;
import java.awt.image.BufferedImage;

/**
 * Lightweight server-side block ray renderer. This is NOT a screenshot of a Minecraft client:
 * it approximates block geometry with flat material colours and intentionally omits shaders,
 * entity animation, particles and client-only sounds.
 */
public final class CameraRenderer {
    private CameraRenderer() {}

    public static BufferedImage capture(Block camera, int width, int height, double range) {
        World world = camera.getWorld();
        BlockFace facing = camera.getBlockData() instanceof Directional directional
                ? directional.getFacing() : BlockFace.NORTH;
        Vector forward = new Vector(facing.getModX(), facing.getModY(), facing.getModZ()).normalize();
        if (forward.lengthSquared() == 0) forward = new Vector(0, 0, -1);
        Vector worldUp = Math.abs(forward.dot(new Vector(0, 1, 0))) > 0.95
                ? new Vector(0, 0, 1) : new Vector(0, 1, 0);
        Vector right = forward.clone().crossProduct(worldUp).normalize();
        Vector up = right.clone().crossProduct(forward).normalize();
        Location origin = camera.getLocation().add(0.5, 0.5, 0.5).add(forward.clone().multiply(0.55));
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);

        double aspect = (double) width / height;
        double tanHalfFov = Math.tan(Math.toRadians(32.0));
        for (int py = 0; py < height; py++) {
            for (int px = 0; px < width; px++) {
                double sx = ((px + 0.5) / width * 2.0 - 1.0) * aspect * tanHalfFov;
                double sy = (1.0 - (py + 0.5) / height * 2.0) * tanHalfFov;
                Vector ray = forward.clone().add(right.clone().multiply(sx)).add(up.clone().multiply(sy)).normalize();
                RayTraceResult hit = world.rayTraceBlocks(origin, ray, range, FluidCollisionMode.NEVER, true);
                int rgb = hit == null || hit.getHitBlock() == null
                        ? skyColor(py, height)
                        : materialColor(hit.getHitBlock().getType(), hit.getHitBlock().getY(), origin.getBlockY());
                image.setRGB(px, py, rgb);
            }
        }
        return image;
    }

    private static int skyColor(int y, int height) {
        float t = Math.max(0f, Math.min(1f, (float) y / Math.max(1, height - 1)));
        return new Color((int) (100 - 35 * t), (int) (165 - 45 * t), (int) (220 - 20 * t)).getRGB();
    }

    private static int materialColor(Material material, int blockY, int cameraY) {
        String n = material.name();
        Color c;
        if (n.contains("WATER")) c = new Color(45, 95, 180);
        else if (n.contains("LAVA")) c = new Color(235, 95, 20);
        else if (n.contains("LEAVES") || n.contains("MOSS") || n.contains("GRASS") || n.contains("VINE")) c = new Color(65, 125, 55);
        else if (n.contains("LOG") || n.contains("WOOD") || n.contains("PLANK")) c = new Color(125, 88, 52);
        else if (n.contains("SAND") || n.contains("SANDSTONE")) c = new Color(205, 190, 125);
        else if (n.contains("STONE") || n.contains("DEEPSLATE") || n.contains("COBBLE")) c = new Color(105, 105, 110);
        else if (n.contains("IRON") || n.contains("COPPER") || n.contains("GOLD") || n.contains("METAL")) c = new Color(160, 145, 120);
        else if (n.contains("GLASS")) c = new Color(155, 205, 215);
        else if (n.contains("SNOW") || n.contains("ICE")) c = new Color(210, 225, 235);
        else if (n.contains("WOOL") || n.contains("CONCRETE") || n.contains("TERRACOTTA")) c = new Color(145, 115, 105);
        else c = new Color(125, 125, 125);
        int shade = blockY < cameraY ? -12 : 12;
        return new Color(clamp(c.getRed() + shade), clamp(c.getGreen() + shade), clamp(c.getBlue() + shade)).getRGB();
    }

    private static int clamp(int n) { return Math.max(0, Math.min(255, n)); }
}
