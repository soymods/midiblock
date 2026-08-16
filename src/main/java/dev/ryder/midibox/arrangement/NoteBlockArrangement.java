package dev.ryder.midibox.arrangement;

import dev.ryder.midibox.midi.CompiledSong;

import java.util.List;

/** The native-note-block render plan derived from one immutable compiled MIDI song. */
public record NoteBlockArrangement(CompiledSong source, List<PlayableNote> notes, ArrangementDiagnostics diagnostics) {
    public NoteBlockArrangement {
        notes = List.copyOf(notes);
    }
}
