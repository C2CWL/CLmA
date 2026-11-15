#!/usr/bin/env python3
"""Collect only PRs reachable in a release range; bundle reviewed notes verbatim."""
import argparse
import json
from pathlib import Path
import re
import subprocess
import zipfile

ASSET_DIR = 'app/src/main/assets/changelog'


def asset_path(version):
    if not re.fullmatch(r'\d+\.\d+', version):
        raise ValueError('version must be X.Y')
    return f'{ASSET_DIR}/{version}.txt'


def select_prs(prs, commits):
    """No published-at cutoff: a release may have been published long after its commit."""
    return sorted((p for p in prs if (p.get('mergeCommit') or {}).get('oid') in commits
                   and not re.match(r'^(?:chore\(release\)|ci|release)(?:[:(])', p['title'], re.I)),
                  key=lambda p: p['number'])


def gh(*args):
    return subprocess.run(['gh', *args], check=True, text=True, capture_output=True).stdout


def collect(repo, head, base=None):
    if base is None:
        base = json.loads(gh('api', f'repos/{repo}/releases/latest'))['tag_name']
    # --paginate follows compare's commit pagination, rather than silently truncating at 250.
    commits = set(gh('api', '--paginate', f'repos/{repo}/compare/{base}...{head}?per_page=100',
                     '--jq', '.commits[].sha').splitlines())
    prs = json.loads(gh('pr', 'list', '-R', repo, '--state', 'merged', '--limit', '1000',
                        '--json', 'number,title,url,mergeCommit'))
    if len(prs) >= 1000:
        raise ValueError('PR listing reached limit; narrow the release range before drafting')
    return {'base': base, 'head': head, 'prs': select_prs(prs, commits)}


def draft(version, data):
    # Do not invent translations or user-visible effects from technical titles.
    # The existing release linter rejects TODOs/English-only Chinese bullets until reviewed.
    lines = [f'## CLmA {version}', '']
    lines += [f"- {p['title']} (#{p['number']})" for p in data['prs']]
    lines += ['', '## Changes']
    lines += [f"- TODO: review English summary for #{p['number']}" for p in data['prs']]
    return '\n'.join(lines) + '\n'


def bundled_files(version, notes):
    if re.search(r'\bTODO\b', notes, re.I):
        raise ValueError('review draft TODOs before release')
    if not notes.strip():
        raise ValueError('release notes must not be empty')
    if not notes.startswith(f'## CLmA {version}\n'):
        raise ValueError('release notes version does not match target')
    return {asset_path(version): notes}


def verify_apk(apk, version, notes):
    with zipfile.ZipFile(apk) as archive:
        actual = archive.read(f'assets/changelog/{version}.txt').decode('utf-8')
    if actual != notes:
        raise ValueError('APK changelog differs from Release notes; rebuild before publishing')


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--repo', default='C2CWL/CLmA')
    ap.add_argument('--head', required=True, help='immutable release preparation SHA')
    ap.add_argument('--base', help='previous release tag; defaults to latest published release')
    ap.add_argument('--version', required=True)
    ap.add_argument('--out', required=True)
    args = ap.parse_args()
    asset_path(args.version)
    data = collect(args.repo, args.head, args.base)
    Path(args.out).write_text(draft(args.version, data), encoding='utf-8')
    Path(args.out + '.sources.json').write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(f"Prepared {len(data['prs'])} PRs from {data['base']} to {data['head']}; review notes before release.")


if __name__ == '__main__':
    main()
