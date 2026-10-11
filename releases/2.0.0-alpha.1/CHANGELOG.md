# TraduzMods 2.0.0-alpha.1 — alterações técnicas

Pré-lançamento da linha 2.0, comparado à 1.0.0. O código de tradução continua local, com Tencent Hy-MT2-1.8B Q4_K_M e runtime llama.cpp b11429. São oito JARs: Lite e Normal para quatro combinações de loader/Minecraft. A Release 1.0.0 e seus seis artefatos permanecem preservados.

## Ambiente dos testes: PC do autor

**Todos os ensaios locais citados nesta versão foram executados no PC do autor do projeto.** Os resultados dependem desse hardware, da memória disponível, do backend e dos textos usados.

| Componente | Configuração |
| --- | --- |
| CPU | AMD Ryzen 5 5600X, 6 núcleos / 12 threads |
| RAM | 16 GB instalados; 16294 MiB físicos reportados pelo sistema |
| GPU | NVIDIA GeForce RTX 5060 |
| VRAM | 8151 MiB reportados pelo driver, placa de 8 GB |
| Driver NVIDIA | 617.42 |
| Sistema | Windows 11 Pro, versão 10.0.26200 / build 26200 |
| Inferência | llama.cpp b11429; Hy-MT2-1.8B Q4_K_M |
| Compilação | Gradle 9.7.1 executado com JDK 25; alvos Java 17, 21 e 25 conforme a plataforma |

Os microbenchmarks e ensaios nativos usaram diretórios isolados. Na confirmação final de inferência, os aplicativos de desktop permaneceram abertos e não havia outro Minecraft concorrente. O teste integrado ao ATM11 é registrado separadamente. Não foi medido FPS.

## Fila e seleção de trabalho

- `IndexedWorkDeque` substitui varreduras lineares da fila por uma lista duplamente ligada com índice `HashMap`. Cada texto tem um único nó; promoção, remoção, consulta de presença e tamanho têm custo esperado O(1). As operações são sincronizadas; iteradores usam snapshots.
- Um trabalho já admitido pode receber prioridade mesmo quando o limite de novos trabalhos foi atingido. Flags de execução e promoção evitam duplicação entre a renderização e o worker.
- A fila `canonicalReady` permite aplicar rótulos conhecidos sem depender da disponibilidade do modelo. O bloqueio de inferência por recursos não bloqueia esses resultados determinísticos.
- O catálogo cede prioridade às consultas visíveis. A passagem ocorre entre trechos de inferência; resultados já concluídos são persistidos, e o restante do lote volta à fila.

## Terminologia, prompt e reutilização

- `TerminologyIndex` utiliza um autômato de múltiplos padrões com transições esparsas para selecionar candidatos do glossário. As regras finais conservam limites de palavras, precedência e plurais. Texto Unicode mantém a busca completa para conservar a equivalência com as expressões regulares.
- `TranslationPrompt` reduz a instrução enviada ao modelo. O perfil compacto conserva até 400 caracteres de referências de nomes nativos; instruções de marcadores entram apenas quando necessárias.
- `TranslationMemo` adiciona um LRU de parágrafos, confinado ao worker: até 256 entradas e carga estimada de 128 KiB na Lite / 512 KiB na Normal. A chave inclui o prompt completo, suas referências e seus marcadores, evitando reutilizar uma tradução sob outro contexto.
- Marcadores protegidos são validados antes da admissão no memo. O cache de textos completos em disco continua sendo a persistência principal. O memo evita inferência repetida durante a sessão; não substitui o cache em disco.
- A inferência continua serializada em um único slot. `cache_prompt=true` reutiliza o KV desse slot, enquanto `--cache-ram 0` mantém desativado o cache adicional do servidor. As threads do modelo executam o cálculo interno; não representam vários servidores ou pedidos simultâneos.

## Execução local e política de memória

