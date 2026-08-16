package dev.ryder.midiblock.midi;

import dev.ryder.midiblock.library.Song;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MidiCompilerTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void compilesTempoAdjustedNotesAndReusesTheDiskCache() throws Exception {
        Path source = temporaryDirectory.resolve("tempo.mid");
        Sequence sequence = new Sequence(Sequence.PPQ, 480);
        Track track = sequence.createTrack();
        track.add(new MidiEvent(tempo(500_000), 0));
        track.add(new MidiEvent(program(40), 0));
        track.add(new MidiEvent(control(7, 80), 0));
        track.add(new MidiEvent(note(60, 100), 0));
        track.add(new MidiEvent(pitchBend(10_240), 120));
        track.add(new MidiEvent(noteOff(60), 240));
        track.add(new MidiEvent(control(64, 127), 240));
        track.add(new MidiEvent(tempo(1_000_000), 480));
        track.add(new MidiEvent(note(64, 90), 960));
        MidiSystem.write(sequence, 1, source.toFile());

        Song song = new Song("tempo", "Tempo", source);
        MidiCompiler compiler = new MidiCompiler(temporaryDirectory, Logger.getAnonymousLogger());
        CompiledSong first = compiler.getOrCompile(song);
        CompiledSong cached = new MidiCompiler(temporaryDirectory, Logger.getAnonymousLogger()).getOrCompile(song);

        assertEquals(2, first.notes().size());
        assertEquals(0L, first.notes().get(0).timeMicros());
        assertEquals(1_500_000L, first.notes().get(1).timeMicros());
        assertEquals(250_000L, first.notes().get(0).endTimeMicros());
        assertEquals(40, first.notes().get(0).program());
        assertEquals(3, first.controls().size());
        assertEquals(MidiControlEvent.Type.PITCH_BEND, first.controls().get(1).type());
        assertEquals(10_240, first.controls().get(1).value());
        assertEquals(first, cached);
        try (var files = Files.list(temporaryDirectory.resolve("cache"))) {
            assertTrue(files.anyMatch(path -> path.getFileName().toString().endsWith(".mbc")));
        }
    }

    @Test
    void rejectsMalformedMidiWithoutCreatingUsablePlaybackData() throws Exception {
        Path source = temporaryDirectory.resolve("broken.mid");
        Files.write(source, new byte[] {0x4D, 0x54, 0x68, 0x64, 0x00});

        MidiCompiler compiler = new MidiCompiler(temporaryDirectory, Logger.getAnonymousLogger());

        assertThrows(java.io.IOException.class,
            () -> compiler.getOrCompile(new Song("broken", "Broken", source)));
    }

    private MetaMessage tempo(int microsecondsPerQuarter) throws Exception {
        MetaMessage message = new MetaMessage();
        message.setMessage(0x51, new byte[] {
            (byte) (microsecondsPerQuarter >>> 16),
            (byte) (microsecondsPerQuarter >>> 8),
            (byte) microsecondsPerQuarter
        }, 3);
        return message;
    }

    private ShortMessage program(int program) throws Exception {
        ShortMessage message = new ShortMessage();
        message.setMessage(ShortMessage.PROGRAM_CHANGE, 0, program, 0);
        return message;
    }

    private ShortMessage note(int key, int velocity) throws Exception {
        ShortMessage message = new ShortMessage();
        message.setMessage(ShortMessage.NOTE_ON, 0, key, velocity);
        return message;
    }

    private ShortMessage noteOff(int key) throws Exception {
        ShortMessage message = new ShortMessage();
        message.setMessage(ShortMessage.NOTE_OFF, 0, key, 0);
        return message;
    }

    private ShortMessage control(int controller, int value) throws Exception {
        ShortMessage message = new ShortMessage();
        message.setMessage(ShortMessage.CONTROL_CHANGE, 0, controller, value);
        return message;
    }

    private ShortMessage pitchBend(int value) throws Exception {
        ShortMessage message = new ShortMessage();
        message.setMessage(ShortMessage.PITCH_BEND, 0, value & 0x7F, value >>> 7);
        return message;
    }
}
