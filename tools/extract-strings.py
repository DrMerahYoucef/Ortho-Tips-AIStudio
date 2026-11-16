#!/usr/bin/env python3
"""
Finds user-facing string literals still hardcoded in Kotlin.

Localizing this app is two jobs: the infrastructure (done) and moving roughly
700 literals out of 105 files (ongoing). This makes the second one repeatable and
reviewable rather than a single blind sweep.

It only reports. It never rewrites source, because deciding whether a literal is
user-facing needs judgement: "image/jpeg", "patient.diagnosis" and
"TdLibTelegramClient" all look like strings and none of them belong in
strings.xml. Run it, read the output, paste the keepers into strings.xml, then
replace those call sites by hand.

    python3 tools/extract-strings.py app/src/main/java > candidates.xml
"""
import os
import re
import sys

# Contexts where a literal is almost certainly shown to a person.
USER_FACING = re.compile(
    r'(?:'
    r'Text\(\s*"|'
    r'text\s*=\s*"|'
    r'label\s*=\s*\{\s*Text\(\s*"|'
    r'title\s*=\s*"|'
    r'subtitle\s*=\s*"|'
    r'placeholder\s*=\s*"|'
    r'placeholderText\s*=\s*"|'
    r'contentDescription\s*=\s*"|'
    r'message\s*=\s*"'
    r')([^"]{2,})"'
)

# Literals that look user-facing but are not.
SKIP = re.compile(
    r'^(?:'
    r'[a-z]+/[a-z0-9.+-]+|'
    r'[a-z]+\.[a-zA-Z.]+|'
    r'%[sd\d$]+|'
    r'[A-Z_]{3,}|'
    r'\W+|'
    r'\s*'
    r')$'
)


def slug(path, text):
    area = os.path.basename(path)[:-3]
    area = re.sub(r'(?<!^)(?=[A-Z])', '_', area).lower()
    area = area.replace('_screen', '').replace('_card', '')
    words = re.findall(r'[a-zA-Z]+', text.lower())[:4]
    return f"{area}_{'_'.join(words)}" if words else area


def main(root):
    rows = []
    for base, _, files in os.walk(root):
        for name in files:
            if not name.endswith('.kt'):
                continue
            path = os.path.join(base, name)
            with open(path, encoding='utf-8') as handle:
                for number, line in enumerate(handle, 1):
                    if 'stringResource' in line:
                        continue
                    for match in USER_FACING.finditer(line):
                        value = match.group(1)
                        if SKIP.match(value):
                            continue
                        rows.append((path, number, slug(path, value), value))

    print(f'<!-- {len(rows)} candidates across {len({r[0] for r in rows})} files -->')
    seen = set()
    for path, number, name, value in sorted(rows):
        key, suffix = name, 2
        while key in seen:
            key = f'{name}_{suffix}'
            suffix += 1
        seen.add(key)
        escaped = value.replace('&', '&amp;').replace('<', '&lt;').replace("'", "\\'")
        print(f'<!-- {path}:{number} -->')
        print(f'<string name="{key}">{escaped}</string>')


if __name__ == '__main__':
    main(sys.argv[1] if len(sys.argv) > 1 else 'app/src/main/java')
