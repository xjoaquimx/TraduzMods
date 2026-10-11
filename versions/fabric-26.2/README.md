# TraduzMods 1.0.0 — Lite e Normal

TraduzMods é um mod cliente que traduz automaticamente textos de mods para português do Brasil usando somente um modelo local. Ele reutiliza a tradução nativa quando disponível e salva as novas traduções em cache em disco. Há duas edições com o mesmo núcleo de tradução e diferenças apenas na política de recursos.

| Política | Lite | Normal |
| --- | --- | --- |
| Objetivo | Prioridade ao desempenho do Minecraft | Maior velocidade de tradução |
| Tradução do catálogo em segundo plano | Desativada; somente textos consultados | Ativa quando há recursos e nenhuma tela de leitura em foco |
| Orçamento supervisionado do processo local | 2.048 MiB | Perfil completo: 2.048–8.192 MiB; consulta compacta: 1.792 MiB |
| RAM livre mínima para iniciar o modelo | 4.096 MiB | Perfil completo: orçamento + 2.048 MiB; consulta compacta: 3.328 MiB |
| Contexto de inferência | 2.048 tokens | Perfil completo: 4.096; consulta compacta: 1.024 tokens |
| Batch / microbatch | 64 / 32 | Perfil completo: 256 / 128 em CPU; até 1.024 / 512 em GPU; consulta compacta: 64 / 32 |
| Threads de inferência | Até 2 | Perfil completo: até 32; consulta compacta: até 2 |
| Intervalo de trabalho | 500 ms | 100 ms |
| Descarregamento após ociosidade | 20 segundos | Perfil completo: 180 segundos; consulta compacta: 20 segundos |

Ambas as edições desativam o cache extra de prompts em RAM (`--cache-ram 0`) que anteriormente podia acumular vários gigabytes. O cache KV do único slot continua limitado pelo contexto e é reutilizado pelo backend (`cache_prompt=true`); ele não foi desligado. A tradução local usa o mesmo modelo em ambas: Lite não promete consumo menor que 1 GiB. Um supervisor verifica periodicamente RAM do processo e memória disponível; há limites de fila, prevenção de dois servidores locais simultâneos e pausa sob pressão de memória. Esses controles não são uma cota rígida do sistema operacional e não garantem FPS específico.

No perfil completo, a edição Normal mede RAM total/livre e threads lógicas, calcula o orçamento com teto de um terço da RAM total e preserva pelo menos 2 GiB livres antes da admissão. No menu pode usar todas as threads lógicas menos uma; em um mundo usa até 75%, sempre com teto de 32. O orçamento é recalculado ao iniciar o backend; mudanças entre menu e mundo podem reconfigurar o modelo após o trabalho atual.

No Windows, a Normal também consulta a VRAM livre com `nvidia-smi`, após o download e imediatamente antes de iniciar o modelo. A aceleração CUDA requer driver compatível e pelo menos 4 GiB livres; o batch cresce em placas com 8 GiB ou mais de VRAM livre. Durante a sessão, uma consulta assíncrona periódica pausa o modelo sob pressão severa de VRAM. Quando a consulta não está disponível, a seleção de GPU é conservadora. Lite mantém seu perfil conservador.

Normal reduz esperas artificiais, usa batches maiores no trabalho de fundo, agrupa até oito textos em uma rodada de processamento e mantém o modelo aquecido por mais tempo. Textos de uma tela aberta têm prioridade: são atendidos em lotes de uma fonte, e o catálogo fica suspenso durante a leitura. Se um lote de fundo já estiver em andamento, ele cede entre os trechos de inferência; os resultados concluídos são preservados e o restante volta à fila. Durante uma tela de leitura, concluir a fila não encerra imediatamente o modelo: ele fica disponível para as próximas consultas, respeitando a proteção de memória e os limites de ociosidade. Ao sair da tela, a Normal encerra o servidor se toda a fila estiver concluída; o perfil compacto também é encerrado quando não há mais consulta visível. O limite de ociosidade é de 180 segundos no perfil completo da Normal e de 20 segundos no compacto. As inferências continuam serializadas; as gravações do lote são agrupadas para reduzir sincronizações de disco. O ganho real depende da CPU/GPU, da RAM livre, dos textos e do cache; a comparação medida deve ser consultada no relatório de validação da versão.

Quando o perfil completo da Normal não cabe na RAM livre, uma consulta visível pode iniciar um perfil compacto em CPU: até duas threads, contexto de 1.024 tokens, orçamento supervisionado de 1.792 MiB e pelo menos 3.328 MiB livres para admissão. Esse perfil atende somente consultas, sem avançar o catálogo. Abaixo do mínimo, o modelo continua aguardando recursos. Esses valores descrevem a política implementada. O ensaio nativo isolado mais recente confirmou inicialização, inferência e passagem cooperativa do fundo para consultas. Essa passagem ocorre entre trechos de inferência, podendo levar vários segundos; a consulta não interrompe instantaneamente o trecho já em execução. O ensaio não mede FPS nem disputa de recursos dentro do ATM11; detalhes estão no relatório de otimizações. Não há promessa de tradução instantânea.

A busca de terminologia reconhecida usa um índice para reduzir varreduras repetidas de regras por texto, e as expressões regulares da política de tradução são compiladas uma vez e reutilizadas. A comparação isolada confirmou saídas equivalentes; essa melhoria não implica o mesmo ganho percentual no tempo completo de tradução, que inclui inferência e gravação.

## Indicador visual de tradução

