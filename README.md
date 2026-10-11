# TraduzMods 1.0.0

Tradução automática de textos de mods para **português do Brasil**, feita localmente no computador. Usa as traduções nativas disponíveis, prioriza os textos que você abre e salva os resultados em disco para reutilizar nas próximas sessões.

O modelo é **Tencent Hy-MT2-1.8B GGUF Q4_K_M**. Não precisa de chave de API. Modelo e runtime são baixados no primeiro uso; depois, a inferência é local. Esses arquivos não estão incluídos nos JARs deste repositório. O download automático do runtime atualmente atende **Windows x64**; outras plataformas não foram validadas nesta versão.

## Download

| Minecraft | Loader | Lite | Normal |
| --- | --- | --- | --- |
| 1.20.1 | Fabric | [Baixar](releases/1.0.0/traduzmods-fabric-1.0.0-lite-mc1.20.1.jar) | [Baixar](releases/1.0.0/traduzmods-fabric-1.0.0-normal-mc1.20.1.jar) |
| 26.2 | Fabric | [Baixar](releases/1.0.0/traduzmods-fabric-1.0.0-lite-mc26.2.jar) | [Baixar](releases/1.0.0/traduzmods-fabric-1.0.0-normal-mc26.2.jar) |
| 26.1.2 | NeoForge | [Baixar](releases/1.0.0/traduzmods-neoforge-1.0.0-lite-mc26.1.2.jar) | [Baixar](releases/1.0.0/traduzmods-neoforge-1.0.0-normal-mc26.1.2.jar) |

**Lite:** atende os textos consultados com um perfil conservador de recursos. **Normal:** também traduz o catálogo em segundo plano e adapta seu perfil aos recursos disponíveis. Ambas usam o mesmo modelo e cache; veja os limites no [guia](docs/GUIA.md).

Feche o jogo, coloque **um único JAR** da edição e plataforma correspondentes na pasta `mods` e selecione Português (Brasil). Lite e Normal não podem ser instaladas juntas. Remova o JAR anterior ao atualizar; mantenha o cache.

## Recursos

- Detecção de textos dos mods instalados e aproveitamento de traduções nativas.
- Prioridade para textos visíveis, incluindo integrações com FTB Quests e diálogos compatíveis.
- Traduções persistidas em disco, com preservação de cores e formatação.
- Painel de progresso com pausa/retomada, minimização e controle da exibição das traduções salvas.
- Limites de memória e fila; sem espera fixa de proteção nem contagem regressiva.

Textos desenhados em imagens e alguns componentes dinâmicos incompatíveis podem permanecer no idioma original. A tradução automática pode conter erros; não há promessa de tradução instantânea ou de FPS específico.

## Fontes e compilação

Os projetos estão em `versions/fabric-1.20.1`, `versions/fabric-26.2` e `versions/neoforge-26.1.2`. Cada projeto contém `common` e a pasta do loader. O núcleo é padronizado; as diferenças de API do Minecraft ficam nos adaptadores de plataforma.

Use **JDK 25 para executar o Gradle**. A versão Fabric 1.20.1 gera classes Java 17 e seus testes precisam de uma instalação JDK 17 detectável pelo Gradle; as outras duas plataformas usam Java 25. O wrapper baixa o Gradle e as dependências na primeira compilação.

Exemplo no Windows, na pasta `versions/fabric-1.20.1/fabric`:

```powershell
.\gradlew.bat build "-Pmod_version=1.0.0-lite" -Pedition=lite
.\gradlew.bat build "-Pmod_version=1.0.0-normal" -Pedition=normal
.\gradlew.bat integrationTest -Pedition=normal
```

Nos demais projetos, entre na pasta `fabric` ou `neoforge` correspondente e use os mesmos parâmetros. No Linux/macOS, use `./gradlew` no lugar de `gradlew.bat`. Os JARs compilados ficam em `build/libs`. O arquivo de edição é gerado na compilação; o identificador do mod é `modtranslator` em todas as variantes.

Verifique a padronização do núcleo e os hashes dos seis JARs distribuídos com:

```text
python scripts/verify_release.py
```

## Documentação e validação

- [Guia completo](docs/GUIA.md)
- [Validação da versão 1.0.0](docs/VALIDACAO.md): 450 casos passaram, zero falhas; limites do ensaio estão documentados.
- Alterações das edições [Lite](docs/CHANGELOG-lite.md) e [Normal](docs/CHANGELOG-normal.md).
- [Hashes SHA-256](releases/1.0.0/SHA256SUMS.txt).

Licença [MIT](LICENSE) para o código do TraduzMods. Minecraft, bibliotecas, runtime de inferência e modelo têm suas próprias licenças. Configurações pessoais, caches, mundos e arquivos do modelo não são distribuídos aqui.
