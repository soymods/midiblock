package dev.ryder.midibox.arrangement;

import dev.ryder.midibox.library.Song;
import dev.ryder.midibox.midi.CompiledSong;
import dev.ryder.midibox.midi.MidiNote;
import dev.ryder.midibox.orchestra.OrchestraProfile;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Builds native sound plans and reads optional <song>.song.yml channel overrides. */
public final class ArrangementCompiler {
    private static final int LOWEST_ORCHESTRA_KEY = 24;
    private static final int HIGHEST_ORCHESTRA_KEY = 102;
    private final NativeInstrumentMapper instruments = new NativeInstrumentMapper();
    private final OrchestraProfile orchestra;
    private final Map<String, NoteBlockArrangement> memory = new ConcurrentHashMap<>();

    public ArrangementCompiler(OrchestraProfile orchestra) {
        this.orchestra = orchestra;
    }

    public NoteBlockArrangement getOrCompile(Song song, CompiledSong compiled) {
        String cacheKey = cacheKey(song, compiled);
        NoteBlockArrangement existing = memory.get(cacheKey);
        if (existing != null) return existing;
        NoteBlockArrangement arrangement = arrange(song, compiled, loadOverrides(song));
        memory.put(cacheKey, arrangement);
        return arrangement;
    }

    public Optional<NoteBlockArrangement> ready(Song song, CompiledSong compiled) {
        return Optional.ofNullable(memory.get(cacheKey(song, compiled)));
    }

    public void clearMemory() {
        memory.clear();
    }

    public void stopNativeSounds(org.bukkit.entity.Player player) {
        instruments.stopAll(player);
    }

    private NoteBlockArrangement arrange(Song song, CompiledSong compiled, Overrides overrides) {
        Map<Part, List<MidiNote>> parts = new HashMap<>();
        for (MidiNote note : compiled.notes()) {
            if (note.channel() != 9) parts.computeIfAbsent(new Part(note.channel(), note.program()), ignored -> new ArrayList<>()).add(note);
        }
        Map<Part, Integer> shifts = new HashMap<>();
        Map<Part, OrchestraProfile.Entry> orchestraSources = new HashMap<>();
        for (Map.Entry<Part, List<MidiNote>> entry : parts.entrySet()) {
            int baseTranspose = overrides.transpose + overrides.channel(entry.getKey().channel).transpose;
            shifts.put(entry.getKey(), bestOctaveShift(entry.getValue(), baseTranspose));
            orchestraSources.put(entry.getKey(), orchestra.bestFor(entry.getKey().program, entry.getValue(), shifts.get(entry.getKey())));
        }

        int outOfRange = 0;
        int percussion = 0;
        List<PlayableNote> rendered = new ArrayList<>(compiled.notes().size());
        for (MidiNote note : compiled.notes()) {
            ChannelOverride channelOverride = overrides.channel(note.channel());
            if (note.channel() == 9) {
                percussion++;
                rendered.add(new PlayableNote(note.timeMicros(), note.endTimeMicros(), note.channel(), note.key(), note.program(), instruments.percussion(note.key()), null, 1.0F, note.velocity(), true));
                continue;
            }
            int transpose = shifts.get(new Part(note.channel(), note.program()));
            int translatedKey = note.key() + transpose;
            if (translatedKey < LOWEST_ORCHESTRA_KEY || translatedKey > HIGHEST_ORCHESTRA_KEY) outOfRange++;
            Sound sound = channelOverride.sound != null ? channelOverride.sound : instruments.melodic(note.program());
            OrchestraProfile.Entry orchestraSource = orchestraSources.get(new Part(note.channel(), note.program()));
            float pitch = orchestraSource == null
                ? (float) Math.pow(2.0D, (translatedKey - 54) / 12.0D)
                : (float) Math.pow(2.0D, (translatedKey - orchestraSource.baseMidi()) / 12.0D);
            rendered.add(new PlayableNote(note.timeMicros(), note.endTimeMicros(), note.channel(), note.key(), note.program(), sound, orchestraSource == null ? null : orchestraSource.soundKey(), Math.clamp(pitch, 0.5F, 2.0F), note.velocity(), false));
        }
        Map<String, Integer> reportedShifts = new HashMap<>();
        shifts.forEach((part, shift) -> reportedShifts.put("channel " + (part.channel + 1) + ", program " + part.program, shift));
        return new NoteBlockArrangement(compiled, rendered, new ArrangementDiagnostics(rendered.size(), outOfRange, percussion, reportedShifts));
    }

    private int bestOctaveShift(List<MidiNote> notes, int baseTranspose) {
        int bestShift = baseTranspose;
        long bestCost = Long.MAX_VALUE;
        for (int octave = -48; octave <= 48; octave += 12) {
            int candidate = baseTranspose + octave;
            long cost = Math.abs(octave); // Prefer the least disruptive octave in ties.
            for (MidiNote note : notes) {
                int translated = note.key() + candidate;
                cost += Math.max(0, LOWEST_ORCHESTRA_KEY - translated) + Math.max(0, translated - HIGHEST_ORCHESTRA_KEY);
            }
            if (cost < bestCost) {
                bestCost = cost;
                bestShift = candidate;
            }
        }
        return bestShift;
    }

    private Overrides loadOverrides(Song song) {
        Path sidecar = sidecar(song);
        if (!Files.isRegularFile(sidecar)) return new Overrides(0, Map.of());
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(sidecar.toFile());
        Map<Integer, ChannelOverride> channels = new HashMap<>();
        ConfigurationSection section = yaml.getConfigurationSection("channels");
        if (section != null) for (String key : section.getKeys(false)) {
            try {
                int channel = Integer.parseInt(key);
                if (channel < 0 || channel > 15) continue;
                ConfigurationSection entry = section.getConfigurationSection(key);
                if (entry == null) continue;
                channels.put(channel, new ChannelOverride(entry.getInt("transpose", 0), parseSound(entry.getString("sound"))));
            } catch (NumberFormatException ignored) {
                // Invalid override keys are ignored; analysis remains available for the rest of the song.
            }
        }
        return new Overrides(yaml.getInt("transpose", 0), channels);
    }

    private Sound parseSound(String name) {
        if (name == null || name.isBlank()) return null;
        String normalized = name.trim().toUpperCase(Locale.ROOT);
        if (!normalized.startsWith("BLOCK_NOTE_BLOCK_")) normalized = "BLOCK_NOTE_BLOCK_" + normalized;
        try {
            return Sound.valueOf(normalized);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private String overrideFingerprint(Song song) {
        try {
            Path sidecar = sidecar(song);
            return Files.isRegularFile(sidecar) ? Files.getLastModifiedTime(sidecar).toMillis() + ":" + Files.size(sidecar) : "none";
        } catch (Exception ignored) {
            return "unreadable";
        }
    }

    private String cacheKey(Song song, CompiledSong compiled) {
        return song.id() + ":" + compiled.sourceHash() + ":" + overrideFingerprint(song);
    }

    private Path sidecar(Song song) {
        String name = song.path().getFileName().toString().replaceFirst("(?i)\\.(mid|midi)$", ".song.yml");
        return song.path().resolveSibling(name);
    }

    private record Part(int channel, int program) {
    }

    private record Overrides(int transpose, Map<Integer, ChannelOverride> channels) {
        private ChannelOverride channel(int channel) {
            return channels.getOrDefault(channel, new ChannelOverride(0, null));
        }
    }

    private record ChannelOverride(int transpose, Sound sound) {
    }
}
