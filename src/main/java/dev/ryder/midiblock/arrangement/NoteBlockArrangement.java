package dev.ryder.midiblock.arrangement;

import dev.ryder.midiblock.midi.CompiledSong;

import java.util.List;

/** The native-note-block render plan derived from one immutable compiled MIDI song. */
public record NoteBlockArrangement(CompiledSong source, List<PlayableNote> notes, ArrangementDiagnostics diagnostics) {
    public NoteBlockArrangement {
        notes = List.copyOf(notes);
    }
}
