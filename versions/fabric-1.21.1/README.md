# TraduzMods 2.0.0-alpha.1 — Fabric 1.21.1

Adaptador cliente para Minecraft 1.21.1, Fabric Loader 0.19+ e Java 21+. Há edições Lite e Normal, com o mesmo núcleo de tradução local Tencent Hy-MT2-1.8B Q4_K_M. Instale somente uma edição.

Este adaptador faz parte do desenvolvimento da 2.0.0. A Release estável 1.0.0 não contém uma versão para Minecraft 1.21.1. Consulte [o estado e as políticas atuais](../../docs/DESENVOLVIMENTO-2.0.md) e [os ensaios e limites](../../docs/BENCHMARK-2.0.md).

As duas edições foram compiladas e passaram na suíte de integração. O cliente real 1.21.1 carregou os mixins principais e exercitou o painel com fontes, botões e contagens simuladas. As integrações opcionais de FTB Quests e FancyMenu ainda precisam de ensaios com esses mods instalados antes de um lançamento estável.

Feche o jogo, retire o JAR anterior da pasta mods, coloque o JAR correspondente a Fabric 1.21.1 e selecione Português (Brasil). Mantenha config/modtranslator para reutilizar suas traduções e preferências. O primeiro uso baixa modelo e runtime; depois, a inferência funciona localmente.

O projeto Gradle fica em fabric, e o núcleo compartilhado em common. Use JDK 25 para executar o Gradle; as classes geradas têm alvo Java 21.

```powershell
.\gradlew.bat build integrationTest -Pedition=normal '-Pmod_version=2.0.0-alpha.1-normal'
.\gradlew.bat build integrationTest -Pedition=lite '-Pmod_version=2.0.0-alpha.1-lite'
```

Licença MIT. Modelos, runtimes e dados do jogador não são incluídos no código-fonte ou no JAR.
