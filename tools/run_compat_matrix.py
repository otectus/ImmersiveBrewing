#!/usr/bin/env python3
"""Serial, versioned GameTest matrix. IB_COMPAT_FIXTURES_DIR may supply licensed jars on a private CI runner.
Config experiments are restored byte-for-byte even when a test fails. Logs/reports contain no dependency jars.
"""
import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CASES = {
    'core': [],
    'apothecary': ['withApothecary'],
    'morepotions': ['withMorePotions'],
    'potionsmaster': ['withPotionsMaster'],
    'rpgpotions': ['withRpgPotions'],
    'collision': ['withApothecary', 'withMorePotions'],
    'combined': ['withPotionMods', 'withSmithing', 'withTanning', 'withJewelry', 'withEnchanting'],
    'apothecary_config': ['withApothecary', 'apothecaryConfigFixture'],
}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('cases', nargs='*', help='Cases: ' + ', '.join(CASES))
    args = parser.parse_args()
    if any(case not in CASES for case in args.cases):
        parser.error('Unknown compatibility case')
    (ROOT / 'libs').mkdir(exist_ok=True)
    fixture_dir = os.environ.get('IB_COMPAT_FIXTURES_DIR')
    manifest = json.loads((ROOT / 'tools/compat-fixtures.json').read_text())
    for fixture in manifest['fixtures']:
        path = ROOT / 'libs' / fixture['file']
        if not path.exists() and fixture_dir:
            shutil.copy2(Path(fixture_dir) / fixture['file'], path)
        assert path.is_file(), f'Provision fixture separately: {path.name}'
        assert hashlib.sha256(path.read_bytes()).hexdigest() == fixture['sha256'], f'Unexpected jar: {path}'
    out = ROOT / 'build/compat-matrix'
    out.mkdir(parents=True, exist_ok=True)
    quiet = Path('/home/otectus/Projects/.mcmod-tools/gradlew-quiet.sh')
    command = [str(quiet), str(ROOT)] if quiet.exists() else [str(ROOT / 'gradlew'), '--no-daemon']
    results = {}
    for case in args.cases or CASES:
        # Separate worlds prevent one case's removed registries leaking into the next case's logs.
        run_dir = ROOT / 'run-gametest/matrix' / case
        config = run_dir / 'config/apothecary-common.toml'
        if case == 'apothecary_config' and not config.exists():
            baseline = ROOT / 'run-gametest/matrix/apothecary/config/apothecary-common.toml'
            if not baseline.exists():
                baseline = ROOT / 'run-gametest/config/apothecary-common.toml'
            assert baseline.exists(), 'Run apothecary once first to create its normal config'
            config.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(baseline, config)
        old = config.read_bytes() if config.exists() else None
        try:
            if case == 'apothecary_config':
                assert old is not None, 'Run apothecary once first to create its normal config'
                text = old.decode()
                text, stacks = re.subn(r'(?m)^(\s*Stack_Size\s*=\s*)\d+', r'\g<1>16', text)
                text, ingredient = re.subn(r'(\[Potions\.Iron_Skin\][\s\S]*?Ingredient\s*=\s*)"[^"]+"', r'\g<1>"minecraft:iron_ingot"', text, count=1)
                assert stacks == 1 and ingredient == 1, 'Fixture config structure changed'
                config.write_text(text)
            cmd = command + ['runGameTestServer', '-PgameTestDirectory=' + str(run_dir)] + ['-P' + flag for flag in CASES[case]]
            with (out / (case + '.log')).open('w') as log:
                result = subprocess.run(cmd, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT)
            latest = run_dir / 'logs/latest.log'
            if latest.exists():
                shutil.copy2(latest, out / (case + '-server.log'))
            report = ROOT / 'docs/reports/gametest-coverage-report.txt'
            if report.exists():
                shutil.copy2(report, out / (case + '-coverage.txt'))
            results[case] = {'passed': result.returncode == 0, 'command': cmd, 'exit_code': result.returncode}
            print(case, 'PASS' if result.returncode == 0 else 'FAIL', flush=True)
        finally:
            if old is not None:
                config.write_bytes(old)
            elif case == 'apothecary_config' and config.exists():
                config.unlink()
        (out / 'results.json').write_text(json.dumps(results, indent=2) + '\n')
    raise SystemExit(0 if all(result['passed'] for result in results.values()) else 1)


if __name__ == '__main__':
    main()
