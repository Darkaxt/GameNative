from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

PREFIXES = (
    'canonical_detail_', 'canonical_copy_', 'canonical_copies_',
    'steam_match_', 'steam_resolution_',
)
FORMAT = re.compile(r'%(?:\d+\$)?[-#+ 0,(]*\d*(?:\.\d+)?[a-zA-Z]')


def resources(directory):
    result = {}
    for path in sorted(directory.glob('*.xml')):
        for node in ET.parse(path).getroot():
            name = node.attrib.get('name', '')
            if node.tag == 'string' and name.startswith(PREFIXES):
                if name in result:
                    raise AssertionError(f'{directory.name}: duplicate {name}')
                result[name] = ''.join(node.itertext())
    return result


def check(root):
    base = resources(root / 'values')
    assert base, 'No resolver/detail resources found'
    failures = []
    for locale in sorted(root.glob('values-*')):
        if not (locale / 'strings.xml').exists():
            continue
        translated = resources(locale)
        missing = sorted(base.keys() - translated.keys())
        if missing:
            failures.append(f'{locale.name}: {len(missing)} missing keys')
        for key in sorted(base.keys() & translated.keys()):
            expected = sorted(FORMAT.findall(base[key].replace('%%', '')))
            actual = sorted(FORMAT.findall(translated[key].replace('%%', '')))
            if expected != actual:
                failures.append(f'{locale.name}: mismatched format arguments for {key}')
            if not translated[key].strip():
                failures.append(f'{locale.name}: empty {key}')
    print(f'Checked {len(base)} resolver/detail keys')
    for failure in failures:
        print(failure)
    return not failures


if __name__ == '__main__':
    raise SystemExit(0 if check(Path(sys.argv[1])) else 1)
