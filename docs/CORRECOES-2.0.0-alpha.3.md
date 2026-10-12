# TraduzMods 2.0.0-alpha.3 — atualização do GuideMe

## Problema identificado no ATM11

O tooltip do Carregador já estava localizado, mas o manual do AE2 continuava em inglês. A consulta de compatibilidade usava `getClassNode(target, false)`. No jogo aberto, o serviço FML do NeoForge lançou `IllegalArgumentException: FML service does not currently support retrieval of untransformed bytecode`. O plugin capturava essa exceção e retornava `false`, impedindo a aplicação do mixin do GuideMe. O adaptador nem era carregado e os parágrafos não chegavam à fila de tradução.

A consulta com `true` foi verificada no processo real do Minecraft e retornou a classe e as APIs esperadas. Todas as integrações opcionais agora usam essa modalidade, incluindo Aether II e FancyMenu. Uma exceção de compatibilidade passa a aparecer no log com o nome da integração e a causa.

## Implementação

- Tradução do corpo e dos subtítulos antes do cálculo do layout, preservando a estrutura dos links e estilos.
- Atualização de `GuideScreen.pageTitle`: o GuideMe remove o primeiro título do documento e o guarda em um parágrafo separado.
- Tradução dos parágrafos da barra lateral e recálculo das posições das linhas, sem reconstruir a árvore de navegação ou redefinir a rolagem.
- Descoberta de novas linhas após expandir a barra lateral, mesmo sem uma nova revisão do cache.
- Guarda de reentrada durante recálculo do layout e inspeção da navegação limitada a uma vez a cada 500 ms.
- Codificação dos segmentos armazenada por bloco, evitando reconstruir a mesma cadeia de marcadores a cada atualização do cache.
- Falhas de reflexão no adaptador são registradas uma vez antes de desabilitar apenas a integração incompatível.

As mesmas mudanças foram propagadas para Fabric 1.20.1, Fabric 1.21.1, Fabric 26.2 e NeoForge 26.1.2, nas edições Lite e Normal. Os adaptadores mantêm suas APIs específicas por plataforma. Cache, modelo local e preferências existentes não precisam ser apagados.

## Validação e limites

Os testes foram executados no computador do autor: Ryzen 5 5600X, 16 GB de RAM, RTX 5060 de 8 GB e Windows 11 Pro. Não foi realizado um novo benchmark de FPS ou de qualidade do Hy-MT2.

Cada combinação de plataforma/edição executa 93 testes com um provedor local simulado. Os novos casos cobrem título separado, barra lateral, cache ligado/desligado, descoberta de linhas sem mudança da revisão, preservação de rolagem e reentrada.

Uma verificação adicional carrega o JAR real do GuideMe 26.1.15-beta do ATM11 e exercita `LytDocument`, `LytParagraph`, `LytHeading`, os nós de texto e um bloco sem texto. Ela verifica tradução, restauração dos originais, preservação de estilo e solicitação de recálculo do layout. O hospedeiro da tela e o provedor de inferência continuam simulados nesse teste; o construtor de imagens depende dos registros do jogo e não é exercitado fora do Minecraft.

A inspeção do bytecode confirma os alvos do mixin, os campos e métodos do título/navegação e o argumento `true` nas três consultas opcionais. A causa original foi diagnosticada no jogo real. A apresentação visual final no manual do Carregador deve ser conferida após reiniciar com o novo JAR.

As correções desta etapa estão incluídas no pré-lançamento 2.0.0-alpha.4 do GitHub.
