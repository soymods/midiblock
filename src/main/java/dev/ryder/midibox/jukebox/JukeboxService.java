package dev.ryder.midibox.jukebox;

import dev.ryder.midibox.MidiBoxPlugin;
import dev.ryder.midibox.library.Song;
import dev.ryder.midibox.playback.PlaybackService;
import dev.ryder.midibox.profile.PlayerSettingsStore;
import dev.ryder.midibox.ui.MusicMenu;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.block.TileState;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.List;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Persistent world jukeboxes backed by tile-state PDC data. */
public final class JukeboxService implements Listener {
    private final NamespacedKey jukeboxKey;
    private final NamespacedKey itemKey;
    private final NamespacedKey radiusKey;
    private final NamespacedKey labelKey;
    private final NamespacedKey restrictedKey;
    private final NamespacedKey loopKey;
    private final MusicMenu menu;
    private final PlaybackService playback;
    private final PlayerSettingsStore settings;
    private final int defaultRadius;
    private final Map<BlockKey, JukeboxPlayback> playing = new HashMap<>();
    private final Map<UUID, BlockKey> listeners = new HashMap<>();

    public JukeboxService(MidiBoxPlugin plugin, MusicMenu menu, PlaybackService playback, PlayerSettingsStore settings) {
        this.jukeboxKey = new NamespacedKey(plugin, "permanent_jukebox");
        this.itemKey = new NamespacedKey(plugin, "jukebox_item");
        this.radiusKey = new NamespacedKey(plugin, "jukebox_radius");
        this.labelKey = new NamespacedKey(plugin, "jukebox_label_uuid");
        this.restrictedKey = new NamespacedKey(plugin, "jukebox_restricted");
        this.loopKey = new NamespacedKey(plugin, "jukebox_loop");
        this.menu = menu;
        this.playback = playback;
        this.settings = settings;
        this.defaultRadius = Math.clamp(plugin.getConfig().getInt("jukebox.default-radius", 50), 1, 512);
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::syncListeners, 2L, 2L);
    }

    public void give(Player player) {
        player.getInventory().addItem(item());
        player.sendMessage(ChatColor.RED + "Received a " + ChatColor.BOLD + "JUKEBOX" + ChatColor.RED + ". Place it to create a public music player.");
    }

    @EventHandler
    public void onPlace(BlockPlaceEvent event) {
        if (event.getBlockPlaced().getType() != Material.JUKEBOX || !isItem(event.getItemInHand())) return;
        if (event.getBlockPlaced().getState() instanceof TileState state) {
            state.getPersistentDataContainer().set(jukeboxKey, PersistentDataType.BYTE, (byte) 1);
            state.getPersistentDataContainer().set(radiusKey, PersistentDataType.INTEGER, defaultRadius);
            state.getPersistentDataContainer().set(restrictedKey, PersistentDataType.BYTE, (byte) 0);
            state.getPersistentDataContainer().set(loopKey, PersistentDataType.BYTE, (byte) 0);
            state.update(true);
            ensureLabel(event.getBlockPlaced().getState());
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null || !isPermanent(event.getClickedBlock().getState())) return;
        event.setCancelled(true);
        ensureLabel(event.getClickedBlock().getState());
        int radius = radius(event.getClickedBlock().getState());
        menu.openJukebox(event.getPlayer(), event.getClickedBlock().getLocation(), radius);
    }

    @EventHandler
    public void onBreak(BlockBreakEvent event) {
        if (!isPermanent(event.getBlock().getState())) return;
        if (event.getPlayer().getGameMode() != GameMode.CREATIVE) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(ChatColor.RED + "Only Creative-mode players can break a permanent JUKEBOX.");
            return;
        }
        removeLabel(event.getBlock().getState());
        stopPlayback(event.getBlock().getLocation());
    }

    /** Starts one shared song clock at this block. Listeners join it at its current position. */
    public void play(Location location, int radius, Song song) {
        BlockKey key = BlockKey.of(location);
        stopListenersFor(key);
        playing.put(key, new JukeboxPlayback(key, location.toBlockLocation(), Math.clamp(radius, 1, 512), song, looping(location), System.nanoTime()));
        syncListeners();
        announceNowPlaying(key);
    }

    /** Queues a song after the active song; if idle, it becomes the new active song immediately. */
    public void queue(Location location, int radius, Song song) {
        JukeboxPlayback session = playing.get(BlockKey.of(location));
        if (session == null) {
            play(location, radius, song);
            return;
        }
        session.queue.addLast(song);
    }

    public boolean canControl(Player player, Location location) {
        return !restricted(location) || player.isOp();
    }

    public boolean restricted(Location location) {
        return flag(location, restrictedKey);
    }

    public boolean looping(Location location) {
        return flag(location, loopKey);
    }

    /** Operators alone may change whether public players can control a jukebox. */
    public boolean toggleRestricted(Player player, Location location) {
        if (!player.isOp()) return false;
        boolean restricted = !restricted(location);
        setFlag(location, restrictedKey, restricted);
        return restricted;
    }

    public boolean toggleLoop(Player player, Location location) {
        if (!canControl(player, location)) return false;
        boolean loop = !looping(location);
        setFlag(location, loopKey, loop);
        JukeboxPlayback session = playing.get(BlockKey.of(location));
        if (session != null) session.looping = loop;
        return loop;
    }

    private ItemStack item() {
        ItemStack item = new ItemStack(Material.JUKEBOX);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.RED + "" + ChatColor.BOLD + "JUKEBOX");
        meta.setLore(List.of(ChatColor.DARK_RED + "Permanent public music player", ChatColor.GRAY + "Right-click after placing to open"));
        meta.getPersistentDataContainer().set(itemKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    private boolean isItem(ItemStack item) {
        return item != null && item.getType() == Material.JUKEBOX && item.hasItemMeta() && item.getItemMeta().getPersistentDataContainer().has(itemKey, PersistentDataType.BYTE);
    }

    private boolean isPermanent(org.bukkit.block.BlockState state) {
        return state instanceof TileState tile && tile.getPersistentDataContainer().has(jukeboxKey, PersistentDataType.BYTE);
    }

    private int radius(org.bukkit.block.BlockState state) {
        return state instanceof TileState tile ? tile.getPersistentDataContainer().getOrDefault(radiusKey, PersistentDataType.INTEGER, defaultRadius) : defaultRadius;
    }

    private boolean flag(Location location, NamespacedKey key) {
        return location.getBlock().getState() instanceof TileState tile
            && tile.getPersistentDataContainer().getOrDefault(key, PersistentDataType.BYTE, (byte) 0) != 0;
    }

    private void setFlag(Location location, NamespacedKey key, boolean value) {
        if (!(location.getBlock().getState() instanceof TileState tile)) return;
        tile.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) (value ? 1 : 0));
        tile.update(true);
    }

    /** Creates (or repairs) the visible title for a persistent jukebox. */
    private void ensureLabel(org.bukkit.block.BlockState state) {
        if (!(state instanceof TileState tile)) return;
        String existingId = tile.getPersistentDataContainer().get(labelKey, PersistentDataType.STRING);
        if (existingId != null && findLabel(tile, existingId) != null) {
            return;
        }

        Location labelLocation = tile.getLocation().add(0.5D, 1.28D, 0.5D);
        TextDisplay label = tile.getWorld().spawn(labelLocation, TextDisplay.class, display -> {
            display.setText(ChatColor.RED + "" + ChatColor.BOLD + "JUKEBOX");
            display.setBillboard(TextDisplay.Billboard.CENTER);
            display.setSeeThrough(true);
            display.setShadowed(true);
        });
        tile.getPersistentDataContainer().set(labelKey, PersistentDataType.STRING, label.getUniqueId().toString());
        tile.update(true);
    }

    private void removeLabel(org.bukkit.block.BlockState state) {
        if (!(state instanceof TileState tile)) return;
        String labelId = tile.getPersistentDataContainer().get(labelKey, PersistentDataType.STRING);
        TextDisplay display = labelId == null ? null : findLabel(tile, labelId);
        if (display != null) {
            display.remove();
        }
        tile.getPersistentDataContainer().remove(labelKey);
        tile.update(true);
    }

    private TextDisplay findLabel(TileState tile, String id) {
        try {
            UUID uuid = UUID.fromString(id);
            return tile.getWorld().getEntity(uuid) instanceof TextDisplay display && !display.isDead() ? display : null;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private void syncListeners() {
        advanceCompletedSongs();
        playing.values().forEach(this::emitMusicNote);

        Map<UUID, BlockKey> desired = new HashMap<>();
        for (Player player : org.bukkit.Bukkit.getOnlinePlayers()) {
            JukeboxPlayback closest = null;
            double closestDistance = Double.MAX_VALUE;
            for (JukeboxPlayback session : playing.values()) {
                if (!player.getWorld().equals(session.location.getWorld())) continue;
                double distance = player.getLocation().distanceSquared(session.location);
                if (distance <= (double) session.radius * session.radius && distance < closestDistance) {
                    closest = session;
                    closestDistance = distance;
                }
            }
            if (closest != null) desired.put(player.getUniqueId(), closest.key);
        }

        for (Map.Entry<UUID, BlockKey> active : new HashMap<>(listeners).entrySet()) {
            if (!active.getValue().equals(desired.get(active.getKey()))) {
                Player player = org.bukkit.Bukkit.getPlayer(active.getKey());
                if (player != null) playback.stop(player);
                listeners.remove(active.getKey());
            }
        }
        for (Map.Entry<UUID, BlockKey> entry : desired.entrySet()) {
            if (listeners.containsKey(entry.getKey())) continue;
            Player player = org.bukkit.Bukkit.getPlayer(entry.getKey());
            JukeboxPlayback session = playing.get(entry.getValue());
            if (player == null || session == null) continue;
            playback.playAt(player, session.song, settings.volume(player), session.elapsedMicros());
            settings.recordPlayed(player, session.song.id());
            listeners.put(entry.getKey(), entry.getValue());
        }
    }

    private void advanceCompletedSongs() {
        for (JukeboxPlayback session : new java.util.ArrayList<>(playing.values())) {
            if (!playback.durationMicros(session.song).map(duration -> session.elapsedMicros() >= duration).orElse(false)) continue;
            Song next = session.queue.pollFirst();
            if (next == null && session.looping) next = session.song;
            if (next == null) {
                playing.remove(session.key);
                stopListenersFor(session.key);
                continue;
            }
            stopListenersFor(session.key);
            playing.put(session.key, new JukeboxPlayback(session.key, session.location, session.radius, next, session.looping, System.nanoTime()));
        }
    }

    private void announceNowPlaying(BlockKey key) {
        JukeboxPlayback session = playing.get(key);
        if (session == null) return;
        Component message = Component.text("♫ ", NamedTextColor.RED)
            .append(Component.text("JUKEBOX", NamedTextColor.RED))
            .append(Component.text(" • Now Playing: ", NamedTextColor.GRAY))
            .append(Component.text(session.song.displayName(), NamedTextColor.WHITE));
        for (Map.Entry<UUID, BlockKey> listener : listeners.entrySet()) {
            if (!listener.getValue().equals(key)) continue;
            Player player = org.bukkit.Bukkit.getPlayer(listener.getKey());
            if (player != null) player.sendActionBar(message);
        }
    }

    private void emitMusicNote(JukeboxPlayback session) {
        Location origin = session.location.clone().add(0.5D, 1.18D, 0.5D);
        session.location.getWorld().spawnParticle(Particle.NOTE, origin, 1, 0.35D, 0.22D, 0.35D, Math.random());
    }

    private void stopPlayback(Location location) {
        BlockKey key = BlockKey.of(location);
        playing.remove(key);
        stopListenersFor(key);
    }

    private void stopListenersFor(BlockKey key) {
        for (Map.Entry<UUID, BlockKey> entry : new HashMap<>(listeners).entrySet()) {
            if (!entry.getValue().equals(key)) continue;
            Player player = org.bukkit.Bukkit.getPlayer(entry.getKey());
            if (player != null) playback.stop(player);
            listeners.remove(entry.getKey());
        }
    }

    private record BlockKey(UUID worldId, int x, int y, int z) {
        private static BlockKey of(Location location) {
            return new BlockKey(location.getWorld().getUID(), location.getBlockX(), location.getBlockY(), location.getBlockZ());
        }
    }

    private static final class JukeboxPlayback {
        private final BlockKey key;
        private final Location location;
        private final int radius;
        private final Song song;
        private boolean looping;
        private final ArrayDeque<Song> queue = new ArrayDeque<>();
        private final long startedNanos;

        private JukeboxPlayback(BlockKey key, Location location, int radius, Song song, boolean looping, long startedNanos) {
            this.key = key;
            this.location = location;
            this.radius = radius;
            this.song = song;
            this.looping = looping;
            this.startedNanos = startedNanos;
        }

        private long elapsedMicros() {
            return Math.max(0L, (System.nanoTime() - startedNanos) / 1_000L);
        }
    }
}
