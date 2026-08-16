package dev.ryder.midibox.midi;

import dev.ryder.midibox.library.Song;

import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiMessage;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Parses Standard MIDI Files into a playback-neutral, cached intermediate format. */
public final class MidiCompiler {
    private static final int DEFAULT_TEMPO = 500_000;
    private static final long MAX_SOURCE_BYTES = 32L * 1024L * 1024L;
    private final Path cacheDirectory;
    private final CompiledSongCache diskCache = new CompiledSongCache();
    private final Map<String, CompiledSong> memoryCache = new ConcurrentHashMap<>();

    public MidiCompiler(Path dataDirectory, java.util.logging.Logger logger) {
        this.cacheDirectory = dataDirectory.resolve("cache");
    }

    public synchronized CompiledSong getOrCompile(Song song) throws IOException, InvalidMidiDataException {
        if (Files.size(song.path()) > MAX_SOURCE_BYTES) {
            throw new IOException("MIDI source exceeds the 32 MiB safety limit: " + song.id());
        }
        String sourceHash = sha256(song.path());
        CompiledSong inMemory = memoryCache.get(song.id());
        if (inMemory != null && inMemory.sourceHash().equals(sourceHash)) return inMemory;

        Path cachePath = cacheDirectory.resolve(sha256(song.id().getBytes()) + ".mbc");
        try {
            CompiledSong fromDisk = diskCache.read(cachePath);
            if (fromDisk.songId().equals(song.id()) && fromDisk.sourceHash().equals(sourceHash)) {
                memoryCache.put(song.id(), fromDisk);
                return fromDisk;
            }
        } catch (IOException ignored) {
            // A missing, interrupted, or old cache is rebuilt below.
        }

        CompiledSong compiled = compile(song, sourceHash);
        diskCache.write(cachePath, compiled);
        memoryCache.put(song.id(), compiled);
        return compiled;
    }

    public Optional<CompiledSong> ready(Song song) {
        return Optional.ofNullable(memoryCache.get(song.id()));
    }

    public void clearMemory() {
        memoryCache.clear();
    }

    private CompiledSong compile(Song song, String sourceHash) throws IOException, InvalidMidiDataException {
        Sequence sequence = MidiSystem.getSequence(song.path().toFile());
        List<RawEvent> rawEvents = new ArrayList<>();
        List<TempoPoint> tempos = new ArrayList<>();
        String title = song.displayName();
        Track[] tracks = sequence.getTracks();
        for (int trackIndex = 0; trackIndex < tracks.length; trackIndex++) {
            Track track = tracks[trackIndex];
            for (int eventIndex = 0; eventIndex < track.size(); eventIndex++) {
                MidiEvent event = track.get(eventIndex);
                MidiMessage message = event.getMessage();
                if (message instanceof MetaMessage meta) {
                    if (meta.getType() == 0x51 && meta.getData().length == 3) {
                        byte[] data = meta.getData();
                        int tempo = ((data[0] & 0xFF) << 16) | ((data[1] & 0xFF) << 8) | (data[2] & 0xFF);
                        tempos.add(new TempoPoint(event.getTick(), tempo));
                    } else if (meta.getType() == 0x03 && title.equals(song.displayName())) {
                        String candidate = new String(meta.getData(), java.nio.charset.StandardCharsets.UTF_8).trim();
                        if (!candidate.isEmpty()) title = candidate;
                    }
                }
                if (message instanceof ShortMessage shortMessage) {
                    rawEvents.add(new RawEvent(event.getTick(), trackIndex, eventIndex, shortMessage));
                }
            }
        }

        List<TempoPoint> tempoMap = normalizeTempoMap(tempos);
        Timing timing = new Timing(sequence, tempoMap);
        long duration = timing.microsAt(sequence.getTickLength());
        Extraction extraction = extractPerformance(rawEvents, timing, duration);
        return new CompiledSong(song.id(), sourceHash, title, sequence.getDivisionType(), sequence.getResolution(), sequence.getTickLength(), duration, tempoMap, extraction.notes, extraction.controls);
    }

