package dev.ryder.midibox.arrangement;

import org.bukkit.Sound;

/** A MIDI note translated into one native Minecraft sound request. */
public record PlayableNote(long timeMicros, long endTimeMicros, int channel, int midiKey, int program, Sound sound, String orchestraSoundKey, float pitch, int velocity, boolean percussion) {
}
