# Vanilla sound catalog tool

`vanilla_sound_catalog.py` analyzes locally installed official Minecraft client sound assets and writes metadata only. It never copies or packages `.ogg` files.

Generate the initial 1.21.4 tonal candidate catalog:

```bash
python3 tools/vanilla_sound_catalog.py \
  --minecraft-dir "/Users/ryder/Library/Application Support/minecraft" \
  --version 1.21.4 \
  --output sound-profiles/1.21.4-candidates.yml
```

The default candidate set favors potentially tonal events such as note blocks, bells, chimes, amethyst, allay, sculk, and selected ambient sources. Use `--all-events` for a broad inventory or `--limit 25` for a quick trial run.

Treat generated pitch and tonal-confidence values as a triage signal, not a final musical verdict. Review candidates in-game before promoting them into a production orchestra profile.
