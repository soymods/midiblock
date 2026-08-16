package dev.ryder.midibox.arrangement;

import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.List;

/** Curated General MIDI families and channel-10 drum mappings for the native note-block palette. */
final class NativeInstrumentMapper {
    private static final List<Sound> USED_SOUNDS = List.of(
        Sound.BLOCK_NOTE_BLOCK_HARP, Sound.BLOCK_NOTE_BLOCK_BASS, Sound.BLOCK_NOTE_BLOCK_BELL,
        Sound.BLOCK_NOTE_BLOCK_FLUTE, Sound.BLOCK_NOTE_BLOCK_GUITAR, Sound.BLOCK_NOTE_BLOCK_CHIME,
        Sound.BLOCK_NOTE_BLOCK_XYLOPHONE, Sound.BLOCK_NOTE_BLOCK_IRON_XYLOPHONE,
        Sound.BLOCK_NOTE_BLOCK_COW_BELL, Sound.BLOCK_NOTE_BLOCK_DIDGERIDOO, Sound.BLOCK_NOTE_BLOCK_BIT,
        Sound.BLOCK_NOTE_BLOCK_BANJO, Sound.BLOCK_NOTE_BLOCK_PLING, Sound.BLOCK_NOTE_BLOCK_BASEDRUM,
        Sound.BLOCK_NOTE_BLOCK_SNARE, Sound.BLOCK_NOTE_BLOCK_HAT
    );

    Sound melodic(int program) {
        if (program <= 7) return Sound.BLOCK_NOTE_BLOCK_HARP;           // piano
        if (program <= 15) return Sound.BLOCK_NOTE_BLOCK_BELL;          // chromatic percussion
        if (program <= 23) return Sound.BLOCK_NOTE_BLOCK_DIDGERIDOO;    // organ
        if (program <= 31) return Sound.BLOCK_NOTE_BLOCK_GUITAR;        // guitar
        if (program <= 39) return Sound.BLOCK_NOTE_BLOCK_BASS;          // bass
        if (program <= 47) return Sound.BLOCK_NOTE_BLOCK_FLUTE;         // strings
        if (program <= 55) return Sound.BLOCK_NOTE_BLOCK_CHIME;         // ensemble
        if (program <= 63) return Sound.BLOCK_NOTE_BLOCK_BELL;          // brass
        if (program <= 79) return Sound.BLOCK_NOTE_BLOCK_FLUTE;         // reed / pipe
        if (program <= 87) return Sound.BLOCK_NOTE_BLOCK_BIT;           // synth lead
        if (program <= 95) return Sound.BLOCK_NOTE_BLOCK_CHIME;         // synth pad
        if (program <= 103) return Sound.BLOCK_NOTE_BLOCK_PLING;        // synth effects
        if (program <= 111) return Sound.BLOCK_NOTE_BLOCK_BANJO;        // ethnic
        return Sound.BLOCK_NOTE_BLOCK_BIT;                               // SFX
    }

    Sound percussion(int key) {
        return switch (key) {
            case 35, 36, 41, 43, 45, 47, 48, 50 -> Sound.BLOCK_NOTE_BLOCK_BASEDRUM;
            case 37, 38, 39, 40 -> Sound.BLOCK_NOTE_BLOCK_SNARE;
            case 42, 44, 46, 51, 52, 53, 55, 59 -> Sound.BLOCK_NOTE_BLOCK_HAT;
            case 56, 75 -> Sound.BLOCK_NOTE_BLOCK_COW_BELL;
            case 49, 54, 57, 58 -> Sound.BLOCK_NOTE_BLOCK_CHIME;
            default -> Sound.BLOCK_NOTE_BLOCK_SNARE;
        };
    }

    void stopAll(Player player) {
        for (Sound sound : USED_SOUNDS) player.stopSound(sound);
    }
}
