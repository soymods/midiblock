package dev.ryder.midibox;

import dev.ryder.midibox.command.MusicCommand;
import dev.ryder.midibox.enhanced.EnhancedAudioService;
import dev.ryder.midibox.library.SongLibrary;
import dev.ryder.midibox.jukebox.JukeboxService;
import dev.ryder.midibox.midi.MidiCompiler;
import dev.ryder.midibox.playback.PlaybackService;
import dev.ryder.midibox.orchestra.OrchestraProfile;
import dev.ryder.midibox.profile.PlayerSettingsStore;
import dev.ryder.midibox.ui.MusicMenu;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Plugin entry point. Keep this class limited to lifecycle wiring. */
public final class MidiBoxPlugin extends JavaPlugin {
    private SongLibrary songLibrary;
    private PlaybackService playbackService;
    private MidiCompiler midiCompiler;
    private ExecutorService compilerExecutor;
    private PlayerSettingsStore playerSettings;
    private EnhancedAudioService enhancedAudio;
    private OrchestraProfile orchestra;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        this.songLibrary = new SongLibrary(getDataFolder().toPath(), getLogger());
        this.songLibrary.reload();
        this.midiCompiler = new MidiCompiler(getDataFolder().toPath(), getLogger());
        this.compilerExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "MidiBox compiler");
            thread.setDaemon(true);
            return thread;
        });
        long trailingSilenceMicros = getConfig().getLong("playback.trailing-silence-millis", 1_000L) * 1_000L;
        int maxVoices = Math.max(1, getConfig().getInt("playback.max-voices-per-player", 48));
        float bendRange = (float) getConfig().getDouble("playback.pitch-bend-range-semitones", 2.0D);
        long sustainRefreshMicros = getConfig().getLong("playback.sustain-refresh-millis", 350L) * 1_000L;
        int maxEventsPerTick = Math.max(1, getConfig().getInt("playback.max-events-per-tick", 512));
        long lateDropThresholdMicros = Math.max(0L, getConfig().getLong("playback.late-drop-threshold-millis", 150L) * 1_000L);
        int lateDropVelocityThreshold = Math.clamp(getConfig().getInt("playback.late-drop-velocity-threshold", 72), 0, 127);
        long preRollMicros = Math.max(0L, getConfig().getLong("playback.timing.pre-roll-millis", 200L) * 1_000L);
        long outboundLookaheadMicros = Math.max(0L, getConfig().getLong("playback.timing.outbound-lookahead-millis", 50L) * 1_000L);
        boolean pingCompensation = getConfig().getBoolean("playback.timing.ping-compensation", true);
        long maxPingCompensationMicros = Math.max(0L, getConfig().getLong("playback.timing.max-ping-compensation-millis", 150L) * 1_000L);
        boolean adaptiveQuality = getConfig().getBoolean("playback.timing.adaptive-quality", true);
        long adaptiveLagThresholdMicros = Math.max(1L, getConfig().getLong("playback.timing.adaptive-lag-threshold-millis", 100L) * 1_000L);
        saveResource("sound-profiles/1.21.4.yml", false);
        this.orchestra = OrchestraProfile.load(new java.io.File(getDataFolder(), "sound-profiles/1.21.4.yml"));
        this.enhancedAudio = new EnhancedAudioService(this);
        this.playbackService = new PlaybackService(this, midiCompiler, compilerExecutor, trailingSilenceMicros, maxVoices, bendRange, sustainRefreshMicros, maxEventsPerTick, lateDropThresholdMicros, lateDropVelocityThreshold, preRollMicros, outboundLookaheadMicros, pingCompensation, maxPingCompensationMicros, adaptiveQuality, adaptiveLagThresholdMicros, enhancedAudio, orchestra);
        this.playerSettings = new PlayerSettingsStore(getDataFolder(), (float) getConfig().getDouble("playback.default-volume", 0.70D));
        warmLibrary();

        MusicMenu menu = new MusicMenu(this, songLibrary, playbackService, playerSettings);
        JukeboxService jukeboxes = new JukeboxService(this, menu, playbackService, playerSettings);
        menu.setJukeboxes(jukeboxes);
        getServer().getPluginManager().registerEvents(menu, this);
        getServer().getPluginManager().registerEvents(jukeboxes, this);
        getServer().getPluginManager().registerEvents(enhancedAudio, this);

        PluginCommand command = Objects.requireNonNull(getCommand("music"), "music command missing from plugin.yml");
        MusicCommand musicCommand = new MusicCommand(this, songLibrary, playbackService, playerSettings, enhancedAudio, orchestra, jukeboxes, menu);
        command.setExecutor(musicCommand);
        command.setTabCompleter(musicCommand);

        getLogger().info("MidiBox enabled with " + songLibrary.songs().size() + " MIDI song(s).");
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
