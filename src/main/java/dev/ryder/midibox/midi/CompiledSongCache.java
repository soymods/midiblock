package dev.ryder.midibox.midi;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/** A small, versioned binary cache. It never serializes Java objects. */
final class CompiledSongCache {
    private static final int MAGIC = 0x4D424331; // MBC1
    private static final int SCHEMA_VERSION = 3;

    CompiledSong read(Path path) throws IOException {
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
            if (input.readInt() != MAGIC || input.readInt() != SCHEMA_VERSION) {
                throw new IOException("Unsupported MidiBox cache format");
            }
            String songId = input.readUTF();
            String sourceHash = input.readUTF();
            String title = input.readUTF();
            float divisionType = input.readFloat();
            int resolution = input.readInt();
            long tickLength = input.readLong();
            long durationMicros = input.readLong();
            int tempoCount = checkedCount(input.readInt(), "tempo points");
            List<TempoPoint> tempos = new ArrayList<>(tempoCount);
            for (int index = 0; index < tempoCount; index++) tempos.add(new TempoPoint(input.readLong(), input.readInt()));
            int noteCount = checkedCount(input.readInt(), "notes");
            List<MidiNote> notes = new ArrayList<>(noteCount);
            for (int index = 0; index < noteCount; index++) {
                notes.add(new MidiNote(input.readLong(), input.readLong(), input.readLong(), input.readUnsignedByte(), input.readUnsignedByte(), input.readUnsignedByte(), input.readUnsignedByte()));
            }
            int controlCount = checkedCount(input.readInt(), "control events");
            List<MidiControlEvent> controls = new ArrayList<>(controlCount);
            for (int index = 0; index < controlCount; index++) {
                long timeMicros = input.readLong();
                int channel = input.readUnsignedByte();
                int type = input.readUnsignedByte();
                if (type >= MidiControlEvent.Type.values().length) throw new IOException("Invalid control type in cache");
                controls.add(new MidiControlEvent(timeMicros, channel, MidiControlEvent.Type.values()[type], input.readUnsignedShort()));
            }
            return new CompiledSong(songId, sourceHash, title, divisionType, resolution, tickLength, durationMicros, tempos, notes, controls);
        }
    }

    void write(Path path, CompiledSong song) throws IOException {
        Files.createDirectories(path.getParent());
        Path temporary = Files.createTempFile(path.getParent(), path.getFileName().toString(), ".tmp");
        try {
            try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temporary)))) {
                output.writeInt(MAGIC);
                output.writeInt(SCHEMA_VERSION);
                output.writeUTF(song.songId());
                output.writeUTF(song.sourceHash());
                output.writeUTF(song.title());
                output.writeFloat(song.divisionType());
                output.writeInt(song.resolution());
                output.writeLong(song.tickLength());
                output.writeLong(song.durationMicros());
                output.writeInt(song.tempoMap().size());
                for (TempoPoint tempo : song.tempoMap()) {
                    output.writeLong(tempo.tick());
                    output.writeInt(tempo.microsecondsPerQuarter());
                }
                output.writeInt(song.notes().size());
                for (MidiNote note : song.notes()) {
                    output.writeLong(note.tick());
                    output.writeLong(note.timeMicros());
                    output.writeLong(note.endTimeMicros());
                    output.writeByte(note.channel());
                    output.writeByte(note.key());
                    output.writeByte(note.velocity());
                    output.writeByte(note.program());
                }
                output.writeInt(song.controls().size());
                for (MidiControlEvent control : song.controls()) {
                    output.writeLong(control.timeMicros());
                    output.writeByte(control.channel());
                    output.writeByte(control.type().ordinal());
                    output.writeShort(control.value());
                }
            }
            moveAtomically(temporary, path);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private int checkedCount(int count, String label) throws IOException {
        if (count < 0 || count > 10_000_000) throw new IOException("Invalid " + label + " count in cache");
        return count;
    }
}
