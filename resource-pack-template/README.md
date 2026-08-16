# MidiBlock enhanced-audio pack template

This folder is the starting layout for a **separately hosted resource-pack ZIP**. Add appropriately licensed `.ogg` samples beneath:

```text
assets/midiblock/sounds/instruments/<name>.ogg
assets/midiblock/sounds/percussion/<name>.ogg
```

The names must match `assets/midiblock/sounds.json`. Zip the contents of this folder (not the enclosing folder), host the ZIP using HTTPS, calculate its SHA-1, then set `enhanced-audio.resource-pack.url` and `sha1` in MidiBlock's `config.yml`.

The supplied `pack_format` targets Minecraft 1.21.4. Before publishing, update it to the appropriate supported format/range for the Minecraft versions your pack will serve. No audio files are bundled here: choosing and licensing the samples is intentionally a server-owner decision.
