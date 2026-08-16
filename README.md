<div align="center">

# MidiBlock

[![Minecraft](https://img.shields.io/badge/Minecraft-1.21.x%20%7C%2026.1--26.2-00AA00?style=for-the-badge&logo=minecraft)](https://minecraft.net)
[![Paper](https://img.shields.io/badge/Paper-1.21%2B-FFFFFF?style=for-the-badge)](https://papermc.io)
[![Java](https://img.shields.io/badge/Java-21%2B-FF6B6B?style=for-the-badge&logo=openjdk)](https://openjdk.org)
[![Build](https://img.shields.io/badge/Build-Gradle-02303A?style=for-the-badge&logo=gradle)](https://gradle.org)

A high-fidelity MIDI player for Minecraft, built around native note blocks and an optional enhanced-audio resource pack.

Created by `Ryder`.

</div>

## What MidiBlock Is

MidiBlock turns Standard MIDI files into a shared Minecraft listening experience. It reads MIDI timing, tempo changes, instruments, percussion, velocity, pitch bend, sustain, and channel controls; translates them into a native note-block arrangement; and gives players an iPod-inspired in-game music player.

The current plugin includes:

- MIDI discovery, compilation, and an atomically written playback cache.
- Tempo-correct, per-player playback with lag-resistant scheduling.
- Native General MIDI instrument mapping, percussion routing, octave fitting, and range diagnostics.
- An in-game Home, Music Library, Now Playing, and Settings player flow.
- Persistent volume, history, playlists, queues, and audience playback.
- An optional resource-pack bridge for richer custom instrument samples.

## Feature Overview

### Playback And Accuracy

- Uses MIDI tempo maps and an absolute monotonic playback clock instead of a simple tick counter.
- Supports program changes, channel volume, expression, pitch bend, percussion, and sustain-pedal approximation.
- Protects clients and the server with configurable voice and per-tick event limits.
- Runs `/music analyze <song>` to report octave shifts and notes that needed native range clamping.

### iPod-Style Player

- `/music` opens a Home screen with Music, Now Playing, and Settings.
- The Library supports paged song browsing; click to play and shift-click to queue.
- Now Playing shows live elapsed time, a progress bar, playback state, and voice-protection feedback.
- Player volume is saved independently for every UUID.

### Library, Playlists, And Sharing

- Songs are read from `plugins/MidiBlock/songs/`, including nested folders.
- Player profiles persist recently played tracks and named playlists in `plugins/MidiBlock/players.yml`.
- Share music with yourself, a selected player, everyone in a radius, or the whole server through permission-gated commands.
- Place a bold red **JUKEBOX** to create a persistent public player; right-click it to open the Music Library and broadcast songs from that block to listeners in its configured radius.

### Enhanced Audio

- Vanilla listeners always receive a complete native note-block arrangement.
- Players who accept the configured enhanced-audio pack receive custom `midiblock:` instrument and percussion sounds instead.
- The resource-pack template defines the sample layout without bundling unlicensed audio assets.

### Vanilla Orchestra

- The default balanced orchestra uses curated, locally available vanilla sound events to extend melodic coverage beyond the note-block palette.
- Each MIDI part selects one coherent source by instrument role and safe pitch fit, rather than switching sounds for every note.
- Administrators can use `/music orchestra list` and `/music orchestra audition <id> [midi-note]` to tune the palette by ear.

## Controls And Commands

```text
/music                         Open the player
/music play <song>             Play for yourself
/music play <song> @Player     Play for one player (permission required)
/music play <song> radius 50   Play nearby (permission required)
/music play <song> global      Play for everyone (permission required)
/music pause | resume | stop
/music volume <0-100>
/music jukebox                 Receive a permanent public JUKEBOX
/music history
/music playlist list|create|add|play|delete
/music analyze <song>
/music pack                    Request optional enhanced-audio pack
/music reload                  Rescan the library (admin)
```

## Installation

### Required

- Paper for Minecraft `1.21` through `1.21.11`, or `26.1` through `26.2`
- Java `21` for Minecraft 1.21.x; Java `25` for Minecraft 26.x

### Steps

1. Build the plugin with `gradle build`.
2. Copy `build/libs/midiblock-0.1.0.jar` into the Paper server's `plugins/` directory.
3. Start Paper once so it creates `plugins/MidiBlock/`.
4. Add `.mid` or `.midi` files under `plugins/MidiBlock/songs/`.
5. Run `/music reload`, then `/music` in game.

## Plugin Files

MidiBlock stores runtime data inside the Paper plugins directory under `MidiBlock/`.

- `MidiBlock/config.yml`: playback, UI, limits, and enhanced-audio settings
- `MidiBlock/songs/`: source MIDI library and optional `.song.yml` sidecars
- `MidiBlock/cache/`: generated `MBC2` playback cache; safe to delete and rebuild
- `MidiBlock/players.yml`: per-player volume, history, and playlists

### Per-Song Arrangement Overrides

Place a sidecar next to a song, such as `songs/ambient/sweden.song.yml`. `transpose` shifts every melodic part; channels are zero-based MIDI channels (`9` is percussion).

```yaml
transpose: 0
channels:
  "0":
    transpose: -12
    sound: GUITAR
```

Valid `sound` values include `HARP`, `BASS`, `BELL`, `FLUTE`, `GUITAR`, `CHIME`, `XYLOPHONE`, `IRON_XYLOPHONE`, `COW_BELL`, `DIDGERIDOO`, `BIT`, `BANJO`, and `PLING`.

## Enhanced-Audio Resource Pack

Use [resource-pack-template/README.md](resource-pack-template/README.md) as the starting point for a separately hosted resource-pack ZIP.

1. Add appropriately licensed `.ogg` samples to the template's instrument and percussion paths.
2. Zip the **contents** of the template folder and host the ZIP over HTTPS.
3. Calculate the ZIP's SHA-1.
4. Set `enhanced-audio.enabled`, `resource-pack.url`, and `resource-pack.sha1` in `MidiBlock/config.yml`.

Players who decline an optional pack automatically remain on native note-block audio.

## Compatibility

- `plugin.yml` declares `api-version: '1.21'`.
- Runtime code uses stable Bukkit/Paper APIs and avoids NMS or CraftBukkit internals.
- The Java 21 plugin JAR works on supported 1.21.x servers and on Paper 26.x running its required Java 25 runtime.
- The enhanced resource pack is optional; native audio remains the compatibility baseline.

## Development

### Build From Source

```bash
gradle clean build
```

The release JAR is written to `build/libs/midiblock-0.1.0.jar`.

### Verification

```bash
gradle test
gradle clean build
```

The test suite covers MIDI tempo conversion, note releases, program changes, controller events, cache round-trips, and malformed source rejection.

## Version Information

| Component | Version |
|-----------|---------|
| Plugin Version | `0.1.0` |
| Supported Minecraft Versions | `1.21 - 1.21.11`, `26.1 - 26.2` |
| Paper API Baseline | `1.21` |
| Java | `21` for 1.21.x; `25` runtime for 26.x |
| Cache Format | `MBC3` |

## Operations And Safety

- MIDI compilation uses a worker thread; Bukkit sound calls stay on the server thread.
- Source MIDI files are capped at 32 MiB and cache writes are atomic.
- `playback.max-events-per-tick` bounds work after lag spikes; `max-voices-per-player` protects listeners from dense chords.
- Native note blocks cannot provide continuous pitch bends or true sustained samples. MidiBlock applies bend/volume to upcoming notes and uses gentle sustain refreshes as the closest native approximation.

## Support And Feedback

For now, use the project repository's issue tracker for bugs, MIDI files that map poorly, and feature requests.
