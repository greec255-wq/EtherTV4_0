package ru.ethertv.television;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.entity.Player;
import ru.ethertv.EtherTVPlugin;
import ru.ethertv.network.ClientBridgeService;
import space.subkek.customdiscs.api.DiscEntry;
import space.subkek.customdiscs.api.event.CustomDiscEjectEvent;
import space.subkek.customdiscs.api.event.CustomDiscInsertEvent;

/** Connects CustomDiscs jukebox events to the existing EtherTV browser/map pipeline. */
public final class CustomDiscTelevisionService implements Listener {
    private final EtherTVPlugin plugin;
    private final ClientBridgeService bridge;

    public CustomDiscTelevisionService(EtherTVPlugin plugin, ClientBridgeService bridge) {
        this.plugin = plugin;
        this.bridge = bridge;
    }

    @EventHandler(ignoreCancelled = true)
    public void onDiscInsert(CustomDiscInsertEvent event) {
        Block television = event.getBlock();
        if (!plugin.isTelevision(television)) return;
        DiscEntry entry = event.getDiscEntry();
        String videoId = normalizeYoutubeIdentifier(entry == null ? null : entry.getIdentifier());
        Player player = event.getPlayer();
        if (videoId == null) {
            if (player != null) player.sendMessage(Component.text(
                    "Эта пластинка CustomDiscs не распознана как YouTube-источник для видеорежима EtherTV.", NamedTextColor.RED));
            return;
        }
        int viewers = bridge.setYoutube(television, videoId, player);
        if (player != null) player.sendMessage(Component.text(
                "Пластинка принята телевизором ЭТ-1. Видеоклиентов: " + viewers, NamedTextColor.GREEN));
    }

    @EventHandler(ignoreCancelled = true)
    public void onDiscEject(CustomDiscEjectEvent event) {
        Block television = event.getBlock();
        if (!plugin.isTelevision(television)) return;
        int stopped = bridge.stopYoutube(television);
        Player player = event.getPlayer();
        if (player != null) player.sendMessage(Component.text(
                "Пластинка извлечена. Видеосигнал телевизора остановлен; клиентов: " + stopped, NamedTextColor.YELLOW));
    }

    private static String normalizeYoutubeIdentifier(String identifier) {
        if (identifier == null || identifier.isBlank() || identifier.length() > 512) return null;
        String trimmed = identifier.trim();
        String fromUrl = ClientBridgeService.extractYoutubeId(trimmed);
        if (fromUrl != null) return fromUrl;
        // CustomDiscs stores remote service identifiers in the DiscEntry. For YouTube
        // discs this can be the 11-character YouTube video ID itself.
        return trimmed.matches("[A-Za-z0-9_-]{11}") ? trimmed : null;
    }
}
