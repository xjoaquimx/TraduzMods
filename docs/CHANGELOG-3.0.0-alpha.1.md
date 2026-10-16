# TraduzMods 3.0.0-alpha.1

Esta atualização consolida o desenvolvimento local da alpha.5 e os portes Forge 1.20.1 e NeoForge 1.21.1. Disponibiliza **12 JARs**: Lite e Normal para Fabric 1.20.1, 1.21.1 e 26.2; NeoForge 1.21.1 e 26.1.2; Forge 1.20.1. Continua sendo um pré-lançamento.

## Interface e atualização de textos

- HUD compacto de 44 pixels, com fundo escuro, borda bege, indicador de estado e progresso turquesa. A vista expandida mantém pausa/retomada, minimização lateral e controle das traduções salvas.
- Contadores incluem a fila de fundo. O total usa o maior valor entre catálogo e salvos + pendentes; há saturação contra overflow e o percentual fica abaixo de 100% enquanto existir trabalho pendente.
- Quando AgentCraft está instalado, o painel desce 48 pixels para acomodar a barra de objetivos. Cliques só atingem controles visíveis com uma tela aberta.
- AgentCraft: tradução dos títulos antes da quebra de linhas e reticências; invalidação dos layouts do quadro e cartões quando a revisão do cache muda, agrupada em intervalos de 500 ms.
- Fabric 26.2: integração com a submissão diferida de texto de WorldUi para alcançar o quadro no mundo, além dos caminhos de fonte tradicionais.
- GuideMe: compatibilidade com a invalidação de layout de versões antigas, preservando as correções cumulativas de parágrafos, links, títulos e navegação.

## Integridade e custo de processamento

- Integrações opcionais verificam a estrutura da API antes de aplicar mixins; não tornam AgentCraft uma dependência obrigatória.
- Traduções alteram apenas a apresentação: os registros de tarefas, estado do Foreman e comandos não são reescritos.
- Proteção de trechos entre crases, flags CLI, hashtags, caminhos, arquivos, versões e nomes exibidos dos agentes. Comandos detectados como shell são preservados.
- Textos já localizados e rótulos canônicos evitam solicitações desnecessárias ao modelo. O HUD lê snapshots e não executa inferência ou acesso ao disco durante a renderização.
- Núcleo e testes sincronizados entre seis plataformas, com 200 comparações de paridade. Adaptadores de renderização mantêm as APIs específicas de cada Minecraft.
- Inferência local com Tencent Hy-MT2-1.8B GGUF Q4_K_M; cache em disco e prioridade de leitura mantidos. Lite atende sob demanda; Normal também processa o catálogo em segundo plano. Não foi medido um novo ganho percentual de velocidade nesta atualização.

## Compatibilidade de Forge

Forge 1.20.1 usa mínimo 47.4.0 e pack.mcmeta no formato 15, incluindo o ajuste do aviso ResourcePackInfo observado no Society: Sunlit Valley. Para testes isolados, as assinaturas inválidas do JAR remapeado de Forge são removidas apenas de uma cópia de fixture; o Forge instalado e os artefatos de produção não são modificados.

## Validação e limites

**Testes executados no PC do autor:** Windows 11 Pro, Ryzen 5 5600X (6 núcleos/12 threads), 16 GB de RAM, RTX 5060 de 8 GB (8151 MiB informados pelo driver).

Cada um dos 12 artefatos passou em 106 casos de integração: **1272 execuções, zero falhas**. A suíte usa provedor simulado para verificar filas, persistência, proteção e atualização da interface; não é um benchmark de inferência do modelo. Também foram conferidos metadados, edições, classes Java e presença dos mixins, além dos alvos reais do Minecraft 26.2 e AgentCraft (14 classes, 36 seletores, 6 campos).

Nos portes, o bytecode atual também foi conferido contra Minecraft/NeoForge 1.21.1 (10 classes, 29 seletores, 3 campos; mods opcionais ausentes) e Minecraft/Forge 1.20.1 com os mods disponíveis do Society (14 classes, 38 seletores, 8 campos; GuideMe e AgentCraft ausentes). Esses ensaios estruturais não equivalem a iniciar todos os modpacks.

O predecessor alpha.5 Normal iniciou no Fabulously Optimized com AgentCraft. O aceite visual completo e o teste de cada edição em cada modpack permanecem pendentes. Não há nova medição de FPS, latência ou consumo de RAM. Algumas entradas de catálogo ainda podem ser recusadas pela validação de tokens; não se afirma cobertura integral de todos os textos.

Java de execução do jogo: 17 para 1.20.1, 21 para 1.21.1, 25 para 26.x. Para compilar: Gradle executado com JDK 25, com JDK 17 e 21 disponíveis aos toolchains. Runtime automático de inferência validado em Windows x64.

Instale somente um JAR correspondente ao Minecraft, loader e edição. Remova o JAR anterior e preserve o cache. Modelo e runtime são baixados no primeiro uso e não são incluídos nos anexos. Manifesto, SHA-256 e relatório de validação acompanham esta publicação.
