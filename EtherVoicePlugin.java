package ru.ethertv.voice;

import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import ru.ethertv.EtherTVPlugin;

public final class EtherVoicePlugin implements VoicechatPlugin {
    private final EtherTVPlugin plugin;

    public EtherVoicePlugin(EtherTVPlugin plugin) { this.plugin = plugin; }

    @Override public String getPluginId() { return "ethertv"; }
    @Override public void initialize(VoicechatApi api) { plugin.setVoicechatApi(api); }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(MicrophonePacketEvent.class, event -> {
            if (event.getSenderConnection() == null || event.getSenderConnection().getPlayer() == null) return;
            plugin.acceptMicrophonePacket(
                    event.getSenderConnection().getPlayer().getUuid(),
                    event.getPacket().getOpusEncodedData(),
                    event.getVoicechat()
            );
        });
    }
}
