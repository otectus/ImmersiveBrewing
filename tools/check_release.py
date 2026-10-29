#!/usr/bin/env python3
"""Check locale parity, fixture checksums and/or the contents of a built release jar. Never downloads dependencies."""
import argparse
import hashlib
import json
import re
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CJK = re.compile(r'[\u1100-\u11ff\u2e80-\u33ff\u3400-\u4dbf\u4e00-\u9fff\ua000-\ua4cf\ua960-\ua97f'
                 r'\uac00-\ud7ff\uf900-\ufaff\ufe10-\ufe1f\ufe30-\ufe4f\uff66-\uff9f\U0001b000-\U0001b2ff\U00020000-\U0003ffff]')


def check_locale_content(locale, entries, source):
    """Check decoded JSON, so escaped Unicode cannot bypass the language boundary."""
    if locale.startswith('zh_'):
        return
    for key, text in entries.items():
        assert not CJK.search(key + text), f'{source}: unexpected CJK content in {key}'


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--jar', type=Path)
    parser.add_argument('--fixtures', action='store_true')
    args = parser.parse_args()
    locales = ROOT / 'src/main/resources/assets/immersive_brewing/lang'
    english = json.loads((locales / 'en_us.json').read_text())
    placeholders = lambda text: sorted(re.findall(r'%(?:\d+\$)?[sd]', text))
    locale_files = sorted((ROOT / 'src').glob('**/resources/assets/*/lang/*.json'))
    for directory in sorted({path.parent for path in locale_files}):
        baseline = json.loads((directory / 'en_us.json').read_text())
        for path in sorted(directory.glob('*.json')):
            translated = json.loads(path.read_text())
            assert translated.keys() == baseline.keys(), f'{path}: missing/extra keys: {translated.keys() ^ baseline.keys()}'
            for key, text in baseline.items():
                assert placeholders(text) == placeholders(translated[key]), f'{path}: {key}: format placeholders differ'
    print(f'Locales: {len(english)} keys in all five maintained languages; placeholders match')
    for path in locale_files:
        check_locale_content(path.stem, json.loads(path.read_text()), path.relative_to(ROOT))
    print(f'Locale content: {len(locale_files)} source files checked; CJK only in Chinese locales')
    if args.fixtures:
        for fixture in json.loads((ROOT / 'tools/compat-fixtures.json').read_text())['fixtures']:
            path = ROOT / 'libs' / fixture['file']
            assert path.is_file(), f'Missing separately provisioned fixture: {path}'
            assert hashlib.sha256(path.read_bytes()).hexdigest() == fixture['sha256'], f'Checksum mismatch: {path}'
        print('Optional fixture checksums match the recorded matrix')
    if args.jar:
        version = re.search(r'^mod_version=(.+)$', (ROOT / 'gradle.properties').read_text(), re.M)[1]
        with zipfile.ZipFile(args.jar) as jar:
            names = jar.namelist()
            assert not any(n.endswith('.jar') for n in names), 'Nested dependency jar found'
            assert not any('/smoketest/' in n or '/test/' in n or '/testing/' in n or n.startswith('migration/') for n in names), 'Development-only content shipped'
            assert all(n.startswith('com/otectus/immersivebrewing/') for n in names if n.endswith('.class')), 'Foreign classes bundled'
            for name in names:
                if re.fullmatch(r'assets/[^/]+/lang/[^/]+\.json', name):
                    entries = json.loads(jar.read(name))
                    check_locale_content(Path(name).stem, entries, name)
                    assert not any(key.startswith('smoketest.') for key in entries), 'Test-only translations shipped'
            assert f'version="{version}"' in jar.read('META-INF/mods.toml').decode(), 'Wrong mod version'
            assert not any('apothecary' in n and n.endswith('.class') and not n.startswith('com/otectus/immersivebrewing/') for n in names)
        print(f'Release jar: {args.jar.name}, version {version}; no test code or third-party jars/classes')


if __name__ == '__main__':
    main()
