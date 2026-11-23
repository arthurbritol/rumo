# Avisos de radar, lombada e afins (design)

Data: 2026-11-24. Estado: implementado na branch `feat/movimento-mapa`.

## Objetivo

Deixar o Rumo mais informativo na estrada: avisar (voz, cartão e mapa) de radar de velocidade, semáforo com câmera,
lombada (quebra-molas), faixa sonora e travessia de trem, na navegação e também no modo livre/gravação.

## Fonte de dados e limitação principal

- **OpenStreetMap via Overpass**, gratuito, sem chave. Não há feed oficial brasileiro aberto, e o Waze não tem API para isso.
- **A cobertura no Brasil é incompleta.** Só aparece o que alguém mapeou no OSM: radares fixos de rodovias e capitais
  costumam estar razoavelmente bem, lombadas e travessias de bairro e interior muitas vezes não estão. Radar móvel
  (blitz, pardal portátil) não existe nessa fonte. O aviso é um **auxílio**, não garantia: ausência de aviso não
  quer dizer ausência de radar ou lombada. Isto deve constar na documentação e nos roteiros de teste.
- Futuro (não feito agora): denúncias dos próprios usuários pelo servidor Cloudflare que já existe.

## Tipos (enum `Perigo`) e tags OSM

| Tipo | Tags | Categoria |
|---|---|---|
| RADAR | `highway=speed_camera`, `enforcement=maxspeed` ou `speed_camera` (guarda `maxspeed` numérico) | Radares |
| SEMAFORO_COM_CAMERA | `enforcement=traffic_signals` ou `red_light` | Radares |
| LOMBADA | `traffic_calming=bump`, `hump`, `table`, `cushion`, `speed_table` | Lombadas |
| FAIXA_SONORA | `traffic_calming=rumble_strip` | Lombadas |
| TRAVESSIA_FERROVIARIA | `railway=level_crossing` | Travessias |

Radar vence os demais quando um nó acumula tags. `PASSAGEM_DE_PEDESTRE` elevada não é um tipo à parte: já entra como
lombada (`traffic_calming=table`). `direction` (graus ou N/NE/...) é guardado quando existe; "forward/backward" ficam
de fora (dependem do sentido da via). Elementos repetidos do mesmo tipo a menos de 25 m viram um só.

## Busca e cache

- **Quadrados fixos de 0,1° x 0,1°** (~11 km). Uma consulta Overpass por quadrado (retângulo arredondado a 0,1°, nada de
  uid nem posição exata). Consulta: ver `OverpassPerigoSource.consulta` (`out center tags`, timeout 25 s).
- **Quando carregar**: (1) ao mudar de quadrado, o 3x3 ao redor da posição (na frente da fila); (2) na prévia de rota, só os
  primeiros 10 quadrados da rota SELECIONADA (alternativas não carregam); (3) navegando, uma janela dos próximos ~25 km à frente
  da posição na rota, reposta ao iniciar/recalcular e a cada 10 km andados. Os quadrados da posição e da janela ativa nunca saem da
  memória (teto de 150 quadrados). **Nunca** em `onCameraIdle`
  (regra do projeto, ver `2026-11-10-precos-combustiveis-anp-design.md`).
- **Cache em disco**: um arquivo JSON versionado por quadrado (`files/perigos-v1/{ix}_{iy}.json`), válido por **30 dias**;
  quadrado vazio também é guardado. Arquivo corrompido ou de outra versão conta como ausente (sem migração de banco: é
  cache descartável, por isso não usa Room). Vencido ainda serve sem internet; com internet é buscado de novo.
- **Espelhos** (ordem, medida em 2026-11): `overpass-api.de` (rápido, mas às vezes 502 "at capacity"), `maps.mail.ru/osm/tools/overpass`
  (estável) e `overpass.kumi.systems` (costuma segurar a consulta até estourar o tempo). Leitura com timeout de 25 s e `[timeout:25]` na
  consulta. Um espelho que falhou fica de lado por 10 min e depois volta; se todos estão de lado, tenta todos. Resposta com `remark`
  (ex.: "Query timed out") é falha: nunca vira resultado nem vai para o cache. User-Agent `Rumo/<versão> (app de navegação para motos; ...)`.
- **Limite de taxa**: no máximo 1 consulta simultânea (um trabalhador), intervalo mínimo de 2 s, recuo exponencial global
  em falha (1, 2, 4... até 30 min; 429/502/504/timeout/remark; o `Retry-After` do servidor vale se for maior). A 1ª falha
  limpa o resto da fila (será pedida de novo na próxima mudança de quadrado ou a cada 5 min).
- Sem internet: só o cache.

