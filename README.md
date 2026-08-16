# MidiBlock

MidiBlock is a Paper plugin that plays Standard MIDI files through native Minecraft note-block sounds, with an optional resource-pack audio upgrade. It targets the stable Paper API from Minecraft 1.21 through 26.2 and deliberately avoids NMS.

## Install

1. Build with `gradle build` (Java 21 toolchain).
2. Copy `build/libs/midiblock-0.1.0.jar` to the server's `plugins/` directory.
3. Start Paper once, then add `.mid` or `.midi` files beneath `plugins/MidiBlock/songs/`.
4. Run `/music reload`, then `/music` in game.

Minecraft 1.20–1.21.11 uses Java 21; Paper 26.1+ requires Java 25. The plugin bytecode remains Java 21 compatible, so one JAR works across that range when the server has its required Java runtime.

## Player commands

```text
/music                         Open the player
/music play <song>             Play for yourself
/music play <song> @Player     Play for one player (permission required)
/music play <song> radius 50   Play nearby (permission required)
/music play <song> global      Play for everyone (permission required)
/music pause | resume | stop
/music volume <0-100>
/music history
/music playlist list|create|add|play|delete
/music analyze <song>
/music pack                    Request optional enhanced-audio pack
/music reload                  Rescan the library (admin)
```

Shift-clicking a song in the Music Library queues it. Player volume, history, and playlists are persisted in `plugins/MidiBlock/players.yml`.

## Per-song arrangement overrides

Place a sidecar next to a song, for example `songs/ambient/sweden.song.yml`. `transpose` shifts every melodic part; channels are zero-based MIDI channels (`9` is percussion).

```yaml
transpose: 0
channels:
  "0":
    transpose: -12
    sound: GUITAR
```

Valid `sound` values include `HARP`, `BASS`, `BELL`, `FLUTE`, `GUITAR`, `CHIME`, `XYLOPHONE`, `IRON_XYLOPHONE`, `COW_BELL`, `DIDGERIDOO`, `BIT`, `BANJO`, and `PLING`.

## Enhanced audio

Native playback is always available. To enable richer instruments, add licensed `.ogg` assets to [resource-pack-template](resource-pack-template/README.md), zip and host the pack via HTTPS, calculate its SHA-1, and configure `enhanced-audio` in `plugins/MidiBlock/config.yml`. Players without a successfully applied pack automatically keep native note-block playback.

## Operations and safety

- MIDI is compiled on a worker thread; all in-game sound calls stay on the server thread.
- Source files are capped at 32 MiB and cache files are atomically written. Delete `plugins/MidiBlock/cache/` safely to force recompilation.
- `playback.max-events-per-tick` bounds work after lag spikes; `max-voices-per-player` protects clients from dense chords.
- `/music analyze <song>` reports octave shifts and notes that required native range clamping.
- Test with `gradle test`; package with `gradle build`.

Native note blocks cannot provide continuous pitch bends or true sustained samples. MidiBlock preserves MIDI timing and controllers, applies bend/volume to upcoming notes, and uses gentle sustain refreshes as the closest native approximation.
