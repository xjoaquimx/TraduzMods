"""Check shared core parity and published JAR hashes without downloading dependencies."""
from pathlib import Path
import hashlib
import json

root = Path(__file__).resolve().parent.parent
canonical = root / 'versions/fabric-1.20.1/common/src/main/java/com/modtranslator/core'
failures = []
checked = 0
for variant in ['fabric-26.2', 'neoforge-26.1.2']:
    target = root / 'versions' / variant / 'common/src/main/java/com/modtranslator/core'
    if {p.name for p in target.glob('*.java')} != {p.name for p in canonical.glob('*.java')}:
        failures.append(variant + ': core file list differs')
    for source in canonical.glob('*.java'):
        if source.name == 'QuestCatalog.java':
            continue  # Minecraft NBT APIs differ between platforms.
        expected = source.read_text(encoding='utf-8').replace('ResourceLocation', 'Identifier')
        actual = target / source.name
        if not actual.exists() or actual.read_text(encoding='utf-8') != expected:
            failures.append(variant + '/' + source.name)
        checked += 1
release = root / 'releases/1.0.0'
artifacts = json.loads((release / 'releases.json').read_text(encoding='utf-8'))
for artifact in artifacts:
    path = release / artifact['file']
    if not path.exists() or hashlib.sha256(path.read_bytes()).hexdigest() != artifact['sha256']:
        failures.append(artifact['file'] + ': SHA-256 mismatch')
if failures:
    raise SystemExit('FAIL: ' + ', '.join(failures))
print(f'PASS: {checked} shared core comparisons and {len(artifacts)} JAR hashes')
