package dev.ryder.midiblock.ui;

import dev.ryder.midiblock.MidiBlockPlugin;
import dev.ryder.midiblock.jukebox.JukeboxService;
import dev.ryder.midiblock.library.Song;
import dev.ryder.midiblock.library.SongLibrary;
import dev.ryder.midiblock.playback.PlaybackService;
import dev.ryder.midiblock.profile.PlayerSettingsStore;
import org.bukkit.ChatColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** A purpose-built, click-wheel-inspired inventory player. */
public final class MusicMenu implements Listener {
    private static final int SIZE = 54;
    private static final int PAGE_SIZE = 45;
    private final MidiBlockPlugin plugin;
    private final SongLibrary library;
    private final PlaybackService playback;
    private final PlayerSettingsStore settings;
    private final Map<java.util.UUID, JukeboxContext> jukeboxContexts = new HashMap<>();
    private JukeboxService jukeboxes;

    public MusicMenu(MidiBlockPlugin plugin, SongLibrary library, PlaybackService playback, PlayerSettingsStore settings) {
        this.plugin = plugin;
        this.library = library;
        this.playback = playback;
        this.settings = settings;
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::refreshOpenPlayers, 10L, 10L);
    }

    public void open(Player player, int ignored) {
        jukeboxContexts.remove(player.getUniqueId());
        openHome(player);
    }

    public void setJukeboxes(JukeboxService jukeboxes) {
        this.jukeboxes = jukeboxes;
    }

    public void openJukebox(Player player, Location location, int radius) {
        jukeboxContexts.put(player.getUniqueId(), new JukeboxContext(location, radius));
        openLibrary(player, 0);
    }

    private void openHome(Player player) {
        Inventory inventory = inventory(Page.HOME, 0);
        inventory.setItem(20, item(Material.JUKEBOX, ChatColor.GREEN + "Now Playing", List.of(ChatColor.GRAY + "See song progress and controls")));
        inventory.setItem(22, item(Material.MUSIC_DISC_CAT, ChatColor.WHITE + "Music", List.of(ChatColor.GRAY + String.valueOf(library.songs().size()) + " songs in your library")));
        inventory.setItem(24, item(Material.COMPARATOR, ChatColor.AQUA + "Settings", List.of(ChatColor.GRAY + "Volume and player preferences")));
        inventory.setItem(4, item(Material.JUKEBOX, ChatColor.RED + "" + ChatColor.BOLD + "JUKEBOX", List.of(ChatColor.GRAY + "Get a permanent public music player")));
        inventory.setItem(49, item(Material.COMPASS, ChatColor.GOLD + "MENU"));
        player.openInventory(inventory);
    }

    private void openLibrary(Player player, int page) {
        int safePage = Math.max(0, page);
        Inventory inventory = inventory(Page.LIBRARY, safePage);
        List<Song> songs = new ArrayList<>(library.songs());
        int start = safePage * PAGE_SIZE;
        for (int slot = 0; slot < PAGE_SIZE && start + slot < songs.size(); slot++) {
            Song song = songs.get(start + slot);
            inventory.setItem(slot, item(Material.MUSIC_DISC_CAT, ChatColor.WHITE + song.displayName(), List.of(
                ChatColor.GRAY + "Click to play",
                ChatColor.DARK_GRAY + "Shift-click to queue",
                ChatColor.DARK_GRAY + song.id()
            )));
        }
        inventory.setItem(45, item(Material.ARROW, ChatColor.GRAY + "◀ Previous"));
        inventory.setItem(47, item(Material.COMPASS, ChatColor.AQUA + "MENU"));
        inventory.setItem(49, item(Material.JUKEBOX, ChatColor.GREEN + "Now Playing"));
        inventory.setItem(51, item(Material.COMPARATOR, ChatColor.AQUA + "Settings"));
        inventory.setItem(53, item(Material.ARROW, ChatColor.GRAY + "Next ▶"));
        player.openInventory(inventory);
    }

    private void openNowPlaying(Player player) {
        Inventory inventory = inventory(Page.NOW_PLAYING, 0);
        renderNowPlaying(inventory, player);
        player.openInventory(inventory);
    }

    private void renderNowPlaying(Inventory inventory, Player player) {
        PlaybackService.PlayingSong current = playback.current(player);
        if (current == null) {
            inventory.setItem(22, item(Material.NOTE_BLOCK, ChatColor.YELLOW + "Nothing Playing", List.of(ChatColor.GRAY + "Choose Music to start listening.")));
        } else {
            inventory.setItem(4, item(Material.MUSIC_DISC_13, ChatColor.WHITE + current.song().displayName(), List.of(
                ChatColor.GRAY + formatTime(current.positionMicros()) + " / " + formatTime(current.durationMicros()),
                current.paused() ? ChatColor.YELLOW + "Paused" : ChatColor.GREEN + "Playing",
                current.droppedNotes() == 0 ? ChatColor.DARK_GRAY + "Native playback" : ChatColor.YELLOW + "Voice-protected notes: " + current.droppedNotes()
            )));
            drawProgress(inventory, current);
        }
        inventory.setItem(45, item(Material.ARROW, ChatColor.GRAY + "◀ Previous"));
        inventory.setItem(47, item(Material.MUSIC_DISC_CAT, ChatColor.AQUA + "Music"));
        inventory.setItem(49, item(Material.JUKEBOX, current != null && current.paused() ? ChatColor.GREEN + "Resume" : ChatColor.YELLOW + "Play / Pause"));
        inventory.setItem(51, item(Material.COMPARATOR, ChatColor.AQUA + "Settings"));
        inventory.setItem(53, item(Material.ARROW, ChatColor.GRAY + "Next ▶"));
    }

    private void refreshOpenPlayers() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            Inventory top = player.getOpenInventory().getTopInventory();
            if (top.getHolder() instanceof PlayerMenuHolder holder && holder.page == Page.NOW_PLAYING) {
                renderNowPlaying(top, player);
            }
        }
    }

    private void openSettings(Player player) {
        Inventory inventory = inventory(Page.SETTINGS, 0);
        int percent = Math.round(settings.volume(player) * 100.0F);
        inventory.setItem(20, item(Material.RED_STAINED_GLASS_PANE, ChatColor.RED + "− 10%"));
        inventory.setItem(22, item(Material.NOTE_BLOCK, ChatColor.WHITE + "Volume: " + percent + "%", List.of(ChatColor.GRAY + "Saved just for you")));
        inventory.setItem(24, item(Material.LIME_STAINED_GLASS_PANE, ChatColor.GREEN + "+ 10%"));
        inventory.setItem(49, item(Material.COMPASS, ChatColor.AQUA + "Back to Menu"));
        player.openInventory(inventory);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof PlayerMenuHolder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || event.getRawSlot() < 0 || event.getRawSlot() >= SIZE) return;
        switch (holder.page) {
            case HOME -> clickHome(player, event.getRawSlot());
            case LIBRARY -> clickLibrary(player, holder.pageNumber, event.getRawSlot(), event.isShiftClick());
            case NOW_PLAYING -> clickNowPlaying(player, event.getRawSlot());
            case SETTINGS -> clickSettings(player, event.getRawSlot());
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof PlayerMenuHolder) event.setCancelled(true);
    }

    private void clickHome(Player player, int slot) {
        if (slot == 20) openNowPlaying(player);
        else if (slot == 22) openLibrary(player, 0);
        else if (slot == 24) openSettings(player);
        else if (slot == 4 && jukeboxes != null) jukeboxes.give(player);
    }

    private void clickLibrary(Player player, int page, int slot, boolean shiftClick) {
        if (slot < PAGE_SIZE) {
            List<Song> songs = new ArrayList<>(library.songs());
            int index = page * PAGE_SIZE + slot;
            if (index >= songs.size()) return;
            Song song = songs.get(index);
            if (jukeboxContexts.containsKey(player.getUniqueId())) {
                play(player, song);
            } else if (shiftClick) {
                playback.queue(player, song);
                player.sendMessage(ChatColor.AQUA + "Queued: " + ChatColor.WHITE + song.displayName());
            } else play(player, song);
            return;
        }
        if (slot == 45 && page > 0) openLibrary(player, page - 1);
        else if (slot == 47) openHome(player);
        else if (slot == 49) openNowPlaying(player);
        else if (slot == 51) openSettings(player);
        else if (slot == 53 && (page + 1) * PAGE_SIZE < library.songs().size()) openLibrary(player, page + 1);
    }

    private void clickNowPlaying(Player player, int slot) {
        if (slot == 47) openLibrary(player, 0);
        else if (slot == 51) openSettings(player);
        else if (slot == 49) {
            PlaybackService.PlayingSong current = playback.current(player);
            if (current == null) openLibrary(player, 0);
            else if (current.paused()) playback.resume(player);
            else playback.pause(player);
            openNowPlaying(player);
        } else if (slot == 53) playback.skip(player);
    }

    private void clickSettings(Player player, int slot) {
        float volume = settings.volume(player);
        if (slot == 20) changeVolume(player, volume - 0.10F);
        else if (slot == 24) changeVolume(player, volume + 0.10F);
        else if (slot == 49) openHome(player);
    }

    private void changeVolume(Player player, float volume) {
        float saved = Math.clamp(volume, 0.0F, 1.0F);
        settings.setVolume(player, saved);
        playback.setVolume(player, saved);
        openSettings(player);
    }

    private void play(Player player, Song song) {
        JukeboxContext context = jukeboxContexts.get(player.getUniqueId());
        if (context != null) {
            List<Player> listeners = new ArrayList<>();
            for (Player target : Bukkit.getOnlinePlayers()) {
                if (target.getWorld().equals(context.location.getWorld()) && target.getLocation().distanceSquared(context.location) <= context.radius * context.radius) {
                    listeners.add(target);
                }
            }
            for (Player listener : listeners) {
                playback.play(listener, song, settings.volume(listener));
                settings.recordPlayed(listener, song.id());
            }
            player.sendMessage(ChatColor.RED + "JUKEBOX" + ChatColor.GRAY + " playing " + ChatColor.WHITE + song.displayName() + ChatColor.GRAY + " for " + listeners.size() + " listener(s) within " + context.radius + " blocks.");
            return;
        }
        PlaybackService.StartResult result = playback.play(player, song, settings.volume(player));
        player.sendMessage(result == PlaybackService.StartResult.STARTED
            ? ChatColor.GREEN + "Now playing: " + ChatColor.WHITE + song.displayName()
            : ChatColor.YELLOW + "Preparing: " + ChatColor.WHITE + song.displayName());
    }

    private void drawProgress(Inventory inventory, PlaybackService.PlayingSong current) {
        int filled = current.durationMicros() == 0 ? 0 : (int) Math.round(current.positionMicros() * 15.0D / current.durationMicros());
        for (int index = 0; index < 15; index++) {
            inventory.setItem(9 + index, item(index < filled ? Material.LIME_STAINED_GLASS_PANE : Material.GRAY_STAINED_GLASS_PANE, " "));
        }
    }

    private Inventory inventory(Page page, int pageNumber) {
        PlayerMenuHolder holder = new PlayerMenuHolder(page, pageNumber);
        String title = ChatColor.DARK_GRAY + plugin.getConfig().getString("ui.title", "MidiBlock") + ChatColor.GRAY + " • Music";
        Inventory inventory = plugin.getServer().createInventory(holder, SIZE, title);
        holder.inventory = inventory;
        return inventory;
    }

    private ItemStack item(Material material, String name) {
        return item(material, name, List.of());
    }

    private ItemStack item(Material material, String name, List<String> lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.setDisplayName(name);
        meta.setLore(lore);
        stack.setItemMeta(meta);
        return stack;
    }

    private String formatTime(long micros) {
        long seconds = micros / 1_000_000L;
        return "%d:%02d".formatted(seconds / 60L, seconds % 60L);
    }

    private enum Page { HOME, LIBRARY, NOW_PLAYING, SETTINGS }

    private record JukeboxContext(Location location, int radius) {
    }

    private static final class PlayerMenuHolder implements InventoryHolder {
        private final Page page;
        private final int pageNumber;
        private Inventory inventory;

        private PlayerMenuHolder(Page page, int pageNumber) {
            this.page = page;
            this.pageNumber = pageNumber;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
