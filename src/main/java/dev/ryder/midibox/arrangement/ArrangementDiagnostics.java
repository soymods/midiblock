package dev.ryder.midibox.arrangement;

import java.util.Map;

/** Reports intentional compromises made while fitting MIDI into native note-block range. */
public record ArrangementDiagnostics(int noteCount, int outOfRangeNotes, int percussionNotes, Map<String, Integer> octaveShifts) {
    public ArrangementDiagnostics {
        octaveShifts = Map.copyOf(octaveShifts);
    }
}
