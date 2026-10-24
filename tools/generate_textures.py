#!/usr/bin/env python3
"""Draws every texture defined in art/brewing_art.py (needs Pillow).

  python3 tools/generate_textures.py            # --check (default): verify every texture exists and matches
  python3 tools/generate_textures.py --write    # write every texture into src/main/resources
  python3 tools/generate_textures.py --preview sheet.png   # contact sheet at 8x for review
  python3 tools/generate_textures.py --project-icon --write  # upscale project-icon-128.png to project-icon.png
  python3 tools/generate_textures.py --project-icon --check  # verify the exact 4x upscale

Pixels are compared, not file bytes, so a re-save by an image tool does not count as a difference.
"""
import argparse
import sys
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / 'art'))
import brewing_art  # noqa: E402

RES = ROOT / 'src/main/resources'


def main():
    parser = argparse.ArgumentParser()
    group = parser.add_mutually_exclusive_group()
    group.add_argument('--write', action='store_true')
    group.add_argument('--check', action='store_true')
    group.add_argument('--preview')
    parser.add_argument('--project-icon', action='store_true',
                        help='use the 128x128 project icon source and its 512x512 nearest-neighbor export')
    args = parser.parse_args()
    output_root = RES
    label = 'textures'
    if args.project_icon:
        if args.preview:
            parser.error('--preview is only available for block and item textures')
        source_path = ROOT / 'project-icon-128.png'
        try:
            with Image.open(source_path) as source:
                if source.size != (128, 128):
                    parser.error('project-icon-128.png must be exactly 128x128 pixels')
                icon = source.convert('RGBA')
        except OSError as exc:
            parser.error(f'cannot read {source_path.name}: {exc}')
        textures = {'project-icon.png': icon.resize((512, 512), Image.Resampling.NEAREST)}
        output_root = ROOT
        label = 'project icons'
    else:
        textures = brewing_art.textures()
    if args.preview:
        tiles = [img for path, img in sorted(textures.items()) if img.size == (16, 16)]
        sheet = Image.new('RGBA', (len(tiles) * 136, 136), (40, 40, 40, 255))
        for i, img in enumerate(tiles):
            sheet.paste(img.resize((128, 128), Image.NEAREST), (i * 136 + 4, 4))
        sheet.save(args.preview)
        print(f'preview with {len(tiles)} textures: {args.preview}')
        return 0
    differing = []
    for rel, img in sorted(textures.items()):
        path = output_root / rel
        if args.write:
            path.parent.mkdir(parents=True, exist_ok=True)
            img.save(path, optimize=False)
            continue
        if not path.exists():
            differing.append(rel)
            continue
        current = Image.open(path).convert('RGBA')
        if current.size != img.size or current.tobytes() != img.convert('RGBA').tobytes():
            differing.append(rel)
    if args.write:
        print(f'wrote {len(textures)} {label}')
        return 0
    for rel in differing:
        print(f'differs {rel}')
    print(f'{len(textures) - len(differing)}/{len(textures)} {label} up to date')
    return 1 if differing else 0


if __name__ == '__main__':
    sys.exit(main())
