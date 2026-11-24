# Integração do Pardal (radares, lombadas e pontos de atenção) — desenho e plano

Data: 2026-11-24. Estado: **proposta, nada implementado**. Fonte: https://github.com/murkiriel/pardal (dados ODbL 1.0, releases `dados-AAAA-MM-DD`, geradas 2x ao dia).

## Por quê

Hoje os avisos vêm do OpenStreetMap via Overpass: cobertura incompleta no Brasil, servidores públicos instáveis e sem sentido da via na maior parte dos radares (vimos "radar na pista contrária"). O Pardal junta DNIT, PRF, ANTT, Inmetro, DERs, órgãos municipais e o próprio OSM, e publica CSV por estado todo dia. Ganhos esperados: mais radares e lombadas, `direction_deg` (sentido) em parte dos radares, radar de trecho, limite de velocidade, e nenhuma consulta de rede durante a viagem.

## Objetivos e não objetivos

- Objetivo: o app baixa os dados do estado onde o usuário está (e dos estados da rota), guarda no aparelho e usa como fonte principal dos avisos; o Overpass continua como reserva onde não há dado do Pardal.
- Objetivo: respeitar o sentido (`direction_deg`) e mostrar a atribuição exigida pela licença.
- Não objetivo: o app não hospeda nem republica o banco; não há servidor nosso; sem relatos de usuários (outro projeto); sem radar móvel/blitz (o Pardal só traz trechos de radar portátil da PRF, fora do v1).
- Anti-objetivo: o app nunca pode ficar sem avisos por causa do Pardal (repositório de terceiro, pode atrasar, falhar ou sumir): o caminho atual com Overpass e o cache de 30 dias continuam funcionando.

## O que o Pardal publica (lido em 2026-11-24)

- Por estado, em `pardal-<UF>.zip`: `radares.csv` (`lat,lng,kind,limit_kmh,source,active,end_lat,end_lng,direction_deg`; kind `FIXED|SECTION|RED_LIGHT`), `lombadas.csv` (`lat,lng,kind`; `BUMP|HUMP|TABLE|CUSHION|RUMBLE_STRIP|UNSPECIFIED`), `pontos_atencao.csv` (`lat,lng,kind`; `LEVEL_CROSSING|CURVE|DANGEROUS_JUNCTION|ANIMAL_CROSSING|SCHOOL_ZONE|FALLING_ROCKS|SPEED_DISPLAY|HAZARD`), além de limites, pedágios, radar portátil, buracos e acidentes com moto. O CSV usa `lat,lng`.
- `catalogo.json`: `built_at`, e por UF `bbox`, `artifact_built_at`, `coverage`, `counts` e, por arquivo, `file`, `bytes`, `sha256`, `count`.
- URL estável: `https://github.com/murkiriel/pardal/releases/latest/download/pardal-<UF>.zip` e `.../catalogo.json`.
- Só ~18% dos radares têm `direction_deg`; vazio = ambos os sentidos ou desconhecido. Releases de 11 a 13/11 e a de 23/11 tiveram falhas parciais de algumas fontes (ANTT, PRF, Transalvador) lidas de cópia anterior.
- Tamanho do zip por estado não foi confirmado (o SQLite de SP tem ~18 MB comprimido, e o `limites_trechos.csv` de SP ~29 MB descompactado). Medir antes de decidir o limite de download (tarefa 4).
- Licença dos dados: ODbL 1.0. Exige citar "© colaboradores do OpenStreetMap" e as fontes, e manter a ODbL em bases derivadas que sejam publicadas. Algumas fontes têm termos próprios. O README avisa que a sinalização da via sempre prevalece. (Isto não é parecer jurídico; ver pergunta 4.)

## Desenho

Encaixa no que já existe: `PerigoSource.buscar(tile)` (um quadrado de ~11 km) e `PerigoRepositorio`/`PerigosAdiante`/`AvisadorDePerigos` não mudam de papel.

