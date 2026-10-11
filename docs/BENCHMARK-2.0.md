# Ensaios locais de otimização — 11/10/2026

Computador: 16294 MiB de RAM física, 12 threads lógicas, NVIDIA RTX 5060 com 8151 MiB de VRAM, driver 617.42. Runtime llama.cpp b11429. Medianas abaixo são de pedidos com o modelo carregado; partida a frio, FPS e carregamento de mundo são medidas diferentes.

## Qual GGUF usar

Comparação com oito textos sintéticos de quests, interface e NPCs, usando o mecanismo real de proteção/restauração de nomes e formatação. CUDA, gráficos habilitados, `--load-mode none`, contexto 1024, batch 64, microbatch 32, duas threads. Sampling: seed 42, temperatura 0,7, top-p 0,6, top-k 20, repeat-penalty 1,05. Uma solicitação de aquecimento foi excluída. Os textos e prompts foram iguais para cada quantização.

| Quantização | Arquivo (MiB) | Mediana por texto (ms) | Pico residente (MiB) | Pico privado Windows (MiB) | VRAM adicional após inferência (MiB) | Nomes/formatação válidos |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Q4_K_M oficial | 1080,59 | 206,28 | 665,47 | 2131,93 | 1264 | 8/8 |
| Q6_K oficial | 1406,46 | 282,01 | 658,47 | 2451,63 | 1585 | 8/8 |
| Q8_0 oficial | 1820,11 | 310,09 | 714,85 | 2922,41 | 2001 | 8/8 |
| Q3_K_M experimental | 906,96 | 236,99 | 672,56 | 1967,21 | 1086 | 8/8 |
| Q2_K experimental | 741,46 | 225,69 | 673,39 | 1803,08 | 931 | 7/8 |

Q3/Q2 foram **requantizados localmente a partir do Q8_0**, com `--allow-requantize`, sem matriz de importância. Não são arquivos oficiais da Tencent nem uma avaliação de todos os possíveis Q3/Q2. A requantização pode introduzir perda adicional.

**Decisão desta etapa: manter Q4_K_M.** Neste conjunto e hardware, ele foi mais rápido que Q6/Q8 e teve o melhor equilíbrio de memória e sentido. Validar nomes e marcadores não valida o português: a revisão encontrou concordância incorreta, sinônimos fora dos nomes nativos e pequenos erros também nos modelos maiores. Q2 alterou instruções e unidades, e em um texto ecoou conteúdo da instrução. Q3 também produziu frases pouco naturais. Um conjunto de oito textos não estabelece qualidade universal; a comparação deve ser ampliada com textos longos, estilos e modpacks diferentes antes de trocar o padrão.

