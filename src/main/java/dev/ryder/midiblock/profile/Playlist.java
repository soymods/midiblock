package dev.ryder.midiblock.profile;

import java.util.List;

/** A player-owned, persistent ordered list of library song ids. */
public record Playlist(String id, String name, List<String> songIds) {
    public Playlist {
        songIds = List.copyOf(songIds);
    }
}
