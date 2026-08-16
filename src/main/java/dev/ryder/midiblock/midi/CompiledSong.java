package dev.ryder.midiblock.midi;

import java.util.List;

/** Immutable intermediate representation consumed by future playback and analysis passes. */
public record CompiledSong(
    String songId,
    String sourceHash,
    String title,
    float divisionType,
    int resolution,
    long tickLength,
    long durationMicros,
    List<TempoPoint> tempoMap,
    List<MidiNote> notes,
    List<MidiControlEvent> controls
) {
    public CompiledSong {
        tempoMap = List.copyOf(tempoMap);
        notes = List.copyOf(notes);
        controls = List.copyOf(controls);
    }
}
