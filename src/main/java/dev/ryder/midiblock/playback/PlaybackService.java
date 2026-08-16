package dev.ryder.midiblock.playback;

import dev.ryder.midiblock.library.Song;
import dev.ryder.midiblock.arrangement.ArrangementCompiler;
import dev.ryder.midiblock.arrangement.ArrangementDiagnostics;
import dev.ryder.midiblock.arrangement.NoteBlockArrangement;
import dev.ryder.midiblock.arrangement.PlayableNote;
import dev.ryder.midiblock.enhanced.EnhancedAudioService;
import dev.ryder.midiblock.midi.CompiledSong;
import dev.ryder.midiblock.midi.MidiCompiler;
import dev.ryder.midiblock.midi.MidiControlEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import javax.sound.midi.InvalidMidiDataException;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** Per-player monotonic-clock playback. Bukkit calls always remain on the server thread. */
public final class PlaybackService {
    private static final long NANOS_PER_MICRO = 1_000L;
    private final Plugin plugin;
    private final MidiCompiler compiler;
    private final Executor compilerExecutor;
    private final long trailingSilenceMicros;
    private final int maxVoices;
    private final float pitchBendRange;
    private final long sustainRefreshMicros;
    private final EnhancedAudioService enhancedAudio;
    private final int maxEventsPerTick;
    private final long lateDropThresholdMicros;
    private final int lateDropVelocityThreshold;
    private final ArrangementCompiler arrangementCompiler = new ArrangementCompiler();
    private final Map<UUID, PlaybackSession> sessions = new HashMap<>();
    private final Map<UUID, Long> requests = new HashMap<>();
    private final Map<UUID, ArrayDeque<Song>> queues = new HashMap<>();
    private long requestSequence;

    public PlaybackService(Plugin plugin, MidiCompiler compiler, Executor compilerExecutor, long trailingSilenceMicros, int maxVoices, float pitchBendRange, long sustainRefreshMicros, int maxEventsPerTick, long lateDropThresholdMicros, int lateDropVelocityThreshold, EnhancedAudioService enhancedAudio) {
        this.plugin = plugin;
        this.compiler = compiler;
        this.compilerExecutor = compilerExecutor;
        this.trailingSilenceMicros = trailingSilenceMicros;
        this.maxVoices = maxVoices;
        this.pitchBendRange = pitchBendRange;
        this.sustainRefreshMicros = sustainRefreshMicros;
        this.enhancedAudio = enhancedAudio;
        this.maxEventsPerTick = maxEventsPerTick;
        this.lateDropThresholdMicros = lateDropThresholdMicros;
        this.lateDropVelocityThreshold = lateDropVelocityThreshold;
    }

    /** Starts immediately when compiled, otherwise queues a background compilation. */
    public StartResult play(Player player, Song song, float volume) {
        queues.remove(player.getUniqueId());
        return requestPlay(player, song, volume);
    }

    /** Adds a song to the player's in-memory listening queue. */
    public void queue(Player player, Song song) {
        queues.computeIfAbsent(player.getUniqueId(), ignored -> new ArrayDeque<>()).addLast(song);
    }

    /** Advances to the queued song, or stops if the queue is empty. */
    public void skip(Player player) {
        Song next = pollQueue(player.getUniqueId());
        float volume = current(player) == null ? 0.70F : current(player).volume();
        stop(player);
        if (next != null) requestPlay(player, next, volume);
    }

    private StartResult requestPlay(Player player, Song song, float volume) {
        stop(player);
        long requestId = ++requestSequence;
        requests.put(player.getUniqueId(), requestId);
        return compiler.ready(song).flatMap(compiled -> arrangementCompiler.ready(song, compiled)).map(arrangement -> {
            start(player, song, arrangement, volume, requestId, 0L);
            return StartResult.STARTED;
        }).orElseGet(() -> {
            CompletableFuture.supplyAsync(() -> prepare(song), compilerExecutor).whenComplete((prepared, error) ->
                Bukkit.getScheduler().runTask(plugin, () -> completeCompilation(player.getUniqueId(), song, volume, requestId, prepared, error))
            );
            return StartResult.COMPILING;
        });
    }