- CUDA passa a ser elegível também no perfil compacto da Normal. Os gráficos CUDA ficam habilitados; o offload usa `--load-mode none` para liberar páginas de pesos no host após o upload à GPU, conforme ensaio no runtime fixado.
- A Normal seleciona o perfil compacto quando não há espaço para a janela completa. Nesse perfil, também pode concluir o catálogo em segundo plano, um texto por pedido. Durante a leitura, o catálogo fica suspenso e o texto aberto recebe prioridade. A Lite continua atendendo apenas os textos consultados.
- Uma tela de leitura mantém o modelo compacto pronto entre páginas, evitando descarregamento apenas por superar a janela de 20 segundos de ociosidade. As verificações de recursos continuam ativas.
- A admissão e o supervisor utilizam o orçamento do perfil efetivamente iniciado. A interface mostra a reserva aplicável à consulta/fallback, em vez de exigir sempre a reserva do perfil completo.

| Parâmetro do perfil compacto | CPU | CUDA, edição Normal |
| --- | ---: | ---: |
| Contexto | 1024 tokens | 1024 tokens |
| Batch / microbatch | 64 / 32 | 64 / 32 |
| Threads | Até 2 | Até 2 |
| Orçamento supervisionado do processo | 1792 MiB | 2560 MiB |
| RAM livre para admissão | 2176 MiB | 1536 MiB |
| Reserva de RAM durante execução | 384 MiB | 768 MiB |
| VRAM livre para admissão | — | 3072 MiB |

A seleção CUDA automática requer Windows x64 e driver compatível, com major >= 580. O perfil completo da Normal mantém contexto de 4096 tokens e alocação adaptativa de threads/buffers. Seu teto de memória é 8192 MiB; o orçamento concreto depende da RAM total/livre e do backend. GPU, memória residente e compromisso privado do processo são medidas diferentes. Os limites são supervisão de processo, não uma cota rígida do sistema operacional.

## Persistência

- Cada resultado aceito continua sendo anexado e sincronizado no journal antes de ser publicado para a interface.
- `translations.json` e `pending.json` passam a usar JSON compacto, mantendo leitura compatível com o formato anterior.
- A consolidação periódica do cache, com janela de 60 segundos, e o snapshot da fila, com janela de 10 segundos, são adiados enquanto há trabalho visível pendente. A gravação durável de resultados prontos não é adiada. Persistência forçada/encerramento continuam consolidando os arquivos.
- O cache existente é reutilizado; atualizar o JAR não exige apagar traduções, fila ou preferências. O controle de exibição das traduções salvas continua preservando os arquivos.

## Painel e adaptadores

O painel foi redesenhado com cabeçalho de edição, faixa de estado, contadores separados de disco/sessão e progresso. Os controles existentes de pausa/retomada, cache e recolhimento foram mantidos com widgets nativos. Geometria de clique, dimensões e animação foram atualizadas. A renderização consome um snapshot preparado pelo worker, sem I/O, chamadas de inferência ou consultas de hardware por quadro.

| Minecraft | Loader | Java no cliente | Edições |
| --- | --- | ---: | --- |
| 1.20.1 | Fabric | 17+ | Lite / Normal |
| 1.21.1 | Fabric, novo adaptador | 21+ | Lite / Normal |
| 26.2 | Fabric | 25+ | Lite / Normal |
| 26.1.2 | NeoForge | 25+ | Lite / Normal |

O novo adaptador 1.21.1 ajusta as APIs de componentes, identificadores, renderização e atualização do chat. Núcleo e seis fontes de testes compartilhados são conferidos por `scripts/sync_shared_core.py`: 84 comparações entre plataformas. `QuestCatalog` e os adaptadores de mixin/renderização conservam as diferenças de API de cada Minecraft.

## Medições e validação

**Microbenchmarks de sete rodadas no PC do autor:**

| Operação por rodada | Antes | Depois |
| --- | ---: | ---: |
| 200 buscas num glossário de 20 mil termos | 104,008 ms | 4,824 ms |
| 1000 promoções/contagens numa fila de 30 mil trabalhos | 248,607 ms | 0,294 ms |
| Checkpoint real de 30 mil registros | 53,776 ms / 8816672 bytes | 50,181 ms / 7916671 bytes |

O checkpoint ficou aproximadamente 10,2% menor; o ganho de tempo de gravação foi modesto. O journal de oito resultados levou aproximadamente 0,6 ms, incluindo sincronização; 400 registros foram recuperados. Ganhos de fila/glossário não representam o mesmo fator de aceleração da inferência completa.

**Confirmação nativa final, oito textos sintéticos por perfil:**

