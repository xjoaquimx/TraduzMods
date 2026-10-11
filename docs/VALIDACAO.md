# TraduzMods 1.0.0 — validação final

75 casos passaram em cada uma das seis variantes: 450 sucessos, zero falhas. Os seis JARs finais passaram nas 18 verificações de perfil e nos seis testes do painel/controles. Suas classes coincidem com as classes examinadas pelos validadores das assinaturas reais do Minecraft. Os 130 fontes Java de produção deste repositório conferem com os snapshots validados após normalizar os finais de linha do Git.

Foi removida a espera fixa de proteção e sua contagem regressiva. Os limites de memória e a admissão conforme recursos disponíveis continuam ativos. O teste confirma preservação de cache, pausa manual, prioridade de leitura, minimização, exibição do cache e escape literal do FTB.

O modelo real executou diretamente o JAR Fabric 1.20.1 Normal final, com perfil compacto CPU, e passou em tradução, passagem cooperativa do fundo, pausa/liberação de processo e retomada. Pico residente 1547.16 MiB; privado 794.14 MiB. Este ensaio isolado não mede FPS dentro do jogo e não comprova qualidade linguística universal.

Os seis JARs validados estão em `releases/1.0.0`. O resumo das verificações está em [validation-summary.json](validation-summary.json).

## Preparação do repositório GitHub

As três plataformas também foram compiladas pelo wrapper Gradle em uma cópia limpa das fontes para publicação, com a edição Normal. Os 75 casos de integração passaram em cada plataforma: 225 sucessos adicionais, zero falhas. Foram ajustados somente configuração de compilação, documentação e suporte aos testes; o código Java de produção e os seis JARs distribuídos permaneceram idênticos aos validados para a 1.0.0. O verificador portátil confirma 30 comparações do núcleo e os seis hashes SHA-256.
