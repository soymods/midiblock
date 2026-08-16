package dev.ryder.midibox.midi;

/** A note-on event with its absolute, tempo-adjusted timestamp. */
public record MidiNote(long tick, long timeMicros, long endTimeMicros, int channel, int key, int velocity, int program) {
}
