package dev.ryder.midiblock;

import dev.ryder.midiblock.command.MusicCommand;
import dev.ryder.midiblock.enhanced.EnhancedAudioService;
import dev.ryder.midiblock.library.SongLibrary;
import dev.ryder.midiblock.midi.MidiCompiler;
import dev.ryder.midiblock.playback.PlaybackService;
import dev.ryder.midiblock.profile.PlayerSettingsStore;
import dev.ryder.midiblock.ui.MusicMenu;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Plugin entry point. Keep this class limited to lifecycle wiring. */
public final class MidiBlockPlugin extends JavaPlugin {
    private SongLibrary songLibrary;
    private PlaybackService playbackService;
    private MidiCompiler midiCompiler;
    private ExecutorService compilerExecutor;
    private PlayerSettingsStore playerSettings;
    private EnhancedAudioService enhancedAudio;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        this.songLibrary = new SongLibrary(getDataFolder().toPath(), getLogger());
        this.songLibrary.reload();
        this.midiCompiler = new MidiCompiler(getDataFolder().toPath(), getLogger());
        this.compilerExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "MidiBlock compiler");
            thread.setDaemon(true);
            return thread;
        });
        long trailingSilenceMicros = getConfig().getLong("playback.trailing-silence-millis", 1_000L) * 1_000L;
        int maxVoices = Math.max(1, getConfig().getInt("playback.max-voices-per-player", 48));
        float bendRange = (float) getConfig().getDouble("playback.pitch-bend-range-semitones", 2.0D);
        long sustainRefreshMicros = getConfig().getLong("playback.sustain-refresh-millis", 350L) * 1_000L;
        int maxEventsPerTick = Math.max(1, getConfig().getInt("playback.max-events-per-tick", 512));
        this.enhancedAudio = new EnhancedAudioService(this);
        this.playbackService = new PlaybackService(this, midiCompiler, compilerExecutor, trailingSilenceMicros, maxVoices, bendRange, sustainRefreshMicros, maxEventsPerTick, enhancedAudio);
        this.playerSettings = new PlayerSettingsStore(getDataFolder(), (float) getConfig().getDouble("playback.default-volume", 0.70D));
        warmLibrary();

        MusicMenu menu = new MusicMenu(this, songLibrary, playbackService, playerSettings);
        getServer().getPluginManager().registerEvents(menu, this);
        getServer().getPluginManager().registerEvents(enhancedAudio, this);

        PluginCommand command = Objects.requireNonNull(getCommand("music"), "music command missing from plugin.yml");
        MusicCommand musicCommand = new MusicCommand(this, songLibrary, playbackService, playerSettings, enhancedAudio, menu);
        command.setExecutor(musicCommand);
        command.setTabCompleter(musicCommand);

        getLogger().info("MidiBlock enabled with " + songLibrary.songs().size() + " MIDI song(s).");
    }

    @Override
    public void onDisable() {
        if (playbackService != null) {
            playbackService.stopAll();
        }
        if (compilerExecutor != null) {
            compilerExecutor.shutdownNow();
        }
    }

    /** Reload source discovery and compile songs off the server thread. */
    public void reloadLibrary() {
        reloadConfig();
        songLibrary.reload();
        midiCompiler.clearMemory();
        warmLibrary();
    }

    private void warmLibrary() {
        for (var song : songLibrary.songs()) {
            playbackService.warm(song);
        }
    }
}
