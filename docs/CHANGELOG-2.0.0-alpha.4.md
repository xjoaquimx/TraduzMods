# TraduzMods 2.0.0-alpha.4 — alterações técnicas

Pré-lançamento com as correções acumuladas desde a alpha.1. São oito JARs: Lite e Normal para Fabric 1.20.1, Fabric 1.21.1, Fabric 26.2 e NeoForge 26.1.2. A inferência continua local com Tencent Hy-MT2-1.8B GGUF Q4_K_M; esta atualização reaproveita modelo, configurações e cache existentes.

## GuideMe / manual do AE2

O adaptador agora altera as folhas de texto antes do cálculo do layout. A substituição genérica durante o desenho foi desabilitada dentro do GuideMe porque podia alterar larguras depois que as posições dos links já estavam calculadas, sobrepondo palavras.

O título superior e os parágrafos da barra lateral são tratados explicitamente. O GuideMe remove o primeiro título do documento e o guarda em `GuideScreen.pageTitle`; atualizar apenas o corpo deixava esse título em inglês. Mudanças de tradução invalidam o layout correspondente, mantendo os objetos interativos, estilos e rolagem. Novas linhas da navegação são descobertas ao expandir categorias, mesmo sem mudança na revisão do cache.

A reavaliação ocorre no máximo a cada 500 ms. Reflexão é resolvida por classe, referências a telas/nós são fracas e a cadeia de marcadores de cada bloco é reutilizada. Uma guarda impede reentrada durante o recálculo do layout; erros incompatíveis são registrados antes de desabilitar a integração daquela tela.

### Recuperação de parágrafos com links

O problema dos cartões de distribuição igualitária e de destruição de sobras foi identificado no cache real: o modelo manteve todas as tags, mas juntou palavras dos links em outros segmentos, mudou sua ordem e deixou alguns vazios. A preservação do conjunto de tokens permitia armazenar esse resultado; o adaptador rejeitava sua correspondência com a árvore do documento e mantinha o texto original.

A tradução do parágrafo completo continua como primeira tentativa. Se a correspondência é inválida ou um segmento com conteúdo fica vazio, o adaptador usa automaticamente as folhas originais como unidades de tradução. Essa alternativa:

- Preserva os nós dos links e seus estilos, atualizando o texto antes do layout.
- Mantém espaços e quebras de linha nas bordas dos segmentos.
- Reaproveita rótulos nativos em português, como “Bancada de Células”.
- Persiste e reutiliza os novos trechos em disco.
- Restaura os originais quando a exibição das traduções salvas é desligada.

Não é necessário apagar o cache anterior. Resultados incompatíveis já armazenados são detectados ao abrir o documento. A tradução por fragmentos pode oferecer menos contexto ao modelo que o parágrafo completo; a qualidade deve ser conferida durante o uso.

## Integrações opcionais no NeoForge

A consulta de compatibilidade passa a usar `getClassNode(target, true)`. O FML rejeita a modalidade de bytecode não transformado (`false`), lançando `IllegalArgumentException`; o código anterior capturava a exceção e desativava silenciosamente o mixin do GuideMe. Essa causa foi confirmada no Minecraft aberto, e a consulta corrigida retornou a classe esperada.

A mesma alteração foi aplicada às consultas opcionais de Aether II e FancyMenu. As verificações de métodos/campos continuam protegendo versões incompatíveis, com registro da causa quando uma consulta falha.

## Aether II, JourneyMap, quests e chat

- O adaptador do Aether II captura documentos de `AlphaInfoScreen` e `DiscoveryDescriptionScreen` e reconstrói suas páginas quando a tradução muda, preservando o índice da página, espaçamento e estilo base. Títulos são admitidos junto com os parágrafos, sem reinicializar a tela.
- Valores `jm.*` de `pt_br` iguais aos de `en_us` são tratados como traduções ausentes, preservando traduções nativas efetivas e nomes de mods. Rótulos conhecidos recebem equivalentes determinísticos; identificadores de dimensão e índices de seção vertical permanecem intactos.
- `Logs` recebe `Troncos` no contexto de tarefas de quests. Títulos de console/logs mantêm seu significado original. Uma tradução de quest pronta deixa de ser bloqueada por um texto igual encontrado em outro recurso nativo.
- O título de progresso `Ice and Fire` recebe `Gelo e Fogo` no contexto da chave de avanço; o nome do mod continua preservado em outros contextos.
- Anúncios estruturados de progresso podem atualizar as linhas abertas do chat. Mensagens de jogadores e chat assinado não são admitidos como origem de tradução literal.

## Compatibilidade dos arquivos

| Minecraft | Loader | Java do cliente | Edições |
| --- | --- | ---: | --- |
| 1.20.1 | Fabric | 17+ | Lite / Normal |
| 1.21.1 | Fabric | 21+ | Lite / Normal |
| 26.2 | Fabric | 25+ | Lite / Normal |
| 26.1.2 | NeoForge | 25+ | Lite / Normal |

O ATM11 0.11.1-beta usado neste projeto executa Minecraft 26.1.2 / NeoForge 26.1.2.109 e utiliza o arquivo **NeoForge 26.1.2 Normal**. Instale somente uma edição por instância e substitua o JAR anterior com o jogo fechado. Os pesos do modelo e o runtime não estão incluídos nos JARs.

## Testes no PC do autor

**Os testes locais desta atualização foram executados no PC do autor do projeto:** AMD Ryzen 5 5600X, 6 núcleos/12 threads, 16 GB de RAM, NVIDIA RTX 5060 de 8 GB e Windows 11 Pro.

- **95 testes em cada uma das oito combinações: 760 sucessos, zero falhas.** O serviço de produção foi exercitado com um substituto de inferência em memória. As regressões incluem recuperação de segmentos reordenados/vazios, rótulos nativos, preservação de links/estilos/espaçamento, armazenamento dos trechos e reaplicação do cache sem nova inferência.
- **102 comparações** confirmam a padronização dos arquivos de núcleo e testes compartilhados; adaptadores de API específicos permanecem separados.
- Uma suíte adicional carrega as classes reais do **GuideMe 26.1.15-beta**, incluindo segmentos aninhados, para conferir a recuperação do texto e sua restauração. A tela hospedeira e a inferência nessa suíte são simuladas; ela não valida o desenho final com as fontes do Minecraft.
- A inspeção dos JARs reais confirma seis classes alvo, nove seletores de mixin, APIs do título/navegação do GuideMe e as três consultas de bytecode transformado.
- A **Normal alpha.4 foi instalada e iniciou no ATM11**, preservando cache e preferências. O processo registrou a versão correta, backend CUDA e geração de nova tradução no checkpoint de inicialização. A confirmação visual final dos dois cartões continua pendente; não se declara tradução integral do modpack.

Não foram realizados novos benchmarks de FPS, velocidade de inferência ou comparação de modelos nesta atualização. As medições históricas da alpha.1 estão em [BENCHMARK-2.0.md](BENCHMARK-2.0.md), com seus próprios textos, perfis e limites.

Detalhes de cada etapa: [alpha.2](CORRECOES-2.0.0-alpha.2.md), [alpha.3](CORRECOES-2.0.0-alpha.3.md) e [alpha.4](CORRECOES-2.0.0-alpha.4.md). Os anexos da Release incluem os oito JARs, manifesto, SHA-256 e relatório de validação.
