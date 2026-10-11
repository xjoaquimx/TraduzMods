# Correções locais — 2.0.0-alpha.2

Esta iteração corrige atualização de textos e cálculo de layout nos exemplos relatados durante o teste do ATM11. A alpha.1 publicada permanece preservada; a alpha.2 é preparada e instalada localmente para validar as mesmas telas.

## Documentos do Aether II

`AlphaInfoScreen` e `DiscoveryDescriptionScreen` guardam listas de linhas e páginas calculadas na abertura. O novo adaptador captura o documento original, admite títulos e parágrafos na fila visível e reconstrói apenas as páginas cujo texto mudou, com intervalo mínimo de 500 ms. Mantém o índice da página, limitado ao novo total, sem reinicializar a tela ou seus widgets. Espaçamento entre parágrafos, estilo base e controle de exibição do cache são preservados.

O adaptador é opcional e só é habilitado quando o formato das APIs e dos campos esperados está presente. Ele não intercepta livros editáveis nem texto digitado por jogadores.

## Guia do AE2 / GuideMe

A tradução genérica por trecho desenhado deixa de atuar dentro dos renderizadores do GuideMe. Ela alterava os textos depois que o guia já havia calculado posições e larguras, causando sobreposição de palavras e links.

O novo adaptador traduz os nós de texto de cada parágrafo antes de `ensureDocumentLayout`. Os marcadores associam cada trecho ao seu nó original; links, estilos, filhos e objetos interativos permanecem os mesmos. Ao mudar o texto, o documento invalida seu layout e o recalcula. Não há reconstrução da tela nem alteração da rolagem pelo adaptador. Resultados com marcadores inválidos ou fora da ordem esperada não são aplicados à árvore de links.

As referências a telas e nós usam chaves/referências fracas; reflexão é resolvida por classe. Reavaliação ocorre por revisão da tradução e no máximo a cada 500 ms, sem inferência ou consultas de hardware no renderizador.

## JourneyMap

Entradas `jm.*` cujo `pt_br` repete o valor de `en_us` passam a ser consideradas pendentes, preservando nomes de mods e traduções nativas diferentes do inglês. Rótulos conhecidos das telas relatadas recebem equivalentes determinísticos. Os rótulos de dimensão e seção vertical conservam os identificadores e índices originais.

## Missões e chat

- `Logs` é tratado como `Troncos` somente no contexto de tarefas de quests; títulos de console/logs não recebem essa substituição.
- Uma tradução pronta de quest deixa de ser bloqueada pela presença de um texto igual em outro recurso nativo.
- `Ice and Fire` recebe `Gelo e Fogo` quando a chave identifica um título de progresso. O nome do mod continua preservado nos demais contextos, e uma tradução nativa explícita mantém prioridade.
- Anúncios estruturados de progresso podem atualizar as linhas já abertas no chat. Mensagens de jogadores e chat assinado não são usados como origem de tradução literal.

## Validação

Os testes desta iteração são executados no PC do autor: Ryzen 5 5600X, 6 núcleos/12 threads, 16 GB de RAM, RTX 5060 de 8 GB e Windows 11 Pro. São testes do serviço com um substituto de inferência; eles não constituem um benchmark de FPS nem uma validação de qualidade universal do modelo.

A suíte acrescenta regressões de títulos/parágrafos ao vivo, manutenção da página e estilo, restauração do texto original, preservação dos links, rejeição de spans reordenados, fallback de idioma do JourneyMap, identificadores de dimensão, legenda da tarefa e distinção entre progresso e mensagem de jogador. O relatório dos oito builds fica em `outputs/releases-2.0.0-alpha.2/build-results.json` no workspace de desenvolvimento.

No ATM11, as APIs reais de ambas as telas do Aether II, FTB Quests e GuideMe foram conferidas por inspeção de bytecode. Os adapters e o núcleo são padronizados em Fabric 1.20.1, 1.21.1, 26.2 e NeoForge 26.1.2. Isso não substitui teste visual em cada combinação de mods. A confirmação visual nas telas relatadas é feita após instalar e abrir o cliente atualizado, preservando o cache e o modelo já baixado.
