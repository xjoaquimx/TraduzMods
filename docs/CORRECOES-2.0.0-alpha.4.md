# TraduzMods 2.0.0-alpha.4 — parágrafos do GuideMe com links

## Causa confirmada

Os parágrafos dos cartões de distribuição igualitária e de destruição de sobras estavam na memória de traduções do ATM11 como `@document`. O Hy-MT2 manteve todas as tags, mas transferiu palavras dos links para o texto comum, deixou vários segmentos vazios e mudou sua ordem. Portanto, a validação de preservação dos tokens permitiu armazenar o resultado, mas o adaptador do GuideMe rejeitou a correspondência entre texto e nós. O título podia aparecer em português enquanto o corpo permanecia em inglês indefinidamente.

## Correção

O adaptador mantém a tradução do parágrafo completo como primeira tentativa. Quando o resultado não mantém a ordem original dos segmentos ou esvazia um segmento com conteúdo, passa automaticamente a traduzir as folhas originais separadamente. As traduções são aplicadas antes do cálculo do layout.

- Mantém os objetos dos links, seus estilos e a posição de rolagem.
- Restaura os espaços nas bordas dos segmentos e as quebras de linha nas bordas, evitando palavras coladas aos links.
- Preserva rótulos já localizados pelos recursos nativos, como “Bancada de Células”, sem enviar esses rótulos novamente ao modelo.
- Salva os trechos na memória de traduções em disco e reaproveita esse cache nas próximas consultas.
- Restaura o texto original quando o usuário desliga a exibição das traduções salvas.
- Trata também a saída que mantém a ordem das tags, mas esvazia o conteúdo de um link.

A alternativa é restrita ao documento do GuideMe. Não modifica a estrutura da página, não aplica substituições depois do layout e não exige apagar o cache do usuário. O resultado antigo incompatível pode continuar armazenado; o adaptador detecta essa incompatibilidade e usa os trechos corrigidos.

As mesmas alterações foram propagadas para Fabric 1.20.1, Fabric 1.21.1, Fabric 26.2 e NeoForge 26.1.2, nas edições Lite e Normal.

## Validação

Cada combinação de plataforma/edição executa 95 testes de integração, usando um substituto de inferência em memória. Os dois novos casos reproduzem o comportamento observado no cache real: segmentos reordenados/vazios e um link vazio com tags na ordem correta. Conferem atualização automática, identidade dos nós, espaços e quebra de linha, preservação de rótulo nativo, persistência dos trechos e ausência de nova inferência ao reativar o cache.

A suíte adicional usa as classes reais do GuideMe 26.1.15-beta, incluindo folhas e segmentos aninhados, para testar a alternativa e a restauração do texto original. A tela hospedeira e a inferência são simuladas nessa suíte; a confirmação visual dos dois cartões continua sendo necessária após instalar o JAR.

Os testes foram executados no computador do autor: Ryzen 5 5600X, 16 GB de RAM, RTX 5060 de 8 GB e Windows 11 Pro. Esta correção não inclui um novo benchmark de velocidade, FPS ou qualidade do modelo.

Compilação local de teste, sem publicação externa nesta etapa.
