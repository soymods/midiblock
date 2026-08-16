package dev.ryder.midiblock.library;

import java.nio.file.Path;

/** A source MIDI file discovered in the server's song library. */
public record Song(String id, String displayName, Path path) {
}