    private Extraction extractPerformance(List<RawEvent> events, Timing timing, long songDurationMicros) {
        events.sort(Comparator.comparingLong(RawEvent::tick)
            .thenComparingInt(event -> event.message().getCommand() == ShortMessage.PROGRAM_CHANGE ? 0 : 1)
            .thenComparingInt(RawEvent::track).thenComparingInt(RawEvent::index));
        int[] programs = new int[16];
        int[] rpnMsb = new int[16];
        int[] rpnLsb = new int[16];
        Arrays.fill(rpnMsb, -1);
        Arrays.fill(rpnLsb, -1);
        List<MutableNote> notes = new ArrayList<>();
        List<MidiControlEvent> controls = new ArrayList<>();
        Map<ChannelKey, ArrayDeque<MutableNote>> heldNotes = new HashMap<>();
        for (RawEvent event : events) {
            ShortMessage message = event.message();
            int channel = message.getChannel();
            long timeMicros = timing.microsAt(event.tick());
            if (message.getCommand() == ShortMessage.PROGRAM_CHANGE) {
                programs[channel] = message.getData1();
            } else if (message.getCommand() == ShortMessage.NOTE_ON && message.getData2() > 0) {
                MutableNote note = new MutableNote(event.tick(), timeMicros, channel, message.getData1(), message.getData2(), programs[channel]);
                notes.add(note);
                heldNotes.computeIfAbsent(new ChannelKey(channel, message.getData1()), ignored -> new ArrayDeque<>()).addLast(note);
            } else if (message.getCommand() == ShortMessage.NOTE_OFF || (message.getCommand() == ShortMessage.NOTE_ON && message.getData2() == 0)) {
                ArrayDeque<MutableNote> sameKey = heldNotes.get(new ChannelKey(channel, message.getData1()));
                if (sameKey != null && !sameKey.isEmpty()) {
                    MutableNote released = sameKey.removeFirst();
                    released.endTimeMicros = Math.max(released.timeMicros, timeMicros);
                }
            } else if (message.getCommand() == ShortMessage.PITCH_BEND) {
                controls.add(new MidiControlEvent(timeMicros, channel, MidiControlEvent.Type.PITCH_BEND, (message.getData2() << 7) | message.getData1()));
            } else if (message.getCommand() == ShortMessage.CONTROL_CHANGE) {
                int controller = message.getData1();
                MidiControlEvent.Type type = switch (controller) {
                    case 7 -> MidiControlEvent.Type.CHANNEL_VOLUME;
                    case 11 -> MidiControlEvent.Type.EXPRESSION;
                    case 64 -> MidiControlEvent.Type.SUSTAIN;
                    default -> null;
                };
                if (type != null) controls.add(new MidiControlEvent(timeMicros, channel, type, message.getData2()));
                if (controller == 101) rpnMsb[channel] = message.getData2();
                else if (controller == 100) rpnLsb[channel] = message.getData2();
                else if (controller == 6 && rpnMsb[channel] == 0 && rpnLsb[channel] == 0) {
                    controls.add(new MidiControlEvent(timeMicros, channel, MidiControlEvent.Type.PITCH_BEND_RANGE, message.getData2()));
                }
            }
        }
        List<MidiNote> immutableNotes = notes.stream()
            .map(note -> new MidiNote(note.tick, note.timeMicros, note.endTimeMicros == -1L ? songDurationMicros : note.endTimeMicros, note.channel, note.key, note.velocity, note.program))
            .toList();
        return new Extraction(immutableNotes, controls);
    }

    private List<TempoPoint> normalizeTempoMap(List<TempoPoint> tempos) {
        Map<Long, Integer> atTick = new HashMap<>();
        atTick.put(0L, DEFAULT_TEMPO);
        tempos.stream().sorted(Comparator.comparingLong(TempoPoint::tick)).forEach(tempo -> atTick.put(tempo.tick(), tempo.microsecondsPerQuarter()));
        return atTick.entrySet().stream().map(entry -> new TempoPoint(entry.getKey(), entry.getValue())).sorted(Comparator.comparingLong(TempoPoint::tick)).toList();
    }

    private String sha256(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            for (int read; (read = input.read(buffer)) >= 0;) digest.update(buffer, 0, read);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record RawEvent(long tick, int track, int index, ShortMessage message) {
    }

    private record ChannelKey(int channel, int key) {
    }

    private record Extraction(List<MidiNote> notes, List<MidiControlEvent> controls) {
    }

    /** Uses one exact tempo numerator, avoiding rounding drift across tempo segments. */
    private static final class Timing {
        private final Sequence sequence;
        private final List<Segment> segments;

        private Timing(Sequence sequence, List<TempoPoint> tempos) {
            this.sequence = sequence;
            List<Segment> built = new ArrayList<>();
            long numerator = 0L;
            long previousTick = 0L;
            int previousTempo = DEFAULT_TEMPO;
            for (TempoPoint tempo : tempos) {
                numerator += (tempo.tick() - previousTick) * (long) previousTempo;
                built.add(new Segment(tempo.tick(), numerator, tempo.microsecondsPerQuarter()));
                previousTick = tempo.tick();
                previousTempo = tempo.microsecondsPerQuarter();
            }
            this.segments = built;
        }

        private long microsAt(long tick) {
            if (sequence.getDivisionType() != Sequence.PPQ) {
                return Math.round(tick * 1_000_000D / (sequence.getDivisionType() * sequence.getResolution()));
            }
            int low = 0;
            int high = segments.size() - 1;
            while (low < high) {
                int middle = (low + high + 1) >>> 1;
                if (segments.get(middle).tick <= tick) low = middle;
                else high = middle - 1;
            }
            Segment segment = segments.get(low);
            long numerator = segment.startNumerator + (tick - segment.tick) * (long) segment.tempo;
            return numerator / sequence.getResolution();
        }

        private record Segment(long tick, long startNumerator, int tempo) {
        }
    }

    private static final class MutableNote {
        private final long tick;
        private final long timeMicros;
        private final int channel;
        private final int key;
        private final int velocity;
        private final int program;
        private long endTimeMicros = -1L;

        private MutableNote(long tick, long timeMicros, int channel, int key, int velocity, int program) {
            this.tick = tick;
            this.timeMicros = timeMicros;
            this.channel = channel;
            this.key = key;
            this.velocity = velocity;
            this.program = program;
        }
    }
}
