package dev.ryder.midibox.orchestra;

import dev.ryder.midibox.midi.MidiNote;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Curated vanilla-sound palette; data lives outside code so it can be tuned by ear. */
public final class OrchestraProfile {
    private final List<Entry> entries;

    private OrchestraProfile(List<Entry> entries) {
        this.entries = List.copyOf(entries);
    }

    public static OrchestraProfile load(File file) {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection sounds = yaml.getConfigurationSection("sounds");
        if (sounds == null) return new OrchestraProfile(List.of());
        List<Entry> entries = new ArrayList<>();
        for (String id : sounds.getKeys(false)) {
            ConfigurationSection sound = sounds.getConfigurationSection(id);
            if (sound == null) continue;
            entries.add(new Entry(id, sound.getString("sound", ""), sound.getDouble("base-midi", 60), sound.getInt("safe-min", 48), sound.getInt("safe-max", 72), sound.getStringList("roles")));
        }
        return new OrchestraProfile(entries);
    }

    public Entry bestFor(int program, List<MidiNote> notes, int transpose) {
        String role = roleFor(program);
        Entry best = null;
        double bestScore = Double.MAX_VALUE;
        for (Entry entry : entries) {
            double score = entry.roles.contains(role) ? 0 : entry.roles.contains("tonal-core") ? 20 : 90;
            for (MidiNote note : notes) {
                int key = note.key() + transpose;
                score += Math.abs(key - entry.baseMidi);
                score += Math.max(0, entry.safeMin - key) * 100;
                score += Math.max(0, key - entry.safeMax) * 100;
            }
            if (score < bestScore) {
                bestScore = score;
                best = entry;
            }
        }
        return best;
    }

    public List<Entry> entries() {
        return entries;
    }

    public Optional<Entry> find(String id) {
        return entries.stream().filter(entry -> entry.id.equalsIgnoreCase(id)).findFirst();
    }

    private static String roleFor(int program) {
        if (program <= 7) return "piano";
        if (program <= 15) return "mallet";
        if (program <= 23) return "organ";
        if (program <= 31) return "pluck";
        if (program <= 39) return "bass";
        if (program <= 55) return "strings";
        if (program <= 79) return "wind";
        if (program <= 95) return "synth";
        return "texture";
    }

    public record Entry(String id, String soundKey, double baseMidi, int safeMin, int safeMax, List<String> roles) {
        public Entry {
            roles = List.copyOf(roles);
        }
    }
}
