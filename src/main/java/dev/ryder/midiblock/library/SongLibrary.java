package dev.ryder.midiblock.library;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;
import java.util.stream.Stream;

/** Discovers source files only. MIDI parsing/compilation belongs in a later compiler pass. */
public final class SongLibrary {
    private final Path songsDirectory;
    private final Logger logger;
    private final Map<String, Song> songs = new LinkedHashMap<>();

    public SongLibrary(Path dataDirectory, Logger logger) {
        this.songsDirectory = dataDirectory.resolve("songs");
        this.logger = logger;
    }

    public void reload() {
        songs.clear();
        try {
            Files.createDirectories(songsDirectory);
            try (Stream<Path> files = Files.walk(songsDirectory)) {
                files.filter(Files::isRegularFile)
                    .filter(this::isMidi)
                    .sorted(Comparator.comparing(path -> songsDirectory.relativize(path).toString()))
                    .forEach(this::addSong);
            }
        } catch (IOException exception) {
            logger.severe("Could not read song library: " + exception.getMessage());
        }
    }

    public Collection<Song> songs() {
        return List.copyOf(songs.values());
    }

    public Optional<Song> find(String id) {
        return Optional.ofNullable(songs.get(id.toLowerCase(Locale.ROOT)));
    }

    private boolean isMidi(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".mid") || name.endsWith(".midi");
    }

    private void addSong(Path path) {
        String relative = songsDirectory.relativize(path).toString().replace('\\', '/');
        String id = relative.replaceFirst("(?i)\\.(mid|midi)$", "").toLowerCase(Locale.ROOT);
        String displayName = relative.replaceFirst("(?i)\\.(mid|midi)$", "");
        songs.put(id, new Song(id, displayName, path));
    }
}
