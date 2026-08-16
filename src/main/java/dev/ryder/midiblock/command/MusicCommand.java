package dev.ryder.midiblock.command;

import dev.ryder.midiblock.MidiBlockPlugin;
import dev.ryder.midiblock.enhanced.EnhancedAudioService;
import dev.ryder.midiblock.library.Song;
import dev.ryder.midiblock.jukebox.JukeboxService;
import dev.ryder.midiblock.orchestra.OrchestraProfile;
import dev.ryder.midiblock.library.SongLibrary;
import dev.ryder.midiblock.playback.PlaybackService;
import dev.ryder.midiblock.profile.PlayerSettingsStore;
import dev.ryder.midiblock.ui.MusicMenu;
import org.bukkit.ChatColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class MusicCommand implements CommandExecutor, TabCompleter {
    private static final List<String> SUBCOMMANDS = List.of("list", "play", "stop", "pause", "resume", "volume", "jukebox", "orchestra", "pack", "history", "playlist", "analyze", "reload");

    private final MidiBlockPlugin plugin;
    private final SongLibrary library;
    private final PlaybackService playback;
    private final PlayerSettingsStore settings;
    private final EnhancedAudioService enhancedAudio;
    private final JukeboxService jukeboxes;
    private final OrchestraProfile orchestra;
    private final MusicMenu menu;

    public MusicCommand(MidiBlockPlugin plugin, SongLibrary library, PlaybackService playback, PlayerSettingsStore settings, EnhancedAudioService enhancedAudio, OrchestraProfile orchestra, JukeboxService jukeboxes, MusicMenu menu) {
        this.plugin = plugin;
        this.library = library;
        this.playback = playback;
        this.settings = settings;
        this.enhancedAudio = enhancedAudio;
        this.jukeboxes = jukeboxes;
        this.orchestra = orchestra;
        this.menu = menu;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "MidiBlock commands are player-only.");
            return true;
        }
        if (!player.hasPermission("midiblock.use")) {
            player.sendMessage(ChatColor.RED + "You do not have permission to use MidiBlock.");
            return true;
        }
        if (args.length == 0) {
            menu.open(player, 0);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "list" -> list(player);
            case "play" -> play(player, args);
            case "stop" -> {
                playback.stop(player);
                player.sendMessage(ChatColor.GRAY + "Playback stopped.");
            }
            case "pause" -> player.sendMessage(playback.pause(player) ? ChatColor.YELLOW + "Playback paused." : ChatColor.RED + "Nothing is playing.");
            case "resume" -> player.sendMessage(playback.resume(player) ? ChatColor.GREEN + "Playback resumed." : ChatColor.RED + "Nothing is paused.");
            case "volume" -> setVolume(player, args);
            case "pack" -> requestPack(player);
            case "jukebox" -> giveJukebox(player);
            case "orchestra" -> orchestra(player, args);
            case "history" -> history(player);
            case "playlist" -> playlist(player, args);
            case "analyze" -> analyze(player, args);
            case "reload" -> reload(player);
            default -> player.sendMessage(ChatColor.RED + "Unknown subcommand. Use /music.");
        }
        return true;
    }

    private void list(Player player) {
        if (library.songs().isEmpty()) {
            player.sendMessage(ChatColor.YELLOW + "No songs yet. Add .mid files to plugins/MidiBlock/songs.");
            return;
        }
        player.sendMessage(ChatColor.GOLD + "MidiBlock library (" + library.songs().size() + "):");
        library.songs().forEach(song -> player.sendMessage(ChatColor.GRAY + " • " + song.id()));
    }

    private void play(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(ChatColor.RED + "Usage: /music play <song>");
            return;
        }
        Audience audience = resolveAudience(player, args);
        if (audience == null) return;
        String songId = String.join(" ", Arrays.copyOfRange(args, 1, audience.songEnd));
        if (songId.isBlank()) {
            player.sendMessage(ChatColor.RED + "Usage: /music play <song> [global|radius <blocks>|@player]");
            return;
        }
        library.find(songId).ifPresentOrElse(song -> playFor(player, audience.players, song), () -> player.sendMessage(ChatColor.RED + "Song not found: " + songId));
    }

    private void playFor(Player initiator, List<Player> targets, Song song) {
        int started = 0;
        for (Player target : targets) {
            PlaybackService.StartResult result = playback.play(target, song, settings.volume(target));
            settings.recordPlayed(target, song.id());
            if (result == PlaybackService.StartResult.STARTED) started++;
        }
        initiator.sendMessage((started == targets.size() ? ChatColor.GREEN : ChatColor.YELLOW) + "Playing " + ChatColor.WHITE + song.displayName() + ChatColor.GRAY + " for " + targets.size() + " player(s).");
    }

    private Audience resolveAudience(Player initiator, String[] args) {
        int end = args.length;
        List<Player> targets = List.of(initiator);
        String last = args[args.length - 1];
        if (last.equalsIgnoreCase("global")) {
            if (!initiator.hasPermission("midiblock.play.global")) return denied(initiator, "global playback");
            targets = List.copyOf(Bukkit.getOnlinePlayers());
            end--;
        } else if (args.length >= 4 && args[args.length - 2].equalsIgnoreCase("radius")) {
            if (!initiator.hasPermission("midiblock.play.radius")) return denied(initiator, "radius playback");
            try {
                double radius = Double.parseDouble(last);
                if (radius <= 0 || radius > 512) throw new NumberFormatException();
                double squared = radius * radius;
                List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
                targets = online.stream().filter(target -> target.getWorld().equals(initiator.getWorld()) && target.getLocation().distanceSquared(initiator.getLocation()) <= squared).toList();
                end -= 2;
            } catch (NumberFormatException exception) {
                initiator.sendMessage(ChatColor.RED + "Radius must be between 0 and 512 blocks.");
                return null;
            }
        } else if (last.startsWith("@")) {
            if (!initiator.hasPermission("midiblock.play.others")) return denied(initiator, "player-targeted playback");
            Player target = Bukkit.getPlayerExact(last.substring(1));
            if (target == null) {
                initiator.sendMessage(ChatColor.RED + "That player is not online.");
                return null;
            }
            targets = List.of(target);
            end--;
        }
        return new Audience(targets, end);
    }

    private Audience denied(Player player, String action) {
        player.sendMessage(ChatColor.RED + "You do not have permission for " + action + ".");
        return null;
    }

    private void setVolume(Player player, String[] args) {
        if (args.length != 2) {
            player.sendMessage(ChatColor.RED + "Usage: /music volume <0-100>");
            return;
        }
        try {
            int volume = Integer.parseInt(args[1]);
            if (volume < 0 || volume > 100) throw new NumberFormatException();
            float normalized = volume / 100.0F;
            settings.setVolume(player, normalized);
            playback.setVolume(player, normalized);
            player.sendMessage(ChatColor.GRAY + "Saved volume: " + ChatColor.WHITE + volume + "%");
        } catch (NumberFormatException exception) {
            player.sendMessage(ChatColor.RED + "Volume must be a whole number from 0 to 100.");
        }
    }

    private void requestPack(Player player) {
        if (enhancedAudio.request(player)) player.sendMessage(ChatColor.AQUA + "Requested the MidiBlock enhanced audio pack.");
        else player.sendMessage(ChatColor.YELLOW + "Enhanced audio is not configured on this server; native note-block audio is active.");
    }

    private void giveJukebox(Player player) {
        if (!player.isOp()) {
            player.sendMessage(ChatColor.RED + "Only server operators can create permanent JUKEBOX players.");
            return;
        }
        jukeboxes.give(player);
    }

    private void orchestra(Player player, String[] args) {
        if (!player.hasPermission("midiblock.admin")) {
            player.sendMessage(ChatColor.RED + "You do not have permission to tune the orchestra.");
            return;
        }
        if (args.length < 2 || args[1].equalsIgnoreCase("list")) {
            player.sendMessage(ChatColor.GOLD + "Balanced orchestra: " + ChatColor.GRAY + orchestra.entries().stream().map(OrchestraProfile.Entry::id).reduce((a, b) -> a + ", " + b).orElse("empty"));
            player.sendMessage(ChatColor.DARK_GRAY + "Use /music orchestra audition <id> [midi-note]");
            return;
        }
        if (!args[1].equalsIgnoreCase("audition") || args.length < 3) {
            player.sendMessage(ChatColor.RED + "Usage: /music orchestra audition <id> [midi-note]");
            return;
        }
        int midi = 60;
        if (args.length >= 4) try {
            midi = Math.clamp(Integer.parseInt(args[3]), 0, 127);
        } catch (NumberFormatException exception) {
            player.sendMessage(ChatColor.RED + "MIDI note must be 0 through 127.");
            return;
        }
        final int note = midi;
        orchestra.find(args[2]).ifPresentOrElse(entry -> {
            float pitch = (float) Math.pow(2.0D, (note - entry.baseMidi()) / 12.0D);
            player.playSound(player.getLocation(), entry.soundKey(), 1.0F, Math.clamp(pitch, 0.5F, 2.0F));
            player.sendMessage(ChatColor.AQUA + "Audition: " + entry.id() + ChatColor.GRAY + " at MIDI " + note);
        }, () -> player.sendMessage(ChatColor.RED + "Unknown orchestra sound. Use /music orchestra list."));
    }

    private void analyze(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(ChatColor.RED + "Usage: /music analyze <song>");
            return;
        }
        String songId = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        library.find(songId).ifPresentOrElse(song -> playback.diagnostics(song).ifPresentOrElse(diagnostics -> {
            player.sendMessage(ChatColor.GOLD + "MidiBlock analysis: " + ChatColor.WHITE + song.displayName());
            player.sendMessage(ChatColor.GRAY + "Notes: " + diagnostics.noteCount() + " • percussion: " + diagnostics.percussionNotes());
            player.sendMessage(diagnostics.outOfRangeNotes() == 0
                ? ChatColor.GREEN + "All melodic notes fit the native range."
                : ChatColor.YELLOW + "Clamped notes: " + diagnostics.outOfRangeNotes() + " (consider a .song.yml override).");
            diagnostics.octaveShifts().forEach((part, shift) -> player.sendMessage(ChatColor.DARK_GRAY + part + ": " + (shift >= 0 ? "+" : "") + shift));
        }, () -> {
            playback.warm(song);
            player.sendMessage(ChatColor.YELLOW + "Analysis is being prepared; run the command again in a moment.");
        }), () -> player.sendMessage(ChatColor.RED + "Song not found: " + songId));
    }

    private void history(Player player) {
        List<String> history = settings.history(player);
        if (history.isEmpty()) {
            player.sendMessage(ChatColor.YELLOW + "You have not played any songs yet.");
            return;
        }
        player.sendMessage(ChatColor.GOLD + "Recently played:");
        history.forEach(song -> player.sendMessage(ChatColor.GRAY + " • " + song));
    }

    private void playlist(Player player, String[] args) {
        if (args.length < 2 || args[1].equalsIgnoreCase("list")) {
            List<dev.ryder.midiblock.profile.Playlist> playlists = settings.playlists(player);
            if (playlists.isEmpty()) player.sendMessage(ChatColor.YELLOW + "No playlists yet. Use /music playlist create <name>.");
            else playlists.forEach(playlist -> player.sendMessage(ChatColor.AQUA + playlist.name() + ChatColor.GRAY + " (" + playlist.songIds().size() + " songs)"));
            return;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "create" -> {
                if (args.length < 3) player.sendMessage(ChatColor.RED + "Usage: /music playlist create <name>");
                else try {
                    var created = settings.createPlaylist(player, String.join(" ", Arrays.copyOfRange(args, 2, args.length)));
                    player.sendMessage(ChatColor.GREEN + "Created playlist: " + created.name());
                } catch (IllegalArgumentException exception) {
                    player.sendMessage(ChatColor.RED + exception.getMessage());
                }
            }
            case "add" -> {
                if (args.length < 4) {
                    player.sendMessage(ChatColor.RED + "Usage: /music playlist add <playlist> <song>");
                    return;
                }
                String songId = String.join(" ", Arrays.copyOfRange(args, 3, args.length));
                library.find(songId).ifPresentOrElse(song -> {
                    if (settings.addToPlaylist(player, args[2], song.id())) player.sendMessage(ChatColor.GREEN + "Added " + song.displayName() + " to " + args[2] + ".");
                    else player.sendMessage(ChatColor.RED + "Playlist not found.");
                }, () -> player.sendMessage(ChatColor.RED + "Song not found: " + songId));
            }
            case "play" -> {
                if (args.length != 3) {
                    player.sendMessage(ChatColor.RED + "Usage: /music playlist play <playlist>");
                    return;
                }
                settings.playlist(player, args[2]).ifPresentOrElse(playlist -> playPlaylist(player, playlist), () -> player.sendMessage(ChatColor.RED + "Playlist not found."));
            }
            case "delete" -> {
                if (args.length != 3) player.sendMessage(ChatColor.RED + "Usage: /music playlist delete <playlist>");
                else player.sendMessage(settings.deletePlaylist(player, args[2]) ? ChatColor.GREEN + "Playlist deleted." : ChatColor.RED + "Playlist not found.");
            }
            default -> player.sendMessage(ChatColor.RED + "Use playlist list, create, add, play, or delete.");
        }
    }

    private void playPlaylist(Player player, dev.ryder.midiblock.profile.Playlist playlist) {
        List<Song> songs = playlist.songIds().stream().map(library::find).flatMap(java.util.Optional::stream).toList();
        if (songs.isEmpty()) {
            player.sendMessage(ChatColor.YELLOW + "That playlist has no available songs.");
            return;
        }
        playFor(player, List.of(player), songs.getFirst());
        songs.subList(1, songs.size()).forEach(song -> playback.queue(player, song));
        player.sendMessage(ChatColor.AQUA + "Queued playlist: " + playlist.name());
    }

    private void reload(Player player) {
        if (!player.hasPermission("midiblock.admin")) {
            player.sendMessage(ChatColor.RED + "You do not have permission to reload MidiBlock.");
            return;
        }
        plugin.reloadLibrary();
        player.sendMessage(ChatColor.GREEN + "Reloaded " + library.songs().size() + " song(s).");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> visible = sender.isOp() ? SUBCOMMANDS : SUBCOMMANDS.stream().filter(subcommand -> !subcommand.equals("jukebox")).toList();
            return filter(visible, args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("play")) {
            return filter(library.songs().stream().map(Song::id).toList(), args[1]);
        }
        return Collections.emptyList();
    }

    private List<String> filter(List<String> choices, String typed) {
        String prefix = typed.toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        for (String choice : choices) if (choice.toLowerCase(Locale.ROOT).startsWith(prefix)) matches.add(choice);
        return matches;
    }

    private record Audience(List<Player> players, int songEnd) {
    }
}