    public boolean pause(Player player) {
        PlaybackSession session = sessions.get(player.getUniqueId());
        if (session == null || session.paused) return false;
        session.positionMicros = elapsedMicros(session);
        session.paused = true;
        cancelTask(session);
        arrangementCompiler.stopNativeSounds(player);
        enhancedAudio.stopSounds(player);
        return true;
    }

    public boolean resume(Player player) {
        PlaybackSession session = sessions.get(player.getUniqueId());
        if (session == null || !session.paused) return false;
        start(player, session.song, session.arrangement, session.volume, session.requestId, session.positionMicros);
        return true;
    }

    public void stop(Player player) {
        UUID playerId = player.getUniqueId();
        requests.remove(playerId);
        PlaybackSession session = sessions.remove(playerId);
        if (session != null) cancelTask(session);
        arrangementCompiler.stopNativeSounds(player);
        enhancedAudio.stopSounds(player);
    }

    public PlayingSong current(Player player) {
        PlaybackSession session = sessions.get(player.getUniqueId());
        if (session == null) return null;
        long position = session.paused ? session.positionMicros : elapsedMicros(session);
        return new PlayingSong(session.song, session.volume, session.paused, position, session.arrangement.source().durationMicros(), session.droppedNotes);
    }

    /** Applies a new per-player master volume immediately to future emitted notes. */
    public void setVolume(Player player, float volume) {
        PlaybackSession session = sessions.get(player.getUniqueId());
        if (session != null) session.volume = Math.clamp(volume, 0.0F, 1.0F);
    }

    public Optional<ArrangementDiagnostics> diagnostics(Song song) {
        return compiler.ready(song).flatMap(compiled -> arrangementCompiler.ready(song, compiled)).map(NoteBlockArrangement::diagnostics);
    }

