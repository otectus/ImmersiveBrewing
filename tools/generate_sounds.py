#!/usr/bin/env python3
"""Encodes the sounds defined in art/brewing_audio.py to OGG Vorbis (needs numpy and ffmpeg with libvorbis), and writes
sounds.json with a subtitle for every event.

  python3 tools/generate_sounds.py            # --check (default): verify every sound file and sounds.json match
  python3 tools/generate_sounds.py --write    # encode every sound and write sounds.json

Encoding is bit-exact (fixed quality, no metadata), so --check can compare bytes.
"""
import argparse
import json
import subprocess
import sys
import tempfile
import wave
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / 'art'))
import brewing_audio  # noqa: E402

ASSETS = ROOT / 'src/main/resources/assets/immersive_brewing'
SOUNDS = ASSETS / 'sounds'


def encode(samples):
    pcm = (np.clip(samples, -1, 1) * 32767).astype('<i2').tobytes()
    with tempfile.TemporaryDirectory() as tmp:
        wav = Path(tmp) / 'in.wav'
        ogg = Path(tmp) / 'out.ogg'
        with wave.open(str(wav), 'wb') as w:
            w.setnchannels(1)
            w.setsampwidth(2)
            w.setframerate(brewing_audio.RATE)
            w.writeframes(pcm)
        subprocess.run(['ffmpeg', '-hide_banner', '-loglevel', 'error', '-y', '-i', str(wav), '-c:a', 'libvorbis', '-q:a', '4',
                        '-map_metadata', '-1', '-fflags', '+bitexact', '-flags:a', '+bitexact', str(ogg)], check=True)
        return ogg.read_bytes()


def sounds_json():
    out = {}
    for name in sorted(brewing_audio.SOUNDS):
        event = name.replace('/', '.')
        out[event] = {'subtitle': f'subtitles.immersive_brewing.{event}', 'sounds': [{'name': f'immersive_brewing:{name}'}]}
    return json.dumps(out, indent=2) + '\n'


def main():
    parser = argparse.ArgumentParser()
    group = parser.add_mutually_exclusive_group()
    group.add_argument('--write', action='store_true')
    group.add_argument('--check', action='store_true')
    args = parser.parse_args()
    sounds = brewing_audio.sounds()
    differing = []
    for name, samples in sorted(sounds.items()):
        data = encode(samples)
        path = SOUNDS / f'{name}.ogg'
        if args.write:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
        elif not path.exists() or path.read_bytes() != data:
            differing.append(name)
    manifest = ASSETS / 'sounds.json'
    if args.write:
        manifest.write_text(sounds_json())
        print(f'wrote {len(sounds)} sounds and sounds.json')
        return 0
    if not manifest.exists() or manifest.read_text() != sounds_json():
        differing.append('sounds.json')
    for name in differing:
        print(f'differs {name}')
    print(f'{len(sounds) + 1 - len(differing)}/{len(sounds) + 1} sound files up to date')
    return 1 if differing else 0


if __name__ == '__main__':
    sys.exit(main())