| Ensaio | Backend/perfil efetivo | Pico residente | Pico privado Windows | Mediana por texto, modelo carregado |
| --- | --- | ---: | ---: | ---: |
| Normal compacta | CUDA / compacto | 660,20 MiB | 2126,62 MiB | 243,06 ms |
| Normal, perfil completo solicitado | CUDA / fallback compacto | 660,64 MiB | 2126,68 MiB | 226,82 ms |
| Lite compacta | CPU / compacto | 1588,73 MiB | 858,64 MiB | 3122,97 ms |
| Normal com pressão adicional de RAM | CUDA / compacto | 660,60 MiB | 2126,49 MiB | 228,78 ms |

No ensaio de pressão foram reservados e tocados mais 1440 MiB de páginas físicas; a RAM disponível chegou a 1805,25 MiB. Pausa/retomada real do servidor e reutilização após retomada passaram nos quatro ensaios. Reutilizar um parágrafo teve mediana de aproximadamente 1,9–2,3 ms; partida a frio, aproximadamente 6,5–6,9 segundos. O relatório distingue perfil solicitado de perfil efetivamente aplicado. Há também uma referência anterior do perfil completo com recursos suficientes.

- **648 casos de integração passaram:** 81 por variante, oito variantes, zero falhas. O substituto de inferência cobre fila, prioridade, Unicode, terminologia, durabilidade, pausa e controles; inferência real é verificada separadamente.
- Alvos reais de métodos/fields dos mixins foram conferidos nos JARs das quatro versões. Alvos opcionais ausentes foram registrados como não exercitados, sem convertê-los em resultados positivos.
- O cliente real Minecraft 1.21.1 carregou os mixins principais e renderizou o painel com fontes/botões nativos. Callbacks de pausa/retomada, cache e recolhimento foram exercitados; as contagens nas capturas desse ensaio são simuladas.
- A Normal foi instalada no **ATM11 0.11.1-beta, Minecraft 26.1.2 / NeoForge 26.1.2.109**, preservando cache e preferências. O cliente carregou a alpha e registrou 144 novas traduções no checkpoint observado após a inicialização. O autor relatou resultado satisfatório no uso. Isso não é uma matriz completa de todas as telas, modpacks ou variantes.

## Comparação de modelos e limites

O Q4_K_M foi mantido após comparar Q4_K_M, Q6_K e Q8_0 oficiais, mais Q3_K_M/Q2_K experimentais. No conjunto controlado de oito textos em CUDA, as medianas foram 206,28 / 282,01 / 310,09 / 236,99 / 225,69 ms, respectivamente. Q3/Q2 foram requantizados localmente a partir de Q8_0, com `--allow-requantize`, sem matriz de importância. Q2 perdeu uma validação de nomes/formatação e apresentou erros de sentido; Q3 também perdeu naturalidade. Validar marcadores não valida a qualidade linguística.

As variantes oficiais Tencent 1.25Bit/2Bit em formato STQ não carregaram no runtime b11429 testado. Elas não receberam métricas de inferência e não são equivalentes aos experimentos locais Q2_K/Q3_K_M. Proveniência, hashes e parâmetros estão em [BENCHMARK-2.0.md](BENCHMARK-2.0.md).

Componentes incompatíveis, saídas rejeitadas pela validação de marcadores e texto embutido em imagens podem continuar no idioma original. O modelo ainda pode produzir português incorreto. Integrações opcionais FTB Quests/FancyMenu no novo Fabric 1.21.1 precisam de ensaios com esses mods presentes. Não há suporte anunciado para todas as versões após 1.7; versões Java 8 exigem outra camada de compatibilidade.

## Artefatos e reprodução

Os oito JARs, manifesto, `SHA256SUMS.txt`, `VALIDACAO.json` e este changelog acompanham o pré-lançamento. Modelo e runtime são baixados separadamente; credenciais, configurações do jogador, caches e mundos não são distribuídos.

Para reproduzir builds, testes e ensaios, consulte [DESENVOLVIMENTO-2.0.md](DESENVOLVIMENTO-2.0.md). `scripts/benchmark_models.py` aceita runtimes/modelos já disponíveis, usa autenticação apenas em loopback, respeita a trava global e encerra somente o processo que criou. A comparação automática de modelos verifica conclusão da resposta; revisão do sentido continua sendo uma etapa separada.
