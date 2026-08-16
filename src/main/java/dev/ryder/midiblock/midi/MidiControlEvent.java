package dev.ryder.midiblock.midi;

/** A channel-level MIDI performance control at an absolute song timestamp. */
public record MidiControlEvent(long timeMicros, int channel, Type type, int value) {
    public enum Type {
        PITCH_BEND,
        PITCH_BEND_RANGE,
        CHANNEL_VOLUME,
        EXPRESSION,
        SUSTAIN
    }
}
