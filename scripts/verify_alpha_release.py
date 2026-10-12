"""Verify alpha assets without downloading models or modifying a game instance."""
from pathlib import Path
import hashlib,json,zipfile,re,sys

root=Path(__file__).resolve().parent.parent
version=sys.argv[1] if len(sys.argv)>1 else '2.0.0-alpha.4'
assert re.fullmatch(r'2\.0\.0-alpha\.\d+',version),'Invalid release version'
release=root/'releases'/version
manifest=json.loads((release/'releases.json').read_text())
expected={(loader,mc,edition) for loader,mc in [('fabric','1.20.1'),('fabric','1.21.1'),('fabric','26.2'),('neoforge','26.1.2')] for edition in ['lite','normal']}
assert len(manifest)==8 and {(r['loader'],r['minecraft'],r['edition']) for r in manifest}==expected
for row in manifest:
    assert row['version']==version+'-'+row['edition']
    assert row['java']=={'1.20.1':17,'1.21.1':21,'26.1.2':25,'26.2':25}[row['minecraft']]
    jar=release/row['file'];data=jar.read_bytes()
    assert len(data)==row['bytes'] and hashlib.sha256(data).hexdigest()==row['sha256'],row['file']+' hash mismatch'
    with zipfile.ZipFile(jar) as archive:
        assert archive.read('traduzmods-edition.properties').decode().strip()=='edition='+row['edition']
        assert int.from_bytes(archive.read('com/modtranslator/core/TranslationService.class')[6:8],'big')==row['java']+44
        assert not any(name.endswith(('.gguf','.exe','.dll')) for name in archive.namelist())
        if row['loader']=='fabric':assert json.loads(archive.read('fabric.mod.json'))['version']==row['version']
        else:assert ('version="'+row['version']+'"') in archive.read('META-INF/neoforge.mods.toml').decode()
assert (release/'SHA256SUMS.txt').read_text().splitlines()==[row['sha256']+'  '+row['file'] for row in manifest]
report=json.loads((release/'VALIDACAO.json').read_text())
expected_cases=648 if version=='2.0.0-alpha.1' else 760 if version=='2.0.0-alpha.4' else sum(row['integrationPassed'] for row in manifest)
assert report['integrationPassed']==expected_cases and report['integrationFailed']==0
assert sum(row['integrationPassed'] for row in manifest)==expected_cases
assert report['hardware']['cpu']=='AMD Ryzen 5 5600X' and report['hardware']['testMachine']=='Project author personal PC'
if version=='2.0.0-alpha.1':assert report['atm11PlayTest']['authorReportedSatisfactoryUse']
else:assert report['visualAcceptance'] and report['atm11Launch']['versionConfirmed']
print(f'PASS: {version}, eight JAR hashes, editions, class targets and metadata; {expected_cases}-case report and author PC recorded.')