    public void stopAll() {
        for (UUID playerId : sessions.keySet().toArray(UUID[]::new)) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null) stop(player);
            else {
                PlaybackSession session = sessions.remove(playerId);
                if (session != null) cancelTask(session);
            }
        }
        requests.clear();
        queues.clear();
    }

    public void warm(Song song) {
        CompletableFuture.runAsync(() -> prepare(song), compilerExecutor).exceptionally(error -> {
            plugin.getLogger().warning("Could not prepare " + song.id() + ": " + error.getMessage());
            return null;
        });
    }

    private PreparedSong prepare(Song song) {
        try {
            CompiledSong compiled = compiler.getOrCompile(song);
            return new PreparedSong(compiled, arrangementCompiler.getOrCompile(song, compiled));
        } catch (IOException | InvalidMidiDataException exception) {
            throw new IllegalStateException("Invalid MIDI file: " + song.id(), exception);
        }
    }

    private void completeCompilation(UUID playerId, Song song, float volume, long requestId, PreparedSong prepared, Throwable error) {
        if (!Long.valueOf(requestId).equals(requests.get(playerId))) return;
        Player player = Bukkit.getPlayer(playerId);
        if (player == null || !player.isOnline()) return;
        if (error != null) {
            requests.remove(playerId);
            player.sendMessage("§cMidiBlock could not compile " + song.displayName() + ". Check the server log.");
            plugin.getLogger().warning("Could not compile " + song.id() + ": " + error.getMessage());
            return;
        }
        start(player, song, prepared.arrangement, volume, requestId, 0L);
        player.sendMessage("§aNow playing: §f" + prepared.compiled.title());
    }

    private void start(Player player, Song song, NoteBlockArrangement arrangement, float volume, long requestId, long positionMicros) {
        PlaybackSession previous = sessions.remove(player.getUniqueId());
        if (previous != null) cancelTask(previous);
        PlaybackSession session = new PlaybackSession(song, arrangement, volume, requestId, positionMicros);
        session.nextNote = firstNoteAtOrAfter(arrangement, positionMicros);
        initializeControllers(session, positionMicros);
        session.startNanos = System.nanoTime() - positionMicros * NANOS_PER_MICRO;
        sessions.put(player.getUniqueId(), session);
        session.task = Bukkit.getScheduler().runTaskTimer(plugin, () -> tick(player.getUniqueId(), session), 1L, 1L);
    }

    private void tick(UUID playerId, PlaybackSession expected) {
        PlaybackSession session = sessions.get(playerId);
        Player player = Bukkit.getPlayer(playerId);
        if (session != expected || player == null || !player.isOnline()) {
            if (session == expected) sessions.remove(playerId);
            cancelTask(expected);
            return;
        }
        long elapsed = elapsedMicros(session);
        cleanReleasedNotes(session, elapsed);
        processDueEvents(player, session, elapsed);
        refreshSustainedNotes(player, session, elapsed);
        if (elapsed >= session.arrangement.source().durationMicros() + trailingSilenceMicros) advanceQueue(player, session.volume);
    }

    private void advanceQueue(Player player, float volume) {
        Song next = pollQueue(player.getUniqueId());
        stop(player);
        if (next != null) requestPlay(player, next, volume);
    }

    private Song pollQueue(UUID playerId) {
        ArrayDeque<Song> queue = queues.get(playerId);
        if (queue == null) return null;
        Song next = queue.pollFirst();
        if (queue.isEmpty()) queues.remove(playerId);
        return next;
    }

    private void processDueEvents(Player player, PlaybackSession session, long elapsed) {
        List<MidiControlEvent> controls = session.arrangement.source().controls();
        List<PlayableNote> notes = session.arrangement.notes();
        int processed = 0;
        while (processed++ < maxEventsPerTick) {
            long controlTime = session.nextControl < controls.size() ? controls.get(session.nextControl).timeMicros() : Long.MAX_VALUE;
            long noteTime = session.nextNote < notes.size() ? notes.get(session.nextNote).timeMicros() : Long.MAX_VALUE;
            if (Math.min(controlTime, noteTime) > elapsed) return;
            if (controlTime <= noteTime) {
                applyControl(session, controls.get(session.nextControl++));
            } else {
                PlayableNote note = notes.get(session.nextNote++);
                if (elapsed - note.timeMicros() > lateDropThresholdMicros && !note.percussion() && note.velocity() < lateDropVelocityThreshold) {
                    session.droppedNotes++;
                } else {
                    emitNote(player, session, note, 1.0F);
                }
            }
        }
    }

    private void emitNote(Player player, PlaybackSession session, PlayableNote note, float sustainMultiplier) {
        cleanReleasedNotes(session, note.timeMicros());
        ActiveNote weakest = session.activeNotes.stream().min(Comparator.comparingInt(active -> priority(active.note))).orElse(null);
        if (session.activeNotes.size() >= maxVoices && (weakest == null || priority(note) <= priority(weakest.note))) {
            session.droppedNotes++;
            return;
        }
        if (weakest != null && session.activeNotes.size() >= maxVoices) session.activeNotes.remove(weakest);
        renderNativeNote(player, session, note, sustainMultiplier);
        session.activeNotes.add(new ActiveNote(note, note.endTimeMicros() + sustainRefreshMicros));
    }

    private void renderNativeNote(Player player, PlaybackSession session, PlayableNote note, float sustainMultiplier) {
        float velocity = (float) Math.pow(note.velocity() / 127.0D, 0.7D);
        float channelGain = session.channelVolume[note.channel()] * session.expression[note.channel()];
        float pitch = note.percussion() ? note.pitch() : (float) (note.pitch() * Math.pow(2.0D, bendSemitones(session, note.channel()) / 12.0D));
        float finalVolume = session.volume * velocity * channelGain * sustainMultiplier;
        float finalPitch = Math.clamp(pitch, 0.5F, 2.0F);
        if (enhancedAudio.isEnabledFor(player)) {
            player.playSound(player.getLocation(), enhancedAudio.soundId(note), finalVolume, finalPitch);
        } else {
            player.playSound(player.getLocation(), note.sound(), finalVolume, finalPitch);
        }
    }

    private void initializeControllers(PlaybackSession session, long positionMicros) {
        List<MidiControlEvent> controls = session.arrangement.source().controls();
        while (session.nextControl < controls.size() && controls.get(session.nextControl).timeMicros() <= positionMicros) {
            applyControl(session, controls.get(session.nextControl++));
        }
    }

    private void applyControl(PlaybackSession session, MidiControlEvent control) {
        switch (control.type()) {
            case PITCH_BEND -> session.pitchBend[control.channel()] = control.value();
            case PITCH_BEND_RANGE -> session.pitchBendRange[control.channel()] = control.value();
            case CHANNEL_VOLUME -> session.channelVolume[control.channel()] = control.value() / 127.0F;
            case EXPRESSION -> session.expression[control.channel()] = control.value() / 127.0F;
            case SUSTAIN -> session.sustain[control.channel()] = control.value() >= 64;
        }
    }

    private void cleanReleasedNotes(PlaybackSession session, long elapsed) {
        session.activeNotes.removeIf(active -> active.note.endTimeMicros() <= elapsed && !session.sustain[active.note.channel()]);
    }

    private void refreshSustainedNotes(Player player, PlaybackSession session, long elapsed) {
        for (ActiveNote active : session.activeNotes) {
            if (session.sustain[active.note.channel()] && active.note.endTimeMicros() <= elapsed && elapsed >= active.nextRefreshMicros) {
                renderNativeNote(player, session, active.note, 0.35F);
                active.nextRefreshMicros = elapsed + sustainRefreshMicros;
            }
        }
    }

    private int priority(PlayableNote note) {
        return note.velocity() + (note.percussion() ? 128 : 0);
    }

    private float bendSemitones(PlaybackSession session, int channel) {
        return ((session.pitchBend[channel] - 8192) / 8192.0F) * session.pitchBendRange[channel];
    }

    private long elapsedMicros(PlaybackSession session) {
        return Math.max(session.positionMicros, (System.nanoTime() - session.startNanos) / NANOS_PER_MICRO);
    }

    private int firstNoteAtOrAfter(NoteBlockArrangement arrangement, long positionMicros) {
        int low = 0;
        int high = arrangement.notes().size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (arrangement.notes().get(middle).timeMicros() < positionMicros) low = middle + 1;
            else high = middle;
        }
        return low;
    }

    private void cancelTask(PlaybackSession session) {
        if (session.task != null) session.task.cancel();
    }

    public enum StartResult { STARTED, COMPILING }

    public record PlayingSong(Song song, float volume, boolean paused, long positionMicros, long durationMicros, int droppedNotes) {
    }

    private final class PlaybackSession {
        private final Song song;
        private final NoteBlockArrangement arrangement;
        private float volume;
        private final long requestId;
        private long startNanos;
        private long positionMicros;
        private int nextNote;
        private int nextControl;
        private boolean paused;
        private BukkitTask task;
        private int droppedNotes;
        private final int[] pitchBend = new int[16];
        private final float[] pitchBendRange = new float[16];
        private final float[] channelVolume = new float[16];
        private final float[] expression = new float[16];
        private final boolean[] sustain = new boolean[16];
        private final List<ActiveNote> activeNotes = new ArrayList<>();

        private PlaybackSession(Song song, NoteBlockArrangement arrangement, float volume, long requestId, long positionMicros) {
            this.song = song;
            this.arrangement = arrangement;
            this.volume = volume;
            this.requestId = requestId;
            this.positionMicros = positionMicros;
            java.util.Arrays.fill(pitchBend, 8192);
            java.util.Arrays.fill(pitchBendRange, PlaybackService.this.pitchBendRange);
            java.util.Arrays.fill(channelVolume, 100.0F / 127.0F);
            java.util.Arrays.fill(expression, 1.0F);
        }
    }

    private static final class ActiveNote {
        private final PlayableNote note;
        private long nextRefreshMicros;

        private ActiveNote(PlayableNote note, long nextRefreshMicros) {
            this.note = note;
            this.nextRefreshMicros = nextRefreshMicros;
        }
    }

    private record PreparedSong(CompiledSong compiled, NoteBlockArrangement arrangement) {
    }
}
