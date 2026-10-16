"""Check all current release assets without running Minecraft or model inference."""
from pathlib import Path
import hashlib,json,zipfile
root=Path(__file__).resolve().parent.parent;version='3.0.0-alpha.1';directory=root/'releases'/version
manifest=json.loads((directory/'releases.json').read_text(encoding='utf-8'))
platforms=[('fabric','1.20.1'),('fabric','1.21.1'),('fabric','26.2'),('neoforge','1.21.1'),('neoforge','26.1.2'),('forge','1.20.1')]
assert len(manifest)==12 and {(r['loader'],r['minecraft'],r['edition']) for r in manifest}=={(l,m,e) for l,m in platforms for e in ['normal','lite']}
classes={}
for row in manifest:
    path=directory/row['file'];data=path.read_bytes()
    assert len(data)==row['bytes'] and hashlib.sha256(data).hexdigest()==row['sha256']
    assert row['version']==version+'-'+row['edition'] and row['integrationPassed']==106
    with zipfile.ZipFile(path) as z:
        assert z.read('traduzmods-edition.properties').decode().strip()=='edition='+row['edition']
        assert not any(p.endswith(('.gguf','.exe','.dll')) for p in z.namelist())
        cls={p:z.read(p) for p in z.namelist() if p.endswith('.class')}
        assert all(int.from_bytes(b[6:8],'big')<=row['java']+44 for b in cls.values())
        key=row['loader'],row['minecraft']
        if key in classes:assert cls==classes[key],str(key)+' editions have divergent production bytecode'
        classes[key]=cls
        if row['loader']=='fabric':assert json.loads(z.read('fabric.mod.json'))['version']==row['version']
        else:
            meta=z.read('META-INF/'+('mods.toml' if row['loader']=='forge' else 'neoforge.mods.toml')).decode()
            assert ('version="'+row['version']+'"' in meta or 'version = "'+row['version']+'"' in meta)
        if row['loader']=='forge':
            assert '[47.4.0,48)' in meta
            assert json.loads(z.read('pack.mcmeta'))['pack']['pack_format']==15
assert (directory/'SHA256SUMS.txt').read_text().splitlines()==[r['sha256']+'  '+r['file'] for r in manifest]
report=json.loads((directory/'VALIDACAO.json').read_text(encoding='utf-8'))
assert report['variantCount']==12 and report['integrationPassed']==1272 and report['integrationFailed']==0
assert report['hardware']['testMachine']=='Project author personal PC' and report['testsUseMockProvider']
assert report['visualAcceptancePending'] and not report['newNativeModelBenchmark']
for directory in (root/'releases').iterdir():
    p=directory/'PROVENIENCIA.json'
    if not p.exists():continue
    for row in json.loads(p.read_text(encoding='utf-8'))['artifacts']:
        assert hashlib.sha256((directory/row['file']).read_bytes()).hexdigest()==row['sha256']
print('PASS: 12 current JARs, 6 Lite/Normal bytecode pairs, metadata, class targets, SHA-256, 1272-case report and archived originals.')
