package dev.ryder.midiblock.jukebox;

import dev.ryder.midiblock.MidiBlockPlugin;
import dev.ryder.midiblock.ui.MusicMenu;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.TileState;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;

/** Persistent world jukeboxes backed by tile-state PDC data. */
public final class JukeboxService implements Listener {
    private final NamespacedKey jukeboxKey;
    private final NamespacedKey itemKey;
    private final NamespacedKey radiusKey;
    private final MusicMenu menu;
    private final int defaultRadius;

    public JukeboxService(MidiBlockPlugin plugin, MusicMenu menu) {
        this.jukeboxKey = new NamespacedKey(plugin, "permanent_jukebox");
        this.itemKey = new NamespacedKey(plugin, "jukebox_item");
        this.radiusKey = new NamespacedKey(plugin, "jukebox_radius");
        this.menu = menu;
        this.defaultRadius = Math.clamp(plugin.getConfig().getInt("jukebox.default-radius", 50), 1, 512);
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
            state.update(true);
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null || !isPermanent(event.getClickedBlock().getState())) return;
        event.setCancelled(true);
        int radius = radius(event.getClickedBlock().getState());
        menu.openJukebox(event.getPlayer(), event.getClickedBlock().getLocation(), radius);
    }

    @EventHandler
    public void onBreak(BlockBreakEvent event) {
        if (!isPermanent(event.getBlock().getState())) return;
        if (event.getPlayer().getGameMode() != GameMode.CREATIVE) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(ChatColor.RED + "Only Creative-mode players can break a permanent JUKEBOX.");
        }
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
}
