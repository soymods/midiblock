package dev.ryder.midibox.midi;

/** A MIDI tempo change, expressed as microseconds per quarter note. */
public record TempoPoint(long tick, int microsecondsPerQuarter) {
}
