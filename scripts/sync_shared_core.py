"""Check platform parity; --write explicitly propagates the canonical implementation.
QuestCatalog and rendering/mixin adapters retain their Minecraft-specific APIs.
"""
from pathlib import Path
import argparse

root=Path(__file__).resolve().parent.parent
base=root/'versions/fabric-1.20.1'
targets=[('fabric-1.21.1','fabric','ResourceLocation'),('fabric-26.2','fabric','Identifier'),('neoforge-26.1.2','neoforge','Identifier')]
parser=argparse.ArgumentParser();parser.add_argument('--write',action='store_true');args=parser.parse_args()
failures=[];checked=0
for version,loader,resource_type in targets:
    destination=root/'versions'/version
    files=[path.relative_to(base) for path in (base/'common/src/main/java/com/modtranslator/core').glob('*.java') if path.name!='QuestCatalog.java']
    files += [Path('common/src/main/java/com/modtranslator/client')/name for name in ['TranslationProgressLayout.java','TranslationProgressControl.java','TranslationPanelMotion.java','AetherPageRefresh.java','GuideMePageRefresh.java','NpcDialogueText.java']]
    files += [Path('common/src/main/java/com/modtranslator/mixin')/name for name in ['AetherDocumentMixin.java','GuideMeDocumentMixin.java']]
    for relative in files:
        content=(base/relative).read_text(encoding='utf-8').replace('ResourceLocation',resource_type)
        path=destination/relative
        if args.write:path.write_text(content,encoding='utf-8',newline='\n')
        if not path.exists() or path.read_text(encoding='utf-8')!=content:failures.append(str(path.relative_to(root)))
        checked+=1
    for name in ['TranslationServiceIntegrationTest.java','OptimizationChecks.java','OptimizationBenchmark.java','ModelComparisonFixtures.java','NativeOptimizationSmoke.java','PersistenceBenchmark.java']:
        content=(base/'fabric/src/test/java/com/modtranslator/core'/name).read_text(encoding='utf-8').replace('ResourceLocation',resource_type).replace('new '+resource_type+'(',resource_type+'.fromNamespaceAndPath(')
        path=destination/loader/'src/test/java/com/modtranslator/core'/name
        if args.write:path.write_text(content,encoding='utf-8',newline='\n')
        if not path.exists() or path.read_text(encoding='utf-8')!=content:failures.append(str(path.relative_to(root)))
        checked+=1
if failures:raise SystemExit('FAIL: '+', '.join(failures))
print(f'PASS: {checked} shared source/test comparisons; platform adapters preserved')