Uma barra discreta no canto superior esquerdo mostra textos concluídos e o trabalho restante, nos menus e durante o jogo. O indicador distingue preparação, tradução, espera e pausa; a mensagem de conclusão aparece por cinco segundos. Os controles continuam acessíveis em telas com cursor livre. F1 oculta também esse indicador. A tradução inicia automaticamente; o controle é opcional.

Na Normal, os contadores representam o catálogo instalado identificado e o trabalho pendente. Na Lite, representam os textos consultados; o catálogo inteiro não aparece como uma tarefa obrigatória. A quantidade total pode mudar quando novos textos são descobertos. A barra mostra atividade de tradução, sem exibir informações técnicas do modelo ou qualquer credencial.

O botão **Pausar / Retomar** usa o visual nativo dos botões do Minecraft e aparece no painel quando há uma tela com cursor livre: menu principal, inventário ou menu aberto com Esc. Ele não captura os cliques usados para jogar. A pausa mantém as traduções já salvas e a fila pendente; novas inferências são interrompidas, e o modelo local pertencente ao mod é encerrado fora da renderização. A opção de pausa é conservada ao reiniciar o jogo (`translationPaused` no arquivo de configuração). Retomar conserva as proteções de RAM e VRAM; não força o modelo a iniciar sem memória suficiente.

O botão independente **Traduções salvas: Ligadas / Desligadas** controla somente a exibição das traduções geradas. Desligar não apaga o cache, não obriga a retraduzir e não altera a pausa da inferência; traduções nativas de pt-BR continuam disponíveis. Ao ligar novamente, o mod reutiliza os resultados salvos. A escolha persiste entre inícios (`displayCachedTranslations`).

A aba lateral **< / >** minimiza ou reabre o painel. Minimizar preserva uma aba de acesso e não pausa a tradução; essa preferência também persiste entre inícios (`translationPanelMinimized`).

Os números **Salvos** incluem as traduções recuperadas do disco; **Nesta sessão** mostra somente as novas traduções gravadas desde este início do jogo. Os valores salvos podem permanecer iguais entre reinicializações enquanto o modelo aguarda memória. O painel distingue **Pausado por você** de **Aguardando memória** e mostra o motivo da espera, incluindo RAM livre e mínima exigida; após Retomar, a inferência recomeça assim que houver recursos suficientes. Se o supervisor de memória encerra o modelo, a disponibilidade de recursos é reavaliada pelo worker em intervalos curtos, sem espera fixa de 120 segundos nem contagem regressiva. O modelo só volta a iniciar quando há recursos suficientes; os limites de RAM e VRAM continuam ativos.

A configuração `showTranslationProgress`, em `config/modtranslator/config.json`, é `true` por padrão; use `false` para ocultar o indicador. A renderização lê um snapshot imutável já preparado pelo worker e não faz chamadas de rede, consultas ao sistema ou leituras de arquivos por quadro.

## Instalação e troca de edição

1. Selecione o JAR do loader e da versão exata do Minecraft.
2. Feche o Minecraft e remova a versão anterior do TraduzMods da pasta `mods`.
3. Instale **um único JAR: Lite ou Normal**. Ambas usam `modtranslator` como identificador, portanto não podem ser instaladas juntas.
4. Defina Português (Brasil) como idioma do Minecraft e inicie o jogo.

Trocar a edição não exige recriar o cache nem inserir uma chave de API. A política é definida pelo JAR, mesmo em uma configuração legada. O backend local é baixado automaticamente quando necessário, exige conexão para esse primeiro download e usa espaço em disco separado do pequeno JAR. O modelo utilizado é Tencent Hy-MT2-1.8B GGUF Q4_K_M. Depois de baixar o modelo e o runtime, a inferência funciona localmente sem serviço de tradução externo e sem chave de API. Configurações legadas de DeepL não são utilizadas. O cache e a fila de tradução existentes são preservados.

O objetivo é preservar frases completas, cores, formatação e nomes de itens. Componentes de texto dinâmicos incompatíveis podem permanecer no idioma original. Letras desenhadas dentro de imagens PNG não são traduzidas por interceptação de texto. Mensagens comuns digitadas por jogadores não são enviadas para tradução; a integração de NPCs reconhece componentes estruturados de diálogo.

Na apresentação de títulos e descrições do FTB Quests, o símbolo literal **&** é escapado para não ser confundido com marcação. A correção mantém códigos de cores, cores hexadecimais e barras invertidas; atua somente no texto exibido e não reescreve os arquivos de quests nem o cache.

## Plataformas desta versão

- Fabric / Minecraft 1.20.1 / Java 17.
- Fabric / Minecraft 26.2 / Java 25.
- NeoForge / Minecraft 26.1.2 / Java 25.

Cada plataforma recebe os mesmos recursos e correções no núcleo. A validação compara esse núcleo normalizando apenas a diferença de tipo de identificador do Minecraft; adaptadores de NBT e renderização permanecem específicos de cada versão.

## Compilação a partir das fontes

O projeto do loader fica na subpasta `fabric` ou `neoforge`; o núcleo compartilhado fica em `common`.

```text
gradle -p fabric build "-Pmod_version=1.0.0-lite" -Pedition=lite
gradle -p fabric build "-Pmod_version=1.0.0-normal" -Pedition=normal
```

Para NeoForge, substitua `fabric` por `neoforge`. O recurso raiz `traduzmods-edition.properties` contém apenas `edition=lite` ou `edition=normal`; o código é o mesmo. A opção de edição ausente ou inválida no recurso assume Lite por segurança. O arquivo distribuído deve ter metadados que indiquem a edição e a versão.

Licença MIT. Não há chaves, configurações do usuário, traduções privadas, executáveis do backend nem arquivos de modelo nos JARs ou no pacote de fontes.
