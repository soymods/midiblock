package dev.ryder.midibox.enhanced;

import dev.ryder.midibox.arrangement.PlayableNote;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.plugin.Plugin;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Set;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Optional resource-pack bridge. Native note-block playback remains the safe default. */
public final class EnhancedAudioService implements Listener {
    private static final UUID PACK_ID = UUID.nameUUIDFromBytes("midibox-enhanced-audio".getBytes(StandardCharsets.UTF_8));
    private final Plugin plugin;
    private final boolean enabled;
    private final boolean autoRequest;
    private final boolean required;
    private final String url;
    private final byte[] sha1;
    private final String prompt;
    private final Set<UUID> loadedPlayers = ConcurrentHashMap.newKeySet();

    public EnhancedAudioService(Plugin plugin) {
        this.plugin = plugin;
        this.enabled = plugin.getConfig().getBoolean("enhanced-audio.enabled", false);
        this.autoRequest = plugin.getConfig().getBoolean("enhanced-audio.auto-request", true);
        this.required = plugin.getConfig().getBoolean("enhanced-audio.required", false);
        this.url = plugin.getConfig().getString("enhanced-audio.resource-pack.url", "").trim();
        this.sha1 = parseHash(plugin.getConfig().getString("enhanced-audio.resource-pack.sha1", ""));
        this.prompt = plugin.getConfig().getString("enhanced-audio.resource-pack.prompt", "MidiBox has an optional high-fidelity audio pack.");
    }

    public boolean available() {
        return enabled && (url.startsWith("https://") || url.startsWith("http://"));
    }

    public boolean isEnabledFor(Player player) {
        return available() && loadedPlayers.contains(player.getUniqueId());
    }

    public boolean request(Player player) {
        if (!available()) return false;
        player.addResourcePack(PACK_ID, url, sha1, prompt, required);
        return true;
    }

    public String soundId(PlayableNote note) {
        if (note.percussion()) return "midibox:percussion/" + percussionName(note.midiKey());
        return "midibox:instruments/" + instrumentName(note.program());
    }

    public void stopSounds(Player player) {
        for (String sound : ENHANCED_SOUNDS) player.stopSound(sound);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        loadedPlayers.remove(event.getPlayer().getUniqueId());
        if (available() && autoRequest) request(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        loadedPlayers.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onPackStatus(PlayerResourcePackStatusEvent event) {
        if (!event.getID().equals(PACK_ID)) return;
        switch (event.getStatus()) {
            case SUCCESSFULLY_LOADED -> loadedPlayers.add(event.getPlayer().getUniqueId());
            case DECLINED, FAILED_DOWNLOAD, INVALID_URL, FAILED_RELOAD -> loadedPlayers.remove(event.getPlayer().getUniqueId());
            default -> { }
        }
    }

    private byte[] parseHash(String hash) {
        if (hash == null || hash.isBlank()) return null;
        String value = hash.trim();
        if (value.length() != 40) {
            plugin.getLogger().warning("enhanced-audio.resource-pack.sha1 must be a 40-character SHA-1 hex string; omitting it.");
            return null;
        }
        try {
            return HexFormat.of().parseHex(value);
        } catch (IllegalArgumentException exception) {
            plugin.getLogger().warning("enhanced-audio.resource-pack.sha1 is not valid hexadecimal; omitting it.");
            return null;
        }
    }

    private String instrumentName(int program) {
        if (program <= 7) return "piano";
        if (program <= 15) return "mallet";
        if (program <= 23) return "organ";
        if (program <= 31) return "guitar";
        if (program <= 39) return "bass";
        if (program <= 47) return "strings";
        if (program <= 63) return "brass";
        if (program <= 79) return "wind";
        if (program <= 95) return "synth";
        return "texture";
    }

    private String percussionName(int key) {
        return switch (key) {
            case 35, 36 -> "kick";
            case 38, 40 -> "snare";
            case 42, 44, 46 -> "hat";
            case 49, 57 -> "crash";
            default -> "percussion";
        };
    }

    private static final List<String> ENHANCED_SOUNDS = List.of(
        "midibox:instruments/piano", "midibox:instruments/mallet", "midibox:instruments/organ",
        "midibox:instruments/guitar", "midibox:instruments/bass", "midibox:instruments/strings",
        "midibox:instruments/brass", "midibox:instruments/wind", "midibox:instruments/synth",
        "midibox:instruments/texture", "midibox:percussion/kick", "midibox:percussion/snare",
        "midibox:percussion/hat", "midibox:percussion/crash", "midibox:percussion/percussion"
    );
}
