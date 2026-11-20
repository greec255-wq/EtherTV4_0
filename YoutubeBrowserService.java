package ru.ethertv.client.youtube;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** Keeps the synchronized YouTube source model; a Rinku-backed BrowserSurface provider supplies the actual CEF texture. */
public final class YoutubeBrowserService {
    private static volatile BrowserSurface surface;
    private YoutubeBrowserService() { }

    public static void install(BrowserSurface browserSurface) { surface = browserSurface; }

    public static void setSource(String dimension, int x, int y, int z, String videoId, long startedAt, boolean captureFrames) {
        BrowserSurface current = surface;
        if (current == null) {
            if (Minecraft.getInstance().player != null) {
                Minecraft.getInstance().player.displayClientMessage(Component.literal("EtherTV: браузерный модуль Rinku не подключён; YouTube-плеер пока недоступен."), false);
            }
            return;
        }
        current.loadOnTelevision(dimension, x, y, z, videoId, startedAt, captureFrames);
    }

    public static void stopSource(String dimension, int x, int y, int z) {
        BrowserSurface current = surface;
        if (current != null) current.stopOnTelevision(dimension, x, y, z);
    }

    public static void control(String dimension, int x, int y, int z, String action, double seconds) {
        BrowserSurface current = surface;
        if (current != null) current.controlOnTelevision(dimension, x, y, z, action, seconds);
    }

    public interface BrowserSurface {
        void loadOnTelevision(String dimension, int x, int y, int z, String videoId, long startedAt, boolean captureFrames);
        void stopOnTelevision(String dimension, int x, int y, int z);
        void controlOnTelevision(String dimension, int x, int y, int z, String action, double seconds);
    }
}
