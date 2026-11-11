# Mudanças

Formato baseado em [Keep a Changelog](https://keepachangelog.com/pt-BR/1.1.0/).

## [0.7.17]

### Corrigido
- **Base de preços da ANP vazia no aparelho ("base ANP: 0 postos")**: a carga da base rodava em três lugares ao mesmo tempo; uma delas podia apagar a tabela de postos e ser cancelada antes de regravar, e o app considerava a base carregada. Agora a carga é uma de cada vez, valida o arquivo antes de apagar, apaga e grava sem poder ser cancelada no meio, e recarrega sozinha se encontrar a base vazia. A busca de postos espera a carga terminar.
- O aviso do mapa mostra o erro de carga da base, se houver.
- **Mapa limpo**: postos sem preço aparecem só com o pino (sem etiqueta), e os com preço ficam por cima.

## [0.7.16]

### Adicionado
- **Médias por município da ANP**: o ETL lê as planilhas oficiais `mensal-municipios-desde-jan2026.xlsx` (base) e `semanal-municipios-2026.xlsx` (atualização), e vale a coleta mais recente de cada município e combustível (gasolina, aditivada, etanol e diesel S10). Posição dos municípios vinda do IBGE (antes, 10 cidades do ES dividiam o mesmo ponto).
- **Mapa em três níveis**: média do estado (zoom bem aberto), média do município (zoom intermediário) e postos com preço próprio (zoom de rua).
- **Cartão do posto sem preço**: mostra a média do município como referência, deixando claro que não é o preço do posto.
- O CSV `ultimas-4-semanas-gasolina-etanol.csv` passa a ser a fonte das semanas recentes dos postos (com as planilhas semanais como reserva).

## [0.7.15]

### Alterado
- O aviso do mapa agora diz quantos postos da base da ANP estão carregados no aparelho (ex.: "20 postos no mapa (9 com preço da ANP) · base ANP: 8587 postos"), para diagnosticar quando nenhum posto aparece com preço.

## [0.7.14]

### Alterado
- **Novo visual dos postos no mapa**: cada posto aparece com um pino na cor e letra da bandeira (Shell, Ipiranga, BR, Ale ou bomba para as demais) e, ao lado, a etiqueta branca com o preço ("sem preço" quando a ANP não pesquisou o posto).
- **Novo cartão do posto**: gasolina comum em destaque (verde), etanol/aditivada/diesel em linhas e o botão principal "Ver rotas".

## [0.7.13]

### Corrigido
- **Nenhum posto com preço**: a ligação entre o posto do mapa e o da ANP escolhia "as 3 cidades mais próximas", mas a base da ANP usa o centro do estado como posição de quase todas as cidades (10 cidades do ES no mesmo ponto), então a escolha era sorteio. Agora compara com os postos do estado (os 2 estados mais próximos) e usa o CEP para não misturar cidades. Em teste com postos reais da Grande Vitória, 25 de 77 casaram com o preço do próprio posto.

## [0.7.12]

### Corrigido
- **Postos com o mesmo preço (R$ 5,95) por todo o mapa**: versões antigas gravaram postos com id `osm_...` usando a média da cidade como se fosse o preço do posto, e o mapa os misturava com os postos novos. Os postos sem preço da ANP agora usam o id `semp_...`; os `osm_...` antigos são apagados a cada abertura e nunca aparecem. A base local é refeita uma vez (versão 4).

## [0.7.11]

### Corrigido
- **Postos não apareciam no mapa**: o mapa só mostrava postos ligados a um posto da ANP, e a ANP pesquisa só uma parte deles (em Serra/ES, 29 de ~90). Agora **todos os postos achados aparecem**; os que a ANP pesquisou mostram o preço próprio deles, e os demais aparecem sem preço (nunca uma média), com o cartão oferecendo "Informar preços". O aviso do mapa diz quantos postos há e quantos têm preço da ANP.

## [0.7.10]

### Corrigido
- **Postos do mapa recebiam o preço de outro posto**: a regra "só um posto da ANP na rua" dava o mesmo preço a vários postos do mapa na mesma avenida. Removida; agora cada ligação exige um único candidato e, se dois postos do mapa caem no mesmo da ANP, fica o de ligação mais forte.
- **Mais postos com preço**: novo casamento pelo **nome do posto** (ex.: "Posto Coliseu", "Posto Solemar") e por CEP + bandeira, para quando a rua do mapa difere da cadastrada na ANP.
- Ao atualizar, as ligações antigas do mapa são descartadas e refeitas com as regras novas.

## [0.7.9]

### Alterado
- **Base de preços da ANP ampliada**: a base embarcada agora vem da **série histórica de 12 meses** (nov/2025 a out/2026) da ANP, com **8.587 postos** (antes 4.628, só de uma semana), cada um com o preço da coleta mais recente por combustível.
- **Atualização semanal por cima da base**: `sync_anp_fuel.py` baixa as 4 planilhas semanais mais recentes (`revendas_lpc_*.xlsx`) e aplica sobre a série histórica; a coleta mais nova de cada posto vence. O workflow semanal publica o resultado em `anp-data-latest`.
- Postos do mapa já ligados à ANP recebem o preço novo a cada atualização da base (um preço informado pelo usuário vale até a ANP coletar depois).
- Médias de cidade e estado usam só coletas dos últimos 45 dias da base.

### Corrigido
- O CNPJ (coluna 0 do XLSX semanal) era ignorado; agora é o id estável do posto (`anp_<cnpj>`).
- Testes Python do ETL estavam desatualizados e falhavam; reescritos.

## [0.7.4]

### Adicionado e Corrigido
- **Integração direta com o Levantamento Semanal da ANP (`sync_anp_fuel.py`)**: crawler e extrator nativo em Python que acessa diretamente a página oficial de últimas semanas pesquisadas da ANP, processa o relatório semanal de revendas (`revendas_lpc_*.xlsx`) e extrai estritamente **Gasolina Comum**, **Gasolina Aditivada** e **Etanol** de todo o Brasil (405 cidades, 27 estados e 4.628 postos pesquisados), gerando o asset comprimido `anp_fuel_seed.json.gz`.
- **Descoberta por proximidade estrita (`FuelStationFinder`)**: raio máximo rigoroso (`<= 8 km`) e ordenação automática por menor distância geométrica (`Geo.distanceM`), eliminando o bug de postos distantes aparecerem antes dos locais no mapa.
- **Informe manual de preços na bomba (`EditFuelPricesDialog` e `FuelStationCard`)**: motociclistas podem informar ou atualizar em tempo real os preços praticados na bomba para Gasolina Comum, Aditivada e Etanol, com gravação persistente no Room (`updateStationPrices`) e atualização imediata nos cards e marcadores.
- **Busca sob demanda na área visível (`MapScreen` e `FuelMapCoordinator`)**: botão flutuante estilizado Novare "Buscar postos nesta área" permitindo explorar livremente outras regiões do mapa sem depender apenas do deslocamento automático.
- **Sincronização imediata de câmera com GPS no mapa (`HunterMap`)**: câmera posiciona na coordenada real do usuário na abertura em vez de reter coordenadas padrão.

## [0.7.3]

### Corrigido e Aprimorado
- **Busca ultra-rápida de postos de combustível (`FuelStationFinder`)**: integração com o Komoot Photon como provedor primário (`osm_tag=amenity:fuel`), retornando postos em menos de 1 segundo (em substituição ao Overpass que sofria com timeouts de mais de 15 segundos).
- **Persistência garantida e carregamento imediato (0ms)**: postos encontrados são salvos imediatamente no banco Room (`anp_stations`) e, em acessos futuros na mesma região, são carregados instantaneamente da base local sem requisições de rede.
- **Não-cancelamento da busca durante movimento de câmera**: a busca online em background agora executa desacoplada de cancelamento por arrasto de mapa ou updates de GPS (`collectLatest`), garantindo que a requisição finalize e popule o banco mesmo durante a pilotagem.
- **Carga do seed embarcado para bases pré-existentes**: o método `ensureSeedLoaded()` agora confere se a base de cidades está completa (ao menos 40 cidades polo), garantindo que atualizações de versões anteriores recebam todas as médias municipais e postos de referência imediatamente.

## [0.7.2]

### Corrigido e Aprimorado
- **Busca dinâmica de postos reais no mapa (`HunterMap` + `FuelStationFinder`)**: quando o cache local de uma área não possui postos, o app busca postos reais do OpenStreetMap via Overpass, cruza instantaneamente com as médias da ANP em 3 níveis e salva no Room para consultas offline subsequentes.
- **Feedback de busca de postos**: pílula visual Novare indicando "Buscando postos próximos..." enquanto a consulta ao OpenStreetMap é executada.
- **Sincronização da ANP no GitHub**: URL de download corrigida para `arthurbritol/rumo` e script ETL com suporte automático a arquivos `.zip` e `.csv` dos Dados Abertos Gov.br da ANP.
- **Atualização manual de preços no Perfil (`ProfileScreen`)**: nova seção "Preços de Combustíveis (ANP)" com informações de cobertura e botão para disparar a sincronização imediata.
- **Resiliência na sugestão da Garagem (`AddFuelupDialog`)**: fallback de localização (GPS quente, LocationManager, última viagem gravada ou marco zero estadual), garantindo que a sugestão de preço sempre apareça.
- **Seed inicial embarcado ampliado**: base com todas as 27 UFs e cidades polo adicionais (Campinas, Santos, Ribeirão Preto, São José dos Campos, Sorocaba, Uberlândia, Juiz de Fora, Niterói, Londrina, Maringá, Joinville, Caxias do Sul, Feira de Santana, Caruaru, Petrolina, Campina Grande).

## [0.7.0]

### Adicionado
- **Integração com a Série Histórica de Preços de Combustíveis da ANP**: levantamento de preços da Agência Nacional do Petróleo integrado para consulta e apoio ao motociclista em todo o território nacional.
- **Marcadores de postos com badge de preço e ícone Font Awesome `fa_gas_pump` na aba Mapa (`HunterMap`)**: visualização de postos no mapa com carregamento 100% offline via Room v4, sem travamentos e sem requisições de rede contínuas durante a pilotagem.
- **Card detalhado de posto (`FuelStationCard`) em Design System Novare (`HCard`)**: painel de detalhes com tabela comparativa de combustíveis (Gasolina Comum, Aditivada, Etanol e Diesel S10) e termômetro de paridade Etanol x Gasolina (regra dos 70%).
- **Hierarquia de cobertura em 3 níveis**: resolução inteligente de preços: Posto individual pesquisado pela ANP -> Média municipal -> Média estadual (UF), assegurando referências confiáveis mesmo em rodovias ou municípios sem coleta direta.
- **Apoio no registro de abastecimento na Garagem (`AddFuelDialog`)**: chip inteligente sugerindo o preço médio da cidade/estado atual a partir do GPS com preenchimento em 1 toque.
- **Pipeline ETL semanal em Python (`scripts/sync_anp_fuel.py`) e workflow agendado no GitHub Actions**: ingestão e enriquecimento de dados da ANP com cruzamento de coordenadas OSM e IBGE gerando payload compactado (`anp-fuel-latest.json.gz`).
- **Seed inicial embarcado com as 27 UFs e capitais brasileiras**: arquivo comprimido nos assets para operação offline imediata desde o primeiro boot.
- **Migração de banco de dados Room `MIGRATION_3_4`**: adição das tabelas `anp_stations`, `anp_city_stats` e `anp_state_stats`.

## [0.6.0]

### Adicionado
- **Design System Novare**: paleta semântica completa com variantes Midnight Navy (modo escuro profundo em camadas com base azul-marinho `#0B111D`, superfícies `#141C2B`/`#1E2D44` e acentos ciano e âmbar) e Clean Slate (modo claro contrastante e arejado com bordas tonais sutis).
- **Tipografia e Geometria refinadas**: cantos arredondados contínuos com `SquircleIcon`, `HCard` e elevações sutis via micro-bordas tonais.
- **Biblioteca de Ícones Nativos Font Awesome 6**: coleção vetorial em `app/src/main/res/drawable/fa_*.xml` com mais de 40 glifos de alta legibilidade para navegação, telemetria, controles e ações (Solid, Regular e Brand).
- **Componentes Base Unificados (`app.hunter.app.ui.Components`)**:
  - `HCard`: container com cantos arredondados de 22dp, preenchimento de superfície e micro-borda tonal suave.
  - `SquircleIcon`: container quadrado arredondado para ícones Font Awesome ou Material com fundo colorido.
  - `HeroMetricCard`: cartão hero para grandes instrumentos (hodômetro, resumo do rolê, autonomia) com alinhamento refinado de ações.
  - `RumoSearchField`: campo de busca estilizado de 48dp com cantos de 14dp, área de toque acessível e ícone Font Awesome integrado.
  - `RumoPrimaryButton`: botão de ação primária com altura de 54dp, cantos de 16dp e gradiente Novare (`#FF7A1A` -> `#EA580C`).
  - `RumoSecondaryButton`: botão de ação secundária com outline, cantos de 16dp e suporte a estado desabilitado.
  - `StatusPill`: pílula compacta de status/tendência com fundo colorido e tipografia destacada.
  - `RumoBadge`: badge compacto com cantos arredondados e cor configurável.

### Alterado
- **Navegação e Interface Global**: substituição de Material Icons padrão por glifos vetoriais Font Awesome 6 (`fa_*.xml`) em todas as telas (`MapScreen`, `TripsScreen`, `GarageScreen`, `FriendsScreen`, `RoleScreens`, `ProfileScreen`, `OnboardingScreen`, `OfflineMapsScreen`).
- **Aba Mapa e HUD**: controles flutuantes com `SquircleIcon` (camadas, busca, centralização, bússola), HUD de navegação e badges de telemetria refinados.
- **Garagem e Manutenção**: cartões de status e métricas de moto reestilizados com `HCard`, badges de alerta tonalizados e formulários modernizados.
- **Histórico e Detalhes de Viagem**: cards de viagem modernizados em `TripsScreen` com `LazyColumn` ponderado, layout compacto e estatísticas organizadas com `HeroMetricCard`.
- **Social e Amigos**: cards de amigos e rolês em grupo com avatares em squircle, chips de status ("Na estrada"), sino de notificações interativo com rolagem contextual e botões de ação consistentes.
- **Onboarding e Telas de Apoio**: apresentação inicial com ilustrações harmonizadas, tipografia equilibrada e transições elegantes.

## [0.5.2]

### Adicionado
- **Localização compartilhada com amigos**: acompanhe amigos em tempo real durante passeios e viagens com a moto conectada via Bluetooth (Tripper Pod).
- **Controle seletivo amigo por amigo**: ative ou desative o compartilhamento individualmente para cada amigo através de um interruptor dedicado na aba Amigos.
- **Transmissão atrelada à moto**: a localização só é transmitida para a nuvem quando a moto estiver conectada (`PodPhase.CONNECTED`) e houver ao menos um amigo autorizado. Ao desligar a moto ou desconectar o Pod, a localização é apagada do Firestore imediatamente.
- **Status "Na estrada" na aba Amigos**: amigos autorizados que estiverem pilotando mostram o badge destacado "Na estrada" e o botão "Ver no mapa".
- **Marcadores de moto no mapa principal**: motos de amigos autorizados são desenhadas no `HunterMap` com ícone orientado pelo rumo (`bearing`), nome do amigo e tempo decorrido desde a última leitura (ex: "agora", "há 1 min").
- **Centralização suave de câmera**: tocar em "Ver no mapa" na aba Amigos alterna para a aba Mapa e centraliza a visualização na coordenada exata da moto do parceiro.
- **Regras de segurança no Firestore**: novas coleções `permissoesLocalizacao` e `localizacoes` protegidas por regras estritas — somente amigos mútuos com permissão explícita ativa conseguem ler coordenadas.
- **Limpeza completa ao apagar a conta**: ao excluir a conta, todos os registros de localização e permissões de localização (enviadas e recebidas) são limpos da nuvem.

### Alterado
- Regras de segurança do Firestore atualizadas com as novas coleções de localização: é necessário republicá-las com `firebase deploy --only firestore:rules`. Veja [docs/FIREBASE.md](docs/FIREBASE.md).

## [0.5.0]

### Adicionado
- Aba **Amigos** (precisa de conta): busca por nome (a partir de 3 letras, até 20 resultados), convites (enviar, aceitar, recusar, cancelar), desfazer amizade e bloquear. Cada amigo mostra a moto e os km rodados na semana (de segunda a domingo). Limite de 50 amigos.
- "Aparecer na busca": pergunta única na primeira vez que você abre a aba e interruptor no Perfil. Desligado, ninguém te acha pelo nome (amigos continuam te vendo).
- **Rolê em grupo**: crie um rolê com até 9 amigos; cada um informa o próprio orçamento e o app calcula a média, o alcance sugerido (km) pelo custo por km de cada um e avisa quem não tem dados de consumo. Quem aceitou pode sair; o criador remove participantes ou encerra. Rolês expiram em 7 dias (política de TTL no Firestore).
- Marcador na aba Amigos com convites e rolês pendentes. Sem internet a aba mostra um aviso e o botão "Tentar de novo".
- Apagar a conta também apaga o que é social (perfil público, atividade, convites, amizades, bloqueios e participação em rolês), com retomada se for interrompido.
- Pacote `app.hunter.social` no `sync-core` (motor, semana, planejador de rolê, chave de busca) e adaptador `FirestoreSocialStore` no app. Novas regras do Firestore, com testes de emulador por caminho.

### Alterado
- Sem conta (ou sem `google-services.json`) a aba Amigos só mostra o convite para entrar; nada social roda.
- Regras do Firestore ampliadas: é preciso republicá-las (`firebase deploy --only firestore:rules`) e ativar o TTL de `roles.expiraEm`. Veja [docs/FIREBASE.md](docs/FIREBASE.md).

## [0.4.0]

### Adicionado
- Aviso de atualização mais esperto: o app procura versão nova toda vez que volta para a frente (no máximo a cada 15 minutos) e o Perfil ganhou "Buscar atualização", com a versão instalada e o resultado da busca.
- Conta opcional (Firebase): entrar com Google ou e-mail e senha (com confirmação de e-mail e "esqueci a senha"). O app continua funcionando sem conta.
- Sincronização com a conta: perfil, moto, hodômetro, abastecimentos, manutenção, locais favoritos, resumo das viagens e a preferência de voz. Funciona sem internet (edita offline e sincroniza ao reconectar); a edição mais recente vence e nada é duplicado.
- Quem usava sem conta e cria uma conta mantém os dados (mesclados, sem duplicar). Reinstalar e entrar recupera tudo, exceto o trajeto completo das viagens, que fica só no aparelho (a viagem volta no histórico, sem mapa).
- Apresentação na primeira abertura (4 telas, com "Pular"), login, permissões uma a uma (sem localização em segundo plano) e cadastro da moto, completo ou curto. "Rever apresentação" fica no perfil.
- Perfil (ícone no topo da Garagem): sair da conta (bloqueado enquanto houver algo sem enviar), apagar meus dados e minha conta (digitar APAGAR, com retomada se for interrompido) e avisos para visitante e e-mail não confirmado.
- Módulo `sync-core` (JVM puro) com o motor de sincronização, a mesclagem e o fluxo de apresentação, mais regras do Firestore com testes de emulador.

### Alterado
- Banco do app na versão 3 (migração automática, sem perder dados): id global, data de alteração e marca de pendente nos registros.
- Quem já usava o app não vê a apresentação nem login forçado: entra direto no mapa e recebe um aviso discreto para criar conta.
- Ao voltar a internet o app sincroniza sozinho.
- Sem `google-services.json` no build o login fica desabilitado e o app funciona como antes.

## [0.3.0]

### Adicionado
- Atualização pelo próprio app: avisa quando há release nova no GitHub, mostra as alterações e baixa/instala o APK.
- Nome **Rumo**, ícone adaptativo (com versão monocromática) e identidade visual.
- Busca de destino com favoritos e recentes instantâneos, distância até cada resultado e atalhos.
- Aviso de falta de internet e novo cálculo automático da rota quando a conexão volta.
- Posição por Wi-Fi/antenas e última posição conhecida no mapa (funciona em ambiente fechado).
- Botão do Android volta ao mapa a partir das outras abas; barra de abas some durante a navegação.

### Alterado
- Mapa fica sempre montado (trocar de aba não recarrega tiles); estilo claro mais leve.
- Rede: tempos limite separados, 3 tentativas com pausa crescente, conexão reaproveitada e cache da busca.
- APK de release no lugar do de debug (bem mais fluido).

## [0.2.0]

### Adicionado
- Mapa MapLibre com rotas para moto (Valhalla), navegação curva a curva com voz e recálculo.
- Planejador de paradas por autonomia, mapas offline e lembretes de manutenção.
- Histórico de viagens com traçado colorido por velocidade, garagem (combustível, manutenção, hodômetro).

## [0.1.0]

### Adicionado
- Protocolo do Tripper Pod (`pod-protocol`), conexão BLE, gravação de viagens em segundo plano.