Os [GGUFs oficiais](https://huggingface.co/tencent/Hy-MT2-1.8B-GGUF) usados vieram da revisão `a0c709d9fac510f2c807aa3af52872340dc37a4a`:

| Arquivo | SHA-256 |
| --- | --- |
| Q4_K_M | `dc5f44fcf1fa496ee7ad725982c0c8c553a4de00259b53af84c4b89fb0c06699` |
| Q6_K | `d98fe604dec1f28f58f80d7d560f7177e584d3b8e5835862687660e5ff97cb40` |
| Q8_0 | `5c3fe0b1408a5ceb0143184ef247b11b579c525f4b02b060e6c851bb76fef1a4` |
| Q3_K_M experimental | `3682c4424227774232a4217b3cf316c0cacde5a2d1b7dbb687c18717fec0df58` |
| Q2_K experimental | `dd9bb7b78850e0fca7ff370150cf25b2d86132e0d07cb2de0da453436058e917` |

As variantes Tencent 1.25Bit e 2Bit foram submetidas a carregamento CPU e CUDA no runtime fixado e não carregaram. Seu formato STQ precisa de suporte específico; as propostas estão nos PRs [22836](https://github.com/ggml-org/llama.cpp/pull/22836) e [19357](https://github.com/ggml-org/llama.cpp/pull/19357). Tamanho de arquivo menor não é prova de RAM menor, qualidade suficiente ou inferência mais rápida. Não foram usadas no mod nem receberam métricas fictícias de tradução.

## Carregamento e GPU

No ensaio controlado de Q4, o carregamento CUDA padrão teve pico residente de 1543,29 MiB; com `--load-mode none`, 662,88 MiB. A mediana por texto passou de 242,05 para 227,81 ms. O compromisso privado aumentou de 1934,93 para 2128,83 MiB, e a VRAM ficou próxima de 1270 MiB adicionais. Portanto, a redução é de RAM **residente**, não de todas as categorias de memória.

Habilitar gráficos CUDA reduziu a mediana de 232,15 para 155,90 ms em outra rodada controlada. Isso não demonstra ganho percentual de FPS nem aceleração universal de 33%. `--no-host` e carregamento lazy não trouxeram vantagem útil neste ensaio e não foram incorporados.

## CPU, fila, glossário e disco

No microbenchmark de sete rodadas, procurar referências em 20 mil termos, 200 vezes por rodada, passou de 104,008 para 4,824 ms. Promover e contar mil trabalhos numa fila de 30 mil passou de 248,607 para 0,294 ms. São caminhos de despacho específicos; não se aplica esse fator à tradução inteira.

O checkpoint real de 30 mil registros tinha 8816672 bytes e mediana de 53,776 ms. Com JSON compacto, 7916671 bytes e 50,1805 ms: arquivo aproximadamente 10,2% menor, ganho de tempo modesto. O registro durável de oito resultados levou cerca de 0,6 ms, preservando a sincronização em disco. Quatrocentos registros foram recuperados sem perda. A mudança mais relevante para quem lê é adiar consolidações/snapshots de catálogo quando há texto visível pendente, sem adiar a gravação durável das traduções prontas.

Os resultados da inferência integrada ao código de produção, incluindo pressão de RAM, estão no [resumo de validação](validation-2.0-summary.json). Reaproveitar um parágrafo não chama o modelo novamente; os tempos de reaproveitamento são medidos separadamente da inferência. Um modelo descarregado ainda precisa ser carregado, o que levou aproximadamente 4–8 segundos nos ensaios. Esse custo precisa ser considerado ao testar a experiência dentro do Minecraft.

## Confirmação final com aplicativos de desktop abertos

Sem Minecraft concorrente, mas com os demais aplicativos de desktop abertos, a Normal compacta usou CUDA: pico residente de 660,20 MiB, compromisso privado de 2126,62 MiB e mediana de 243,06 ms por texto. Pedir o perfil completo também funcionou: a seleção automática usou o compacto por falta de espaço para o completo; a mediana foi 226,82 ms. O relatório distingue o perfil solicitado (`profile`) do aplicado (`effectiveCompact`). A referência anterior com recursos suficientes validou o perfil completo em CUDA, com 656,64 MiB residentes, 2339,32 MiB privados e 153,91 ms de mediana.

A Lite CPU teve 1588,73 MiB residentes e mediana de 3122,97 ms nesta rodada. O reaproveitamento dos parágrafos ficou entre cerca de 1,9 e 2,3 ms de mediana nas três variantes. A partida a frio levou aproximadamente 6,5–6,9 segundos. Esses números reforçam que ter o modelo pronto e reutilizar o disco são partes essenciais da experiência; não são uma promessa para todo computador ou texto.

Reservando mais 1440 MiB de páginas físicas para simular outros aplicativos, a Normal continuou traduzindo com CUDA. A RAM disponível chegou a 1805,25 MiB durante o ensaio; pico residente de 660,60 MiB e mediana de 228,78 ms. As três variantes e o ensaio de pressão passaram na pausa/retomada real do servidor e na reutilização depois da retomada. Não foi necessário esgotar a memória para comprovar o novo comportamento.

Os ensaios nativos foram isolados de instâncias do jogador e respeitaram a trava global do modelo. Os testes de integração usam um substituto de inferência para cobrir fila, falhas, durabilidade, pausa, prioridade, Unicode, glossário e controles. A inferência real é verificada em uma suíte separada. O painel foi renderizado no cliente real do Minecraft 1.21.1, com contagens simuladas e callbacks dos botões exercitados. A validação com quests e NPCs de cada modpack continua pendente antes de uma versão estável 2.0.0.