1. **`PardalSource : PerigoSource`**: responde por quadrado a partir de um índice em memória por UF (lista ordenada por quadrado), sem rede. Quando o quadrado cai num estado sem dado baixado, falha de um jeito que faz o repositório usar o Overpass (fonte composta `PardalOuOverpass`).
2. **Armazenamento** em `files/pardal-v1/<UF>.csv` num formato nosso, enxuto (só radares, lombadas e pontos de atenção que usamos, uma linha por ponto, com `kind` já mapeado) mais `<UF>.meta` com `artifact_built_at` e sha256 dos CSVs de origem. Escrita atômica (arquivo temporário e renomear). O zip é lido em fluxo e as entradas grandes (limites, buracos...) são descartadas sem gravar.
3. **Atualização** (`PardalAtualizador`): lê `catalogo.json` (pequeno), compara `artifact_built_at`/sha256 por UF com o `.meta` e baixa o zip só se mudou. Descobre as UFs pelo `bbox` do catálogo: a da posição atual, as da prévia de rota e as vizinhas só sob pedido. Verifica sha256 dos CSVs do zip contra o catálogo antes de trocar o arquivo. Uma UF por vez, com recuo exponencial em falha, e só em Wi-Fi por padrão (pergunta 1).
4. **Mapeamento** para os tipos atuais: `FIXED` e `SECTION` (início) → `RADAR` (com `limit_kmh`), `RED_LIGHT` → `SEMAFORO_COM_CAMERA`, `BUMP|HUMP|TABLE|CUSHION|UNSPECIFIED` → `LOMBADA`, `RUMBLE_STRIP` → `FAIXA_SONORA`, `LEVEL_CROSSING` → `TRAVESSIA_FERROVIARIA`, `active=0` descartado. `SECTION` ganha um tipo próprio `RADAR_DE_TRECHO` ("Radar de trecho, média de X km/h") com início e fim (`end_lat/end_lng`) no v1 só avisando na entrada do trecho. Curvas, escola, animais, cruzamento perigoso e rocha ficam atrás de uma categoria nova, desligada por padrão (pergunta 2).
5. **Sentido**: hoje `direcaoGraus` de OSM vale só como eixo (`soEixo`). Os `direction_deg` do Pardal são o rumo do trânsito fiscalizado: entram como sentido de verdade (aviso só se meu rumo estiver a ≤ 60° dele). Novo campo `PerigoPonto.sentido: Boolean` para distinguir; vazio continua = ambos os sentidos.
6. **Ordem das fontes por quadrado**: Pardal se o estado está baixado e recente (≤ 14 dias); senão o cache do Overpass; senão consulta Overpass como hoje. Não mistura as duas no mesmo quadrado (o Pardal já inclui o OSM; misturar duplicaria pontos).
7. **UI** (Perfil > "Avisos na estrada"): seção "Dados de radares" com estado por UF ("SP: atualizado há 2 dias"), botão "Atualizar agora", "Só em Wi-Fi", espaço usado, e o texto de atribuição e licença com link para o Pardal e para o OSM. No mapa e nos cartões nada muda além do novo tipo.
8. **Privacidade**: o app só baixa arquivos públicos de UF inteiro; a posição exata nunca sai do aparelho (melhor que o Overpass, que recebe o quadrado). User-Agent como hoje.

## Plano de tarefas (cada uma com testes e revisão)

1. **Leitor e mapeamento**: parsers dos três CSVs (com `lat,lng`, linhas ruins ignoradas, `active=0`) e o mapeamento da seção anterior, com fixtures reais copiadas do Pardal (SP e AC). Inclui os tipos novos (`RADAR_DE_TRECHO`) e `PerigoPonto.sentido`.
2. **Sentido e trecho no avisador**: `AvisadorDePerigos`/`PerigosAdiante` usam `sentido` como filtro de rumo; trecho avisa na entrada; testes com radar nos dois sentidos de uma via de pista dupla.
3. **Armazenamento e `PardalSource`**: formato enxuto, índice por quadrado, escrita atômica, leitura tolerante a arquivo corrompido (ignora o estado), testes.
4. **Catálogo e atualizador**: cliente do `catalogo.json`, escolha de UF por `bbox`, download em fluxo com limite de tamanho, verificação de sha256, recuo, só Wi-Fi. Antes de codar, medir o tamanho real dos zips e ajustar o limite.
5. **Fonte composta e integração**: `PardalOuOverpass` no lugar da fonte atual, regra de frescor, gatilhos (posição, prévia de rota, janela da navegação), sem mexer no cache do Overpass.
6. **Tela, atribuição e docs**: seção em Perfil, textos de licença, `docs/` (arquitetura, roteiro de teste manual: baixar SP, modo avião, rota em pista dupla, estado não baixado cai no Overpass), CHANGELOG.

Ordem: 1 → 2 → 3 → 4 → 5 → 6. Estimativa: 6 tarefas pequenas a médias; a 4 e a 5 são as que mais exigem revisão (rede e concorrência).

## Riscos

- Projeto de terceiro, sem garantia de continuidade ou de formato: mitigado por manter o Overpass como reserva, ler só colunas por nome (cabeçalho) e ignorar colunas novas, e checar `schema` do catálogo (hoje 2; se mudar, para de atualizar e mantém o dado antigo).
- Dados com falhas parciais de fonte em algumas releases: mostrar a data do dado na tela; não apagar o dado antigo antes de validar o novo.
- Falso positivo (radar que não existe mais): o texto do aviso fica como hoje; a sinalização da via prevalece.
- Tamanho e bateria: um estado por vez, só em Wi-Fi, em segundo plano curto; índice em memória só do estado em uso.
- Licença: atribuição visível no app e no repositório; não publicamos o banco derivado.

## Decisões do dono (2026-11-24): sim às quatro recomendações

1. Só Wi-Fi por padrão; "Atualizar agora" avisa o tamanho antes de usar dados móveis.
2. Categoria nova "Pontos de atenção" (curva perigosa, escola, animais, cruzamento perigoso, queda de pedras), desligada por padrão.
3. Automático: estado da posição e os da rota; mais uma lista para escolher estados antes de viajar.
4. Conferência jurídica da ODbL fica para o projeto da Play Store; por ora só atribuição.

## Perguntas (respondidas acima)

1. Baixar também com dados móveis? Recomendo só Wi-Fi, com botão "Atualizar agora" que avisa o tamanho antes de usar dados móveis.
2. Incluir "pontos de atenção" além de passagem de nível (curva perigosa, escola, animais, cruzamento perigoso) como categoria nova, desligada por padrão? Recomendo sim, desligada.
3. Estados: baixar só o da posição e os da rota, ou oferecer "baixar o Brasil todo" para viagens longas offline? Recomendo posição e rota automáticos, mais uma lista para escolher estados antes de viajar.
4. Licença: para o app é só atribuição; se um dia o app publicar um banco derivado, a ODbL se estende a ele. Quer uma conferência jurídica antes de ir para a Play Store (projeto 7)? Recomendo deixar para esse projeto.
