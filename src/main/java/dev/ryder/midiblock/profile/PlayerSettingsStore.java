package dev.ryder.midiblock.profile;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Small persistent per-player preference store; playback state itself is intentionally not persisted. */
public final class PlayerSettingsStore {
    private static final int HISTORY_LIMIT = 20;
    private final File file;
    private final YamlConfiguration data;
    private final float defaultVolume;

    public PlayerSettingsStore(File dataFolder, float defaultVolume) {
        this.file = new File(dataFolder, "players.yml");
        this.data = YamlConfiguration.loadConfiguration(file);
        this.defaultVolume = Math.clamp(defaultVolume, 0.0F, 1.0F);
    }

    public float volume(Player player) {
        return (float) Math.clamp(data.getDouble(path(player) + ".volume", defaultVolume), 0.0D, 1.0D);
    }

    public void setVolume(Player player, float volume) {
        data.set(path(player) + ".volume", Math.clamp(volume, 0.0F, 1.0F));
        save();
    }

    public List<String> history(Player player) {
        return List.copyOf(data.getStringList(path(player) + ".history"));
    }

    public void recordPlayed(Player player, String songId) {
        List<String> history = new ArrayList<>(history(player));
        history.removeIf(songId::equalsIgnoreCase);
        history.addFirst(songId);
        if (history.size() > HISTORY_LIMIT) history = history.subList(0, HISTORY_LIMIT);
        data.set(path(player) + ".history", history);
        save();
    }

    public List<Playlist> playlists(Player player) {
        String base = path(player) + ".playlists";
        var section = data.getConfigurationSection(base);
        if (section == null) return List.of();
        return section.getKeys(false).stream()
            .map(id -> new Playlist(id, data.getString(base + "." + id + ".name", id), data.getStringList(base + "." + id + ".songs")))
            .sorted(Comparator.comparing(Playlist::name, String.CASE_INSENSITIVE_ORDER))
            .toList();
    }

    public Optional<Playlist> playlist(Player player, String nameOrId) {
        String normalized = playlistId(nameOrId);
        return playlists(player).stream().filter(playlist -> playlist.id().equals(normalized) || playlist.name().equalsIgnoreCase(nameOrId)).findFirst();
    }

    public Playlist createPlaylist(Player player, String name) {
        String id = playlistId(name);
        if (id.isBlank()) throw new IllegalArgumentException("Playlist name needs letters or numbers.");
        if (playlist(player, id).isPresent()) throw new IllegalArgumentException("A playlist with that name already exists.");
        String base = path(player) + ".playlists." + id;
        data.set(base + ".name", name.trim());
        data.set(base + ".songs", List.of());
        save();
        return new Playlist(id, name.trim(), List.of());
    }

    public boolean addToPlaylist(Player player, String nameOrId, String songId) {
        Optional<Playlist> playlist = playlist(player, nameOrId);
        if (playlist.isEmpty()) return false;
        List<String> songs = new ArrayList<>(playlist.get().songIds());
        songs.add(songId);
        data.set(path(player) + ".playlists." + playlist.get().id() + ".songs", songs);
        save();
        return true;
    }

    public boolean deletePlaylist(Player player, String nameOrId) {
        Optional<Playlist> playlist = playlist(player, nameOrId);
        if (playlist.isEmpty()) return false;
        data.set(path(player) + ".playlists." + playlist.get().id(), null);
        save();
        return true;
    }

    private String path(Player player) {
        return "players." + player.getUniqueId();
    }

    private String playlistId(String name) {
        return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
    }

    private void save() {
        try {
            data.save(file);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not save MidiBlock player settings", exception);
        }
    }
}