## Alertas (`PerigosAdiante` + `AvisadorDePerigos`, lógica pura)

- **Com rota ativa e confiável** (não fora da rota, não recalculando): perigos adiante no traçado até 1,5 km e a até
  30 m dele; distância medida ao longo do traçado (projeção no segmento).
- **Sem rota** (livre/gravando, ou fora da rota): até 600 m e a ±35° do rumo, só com velocidade acima de 3 m/s.
- Histerese: o alvo atual tem folga (650 m, ±50°, 40 m do traçado) para não piscar.
- `direction` contrária ao sentido do deslocamento (>100°) é ignorada; câmeras usam só o eixo da via (±60°).
- **Limiares de voz** (uma vez por perigo por passagem; cruzar vários de uma vez fala só o mais curto): RADAR 800 m, 300 m e
  "Radar adiante" ~100 m; SEMÁFORO COM CÂMERA 400 e 150 m; LOMBADA 300 e 100 m; FAIXA SONORA 150 m; TRAVESSIA 400 m.
- **Ultrapassado**: só conta como passado quando o perigo ficou ATRÁS (com rota: projeção negativa no traçado; sem rota: a mais de 90º do
  rumo, com velocidade acima de 3 m/s). Parar no sinal, GPS sem rumo ou velocidade baixa não marcam passado nem apagam o cartão do alvo
  atual (um alvo novo, porém, só nasce em movimento). Parado perto do perigo, nada é falado de novo. A passagem só é esquecida quando a pessoa
  se afasta mais de 2 km (aí, voltando, avisa de novo).
- Radar com `maxspeed` e velocidade acima (+2 km/h de margem do GPS): "...limite 60. Você está acima do limite." (texto e cor
  no cartão; nada de multa na tela).
- A voz fala a distância real arredondada de 50 em 50 m ("Radar à frente, 250 metros"), não o limiar.
- Fixes com precisão pior que 100 m são ignorados.

## Apresentação

1. **Voz** pelo mesmo TTS do `NavAnnouncer` (serviço de rastreio), respeitando Voz/Mudo.
2. **Cartão** compacto (ícone, distância, nome e limite) como parte do essencial da navegação enxuta (`ElementosDaTela.alertaPerigo`),
   também no modo livre quando grava. Retrato: canto inferior esquerdo, acima da barra fina, alinhado aos botões da direita,
   sem tocar o banner de manobra. Paisagem: canto inferior esquerdo ao lado do painel lateral.
3. **Camada do mapa** `hazards` (ícones desenhados em código, sem texto), a partir do zoom 13, abaixo dos pinos de posto; fonte GeoJSON
   atualizada só quando os quadrados mudam (debounce de 1 s), só com os perigos num raio de ~25 km da posição, no máximo 3000 pontos, montada fora da thread principal.
4. **Interruptores** por categoria (Radares e semáforos com câmera, Lombadas, Travessias de trem) em Perfil, padrão ligado,
   guardados nas preferências do app (`perigo_radares`, `perigo_lombadas`, `perigo_travessias`). Desligar vale para voz, cartão e mapa.

## Desempenho e bateria

A checagem roda a ~1 Hz no fluxo de posição que o serviço já tem (nenhum listener novo de GPS). O repositório mantém até 150
quadrados em memória; a lista filtrada pelas categorias é recalculada só quando os quadrados ou as categorias mudam.

## Limitações conhecidas

- **Cobertura**: o OSM brasileiro está longe de completo (Recife: 116 marcadores num quadrado de 0,1°; muita rodovia secundária e cidade pequena sem nada).
- **`enforcement` quase não existe em nós** (nas respostas reais vistas só `highway=speed_camera`); semáforo com câmera é raro na fonte.
- **`direction=forward/backward`** (comum em radares de São Paulo) depende da geometria da via e é IGNORADO hoje; só graus e pontos cardeais valem.
  Em pista dupla, sem ângulo, o aviso pode vir do radar da pista contrária (a até 30 m do traçado). Pendência: usar o sentido da via quando houver geometria.
- **Radar móvel/blitz** não existe nessa fonte. `maxspeed` só numérico; "none"/"signals" viram "sem limite".
- Overpass público é de uso justo e pode recusar ou atrasar; só o cache segue funcionando então.
- Pendência (não feita): proxy próprio num Cloudflare Worker (já existe `workers/traffic-proxy`), com cache por quadrado, para não depender do Overpass público.

## Fora de escopo

Radar móvel, denúncias de usuários, velocidade máxima da via para quem não passa por radar, aviso fora de gravação/navegação
(sem o serviço em primeiro plano não há avisos por voz).
