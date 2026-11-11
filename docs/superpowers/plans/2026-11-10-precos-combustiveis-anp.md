# Mini Projeto 6: Preços de Combustíveis (Série Histórica ANP & Postos no Mapa) - Plano de Implementação

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Integrar a série histórica de preços de combustíveis da ANP ao Rumo, exibindo postos no mapa com marcadores de preço em tempo real e 100% offline, card detalhado com termômetro de paridade Etanol x Gasolina (regra dos 70%), hierarquia em 3 níveis (posto exato -> média municipal -> média estadual) e apoio de valor de bomba na Garagem.

**Architecture:** Abordagem offline-first onde um script ETL no GitHub Actions processa o CSV semestral da ANP, cruza com o extrato de postos do OpenStreetMap e centróides do IBGE, e gera um payload comprimido (`anp-fuel-latest.json.gz` < 1.5MB) distribuído via CDN do GitHub. O app Android armazena esses dados no Room (`AppDatabase` v4) com seed inicial embarcado nos assets. O mapa (`HunterMap`) consulta os postos do Room diretamente por *Bounding Box* espacial com zoom >= 11, exibindo marcadores Novare com badges de preço sem realizar requisições contínuas de rede.

**Tech Stack:** Kotlin 2.2.20, AGP 8.13.2, Jetpack Compose, Room v3 (migração v4), Python 3.10+ (ETL), Design System Novare & Font Awesome 6.

**Spec:** `docs/superpowers/specs/2026-11-10-precos-combustiveis-anp-design.md`

## Global Constraints

- **Lógica JVM Pura:** Cálculos matemáticos de paridade e parsing de payloads residem nos módulos JVM `core-model` e `navigation`.
- **Zero Custo de Nuvem / Proteção Spark:** Nenhum dado público de combustível é gravado no Firestore.
- **Funcionamento 100% Offline:** Os postos e médias devem carregar instantaneamente do Room local mesmo sem sinal celular.
- **Overpass Rate-Limit:** Nunca fazer chamadas Overpass em `onCameraIdle` do mapa.
- **Design System Novare:** Tokens de cores adaptáveis (Midnight Navy e Clean Slate) e ícones Font Awesome 6 (`fa_gas_pump.xml`).
- **Idioma:** Estritamente `pt-BR`.

## Review Focus

1. **Combustível com valor zero ou negativo no cálculo de paridade:** deve retornar nulo sem lançar `ArithmeticException` ou divisão por zero.
2. **Posto em coordenada sem cidade mapeada na ANP:** deve recorrer sem quebra à média estadual da UF correspondente.
3. **Migração do Room v3 para v4 com base de dados de usuário pré-existente:** deve preservar intactas todas as tabelas de viagens, abastecimentos e manutenções anteriores.
4. **Download ou descompressão de payload corrompido no Worker de sync:** deve abortar com segurança sem apagar a base de dados local existente.
5. **Movimentação rápida do mapa (pan/zoom contínuo):** a consulta espacial por Bounding Box deve ter debounce e rodar em `Dispatchers.IO` para não travar a UI a 60 FPS.

---

### Task 1: Modelos de Dados e Cálculo de Paridade (`core-model` e `navigation`)

**Files:**
- Create: `core-model/src/main/kotlin/app/hunter/model/FuelParity.kt`
- Create: `core-model/src/test/kotlin/app/hunter/model/FuelParityTest.kt`
- Create: `navigation/src/main/kotlin/app/hunter/nav/AnpFuelPayload.kt`
- Create: `navigation/src/test/kotlin/app/hunter/nav/AnpFuelPayloadTest.kt`

**Interfaces:**
- Produces:
  - `FuelParityMath.calculateRatio(etanolPrice: Double, gasolinaPrice: Double): Double?`
  - `FuelParityMath.isEtanolAdvantageous(ratio: Double): Boolean`
  - `FuelParityMath.formatParityDescription(ratio: Double): String`
  - `data class AnpFuelPayload(val versao: String, val geradoEm: String, val cidades: Map<String, AnpCityDto>, val estados: Map<String, AnpStateDto>, val postos: List<AnpStationDto>)`
  - `AnpFuelPayload.fromJson(jsonString: String): AnpFuelPayload`

- [ ] **Step 1: Escrever os testes que falham para `FuelParityMath`**
Criar `core-model/src/test/kotlin/app/hunter/model/FuelParityTest.kt` testando:
  - Paridade a 70% exato (vantajoso etanol).
  - Paridade a 72% (vantajosa gasolina).
  - Preços zerados ou negativos retornando nulo (Review Focus 1).
  - Textos semânticos formatados em `pt-BR`.

- [ ] **Step 2: Executar o teste e verificar a falha**
Run: `.\gradlew.bat :core-model:test --tests "app.hunter.model.FuelParityTest" --console=plain`
Expected: FAIL (classe não encontrada).

- [ ] **Step 3: Implementar `FuelParity.kt` em `core-model`**
Implementar o objeto `FuelParityMath` com constante `PARITY_THRESHOLD = 0.70`, cálculo seguro de razão e descrição semântica.

- [ ] **Step 4: Escrever os testes que falham para `AnpFuelPayload` em `navigation`**
Criar `navigation/src/test/kotlin/app/hunter/nav/AnpFuelPayloadTest.kt` testando a deserialização de JSON compacto de postos, cidades e estados.

- [ ] **Step 5: Implementar `AnpFuelPayload.kt` em `navigation`**
Implementar os DTOs serializáveis com `kotlinx.serialization.json.Json { ignoreUnknownKeys = true }`.

- [ ] **Step 6: Executar a suíte de testes e validar sucesso**
Run: `.\gradlew.bat :core-model:test :navigation:test --console=plain`
Expected: PASS.

- [ ] **Step 7: Commit**
```bash
git add core-model/ navigation/
git commit -m "feat(core): adicionar calculo de paridade de combustivel e DTOs da ANP"
```

---

### Task 2: Script de Ingestão ETL e Geração do Seed Inicial (`scripts/` e `assets/`)

**Files:**
- Create: `scripts/sync_anp_fuel.py`
- Create: `scripts/test_sync_anp_fuel.py`
- Create: `app/src/main/assets/anp_fuel_seed.json.gz`
- Create: `.github/workflows/sync-anp.yml`

**Interfaces:**
- Produces:
  - Script Python CLI: `python scripts/sync_anp_fuel.py --input <csv> --output <json.gz>`
  - Seed embarcado `app/src/main/assets/anp_fuel_seed.json.gz` contendo cidades polo e estados de referência para uso imediato offline no primeiro boot.

- [ ] **Step 1: Escrever o teste unitário Python para o script ETL**
Criar `scripts/test_sync_anp_fuel.py` testando o parsing de um fragmento de CSV no padrão ANP com agrupamento por UF/município, cálculo de mín/méd/máx e geração de JSON.

- [ ] **Step 2: Executar o teste Python e verificar a falha**
Run: `python scripts/test_sync_anp_fuel.py`
Expected: FAIL (script não existe).

- [ ] **Step 3: Implementar `scripts/sync_anp_fuel.py`**
Script Python que processa dados da ANP, gera agregações de cidades (`gc`, `ga`, `et`, `ds`), agregações estaduais e lista de postos, comprimindo a saída em `.json.gz`.

- [ ] **Step 4: Executar o teste Python e validar sucesso**
Run: `python scripts/test_sync_anp_fuel.py`
Expected: PASS.

- [ ] **Step 5: Gerar o arquivo de seed inicial `anp_fuel_seed.json.gz`**
Executar o script com dados representativos das capitais e estados brasileiros para gerar `app/src/main/assets/anp_fuel_seed.json.gz`.

- [ ] **Step 6: Criar o workflow `.github/workflows/sync-anp.yml`**
Configurar workflow agendado semanalmente (`cron: '0 3 * * 1'`) para atualização contínua e publicação na release.

- [ ] **Step 7: Commit**
```bash
git add scripts/ app/src/main/assets/ .github/workflows/
git commit -m "feat(etl): criar script de ingestao ANP, workflow semanal e seed inicial"
```

---

### Task 3: Entidades e DAOs do Room com Migração v3 -> v4 (`app/data/anp/` e `AppDatabase.kt`)

**Files:**
- Create: `app/src/main/kotlin/app/hunter/app/data/anp/AnpEntities.kt`
- Create: `app/src/main/kotlin/app/hunter/app/data/anp/AnpFuelDao.kt`
- Create: `app/src/main/kotlin/app/hunter/app/data/Migration3To4.kt`
- Modify: `app/src/main/kotlin/app/hunter/app/data/AppDatabase.kt`
- Create: `app/src/test/kotlin/app/hunter/app/data/Migration3To4Test.kt`

**Interfaces:**
- Consumes:
  - `AppDatabase` v3 existente.
- Produces:
  - `AnpStationEntity`, `AnpCityStatsEntity`, `AnpStateStatsEntity`
  - `AnpFuelDao.getStationsInBox(minLat: Double, minLon: Double, maxLat: Double, maxLon: Double): List<AnpStationEntity>`
  - `AnpFuelDao.getCityStats(cityKey: String): AnpCityStatsEntity?`
  - `AnpFuelDao.getStateStats(uf: String): AnpStateStatsEntity?`
  - `AnpFuelDao.insertAllStations(stations: List<AnpStationEntity>)`
  - `Migration3To4.MIGRATION_3_4`

- [ ] **Step 1: Escrever teste de migração v3 -> v4 em SQLite real**
Criar `app/src/test/kotlin/app/hunter/app/data/Migration3To4Test.kt` simulando o esquema v3 e executando os comandos DDL da migração v4, conferindo integridade das tabelas existentes (Review Focus 3).

- [ ] **Step 2: Executar o teste e verificar a falha**
Run: `.\gradlew.bat :app:testDebugUnitTest --tests "app.hunter.app.data.Migration3To4Test" --console=plain`
Expected: FAIL (classes de migração não existem).

- [ ] **Step 3: Implementar entidades, DAO e `Migration3To4`**
Definir `AnpStationEntity`, `AnpCityStatsEntity`, `AnpStateStatsEntity` com índices espaciais, o DAO com consultas por Bounding Box e atualizar `AppDatabase` para versão 4.

- [ ] **Step 4: Executar o teste de migração e validar sucesso**
Run: `.\gradlew.bat :app:testDebugUnitTest --tests "app.hunter.app.data.Migration3To4Test" --console=plain`
Expected: PASS.

- [ ] **Step 5: Commit**
```bash
git add app/src/main/kotlin/app/hunter/app/data/ app/src/test/kotlin/app/hunter/app/data/
git commit -m "feat(db): adicionar tabelas de precos ANP e migracao Room v3 para v4"
```

---

### Task 4: Repositório de Preços com Resolução em 3 Níveis e Carga de Seed (`FuelPriceRepository`)

**Files:**
- Create: `app/src/main/kotlin/app/hunter/app/fuel/FuelPriceRepository.kt`
- Create: `app/src/main/kotlin/app/hunter/app/fuel/FuelPriceSyncWorker.kt`
- Create: `app/src/test/kotlin/app/hunter/app/fuel/FuelPriceRepositoryTest.kt`

**Interfaces:**
- Consumes:
  - `AnpFuelDao`, `AnpFuelPayload`, `GeoBox`, `FuelParityMath`.
- Produces:
  - `data class ResolvedFuelStation(val id: String, val nome: String, val bandeira: String, val endereco: String, val lat: Double, val lon: Double, val precoGasolina: Double?, val precoGasolinaAdit: Double?, val precoEtanol: Double?, val precoDiesel: Double?, val fonte: PriceSource, val dataRef: String)`
  - `enum class PriceSource { POSTO_ANP, MEDIA_MUNICIPAL, MEDIA_ESTADUAL }`
  - `FuelPriceRepository.getStationsInBox(box: GeoBox): List<ResolvedFuelStation>`
  - `FuelPriceRepository.getNearestReferencePrice(lat: Double, lon: Double): ResolvedFuelStation?`
  - `FuelPriceRepository.ensureSeedLoaded()`

- [ ] **Step 1: Escrever testes unitários para a resolução de preços**
Criar `app/src/test/kotlin/app/hunter/app/fuel/FuelPriceRepositoryTest.kt` testando:
  - Posto com preço exato -> `PriceSource.POSTO_ANP`.
  - Posto sem preço com cidade mapeada -> `PriceSource.MEDIA_MUNICIPAL`.
  - Posto sem cidade mapeada -> `PriceSource.MEDIA_ESTADUAL` (Review Focus 2).
  - Carga do seed em formato `.json.gz`.

- [ ] **Step 2: Executar teste e verificar a falha**
Run: `.\gradlew.bat :app:testDebugUnitTest --tests "app.hunter.app.fuel.FuelPriceRepositoryTest" --console=plain`
Expected: FAIL.

- [ ] **Step 3: Implementar `FuelPriceRepository.kt` e `FuelPriceSyncWorker.kt`**
Implementar a lógica de carregamento do seed em background, consultas espaciais e o WorkManager para download com checagem de ETag e tratamento de payload corrompido (Review Focus 4).

- [ ] **Step 4: Executar o teste e validar sucesso**
Run: `.\gradlew.bat :app:testDebugUnitTest --tests "app.hunter.app.fuel.FuelPriceRepositoryTest" --console=plain`
Expected: PASS.

- [ ] **Step 5: Commit**
```bash
git add app/src/main/kotlin/app/hunter/app/fuel/ app/src/test/kotlin/app/hunter/app/fuel/
git commit -m "feat(fuel): implementar repositorio de precos com resolucao em 3 niveis e sync worker"
```

---

### Task 5: Componente de UI `FuelStationCard` com Termômetro de Paridade (`FuelStationCard.kt`)

**Files:**
- Create: `app/src/main/kotlin/app/hunter/app/ui/FuelStationCard.kt`
- Create: `app/src/test/kotlin/app/hunter/app/ui/FuelStationCardTest.kt`

**Interfaces:**
- Consumes:
  - `ResolvedFuelStation`, `FuelParityMath`, `HCard`, `SquircleIcon`, `StatusPill`, `RumoPrimaryButton`, `RumoSecondaryButton`.
- Produces:
  - `@Composable fun FuelStationCard(station: ResolvedFuelStation, distanceM: Double?, onNavigate: () -> Unit, onAddStop: () -> Unit, onClose: () -> Unit)`

- [ ] **Step 1: Escrever o teste de estrutura do componente Compose**
Criar `app/src/test/kotlin/app/hunter/app/ui/FuelStationCardTest.kt` validando que os dados de preços, bandeira, paridade e botões de ação são estruturados corretamente.

- [ ] **Step 2: Executar o teste e verificar a falha**
Run: `.\gradlew.bat :app:testDebugUnitTest --tests "app.hunter.app.ui.FuelStationCardTest" --console=plain`
Expected: FAIL.

- [ ] **Step 3: Implementar `FuelStationCard.kt`**
Construir o card com `HCard`, ícone Font Awesome `fa_gas_pump`, grid de valores (Gasolina, Etanol, etc.), `StatusPill` informando paridade e qual combustível compensa, carimbo da ANP e botões Novare.

- [ ] **Step 4: Executar o teste e validar sucesso**
Run: `.\gradlew.bat :app:testDebugUnitTest --tests "app.hunter.app.ui.FuelStationCardTest" --console=plain`
Expected: PASS.

- [ ] **Step 5: Commit**
```bash
git add app/src/main/kotlin/app/hunter/app/ui/FuelStationCard.kt app/src/test/kotlin/app/hunter/app/ui/FuelStationCardTest.kt
git commit -m "feat(ui): implementar FuelStationCard com termometro de paridade e Design Novare"
```

---

### Task 6: Camada de Postos e Marcadores com Preço no Mapa (`HunterMap.kt`, `MapScreen.kt`, `HunterViewModel.kt`)

**Files:**
- Modify: `app/src/main/kotlin/app/hunter/app/HunterViewModel.kt`
- Modify: `app/src/main/kotlin/app/hunter/app/ui/HunterMap.kt`
- Modify: `app/src/main/kotlin/app/hunter/app/ui/MapScreen.kt`
- Create: `app/src/test/kotlin/app/hunter/app/ui/FuelStationMapLayerTest.kt`

**Interfaces:**
- Consumes:
  - `FuelPriceRepository`, `ResolvedFuelStation`, `FuelStationCard`.
- Produces:
  - `HunterViewModel.fuelLayerEnabled: StateFlow<Boolean>`
  - `HunterViewModel.toggleFuelLayer()`
  - `HunterViewModel.selectedFuelStation: StateFlow<ResolvedFuelStation?>`
  - `HunterViewModel.selectFuelStation(station: ResolvedFuelStation?)`
  - Marcadores no `HunterMap` com preço em pílula squircle e clique que abre `FuelStationCard`.

- [ ] **Step 1: Escrever teste unitário para o estado da camada no ViewModel**
Criar `app/src/test/kotlin/app/hunter/app/ui/FuelStationMapLayerTest.kt` testando alternância da camada, consulta por Bounding Box com debounce (Review Focus 5) e seleção de posto.

- [ ] **Step 2: Executar o teste e verificar a falha**
Run: `.\gradlew.bat :app:testDebugUnitTest --tests "app.hunter.app.ui.FuelStationMapLayerTest" --console=plain`
Expected: FAIL.

- [ ] **Step 3: Adicionar gerenciamento da camada no `HunterViewModel`**
Integrar fluxo reativo de postos visíveis alimentado pelo `FuelPriceRepository` a partir da caixa delimitadora do mapa.

- [ ] **Step 4: Atualizar `HunterMap.kt` e `MapScreen.kt`**
  - Adicionar botão flutuante squircle na lateral do mapa para ativar/desativar a camada de postos com ícone `fa_gas_pump`.
  - Renderizar marcadores com badge de preço (`R$ 5,79` ou `~R$ 5,82`).
  - Exibir `FuelStationCard` em modal inferior quando um posto estiver selecionado.

- [ ] **Step 5: Executar os testes e validar sucesso**
Run: `.\gradlew.bat :app:testDebugUnitTest --tests "app.hunter.app.ui.FuelStationMapLayerTest" --console=plain`
Expected: PASS.

- [ ] **Step 6: Commit**
```bash
git add app/src/main/kotlin/app/hunter/app/HunterViewModel.kt app/src/main/kotlin/app/hunter/app/ui/
git commit -m "feat(map): adicionar camada de postos com marcadores de preco e card de detalhes"
```

---

### Task 7: Apoio no Registro de Abastecimento na Garagem (`GarageScreen.kt`)

**Files:**
- Modify: `app/src/main/kotlin/app/hunter/app/ui/GarageScreen.kt`
- Create: `app/src/test/kotlin/app/hunter/app/ui/GarageFuelPriceSuggestionTest.kt`

**Interfaces:**
- Consumes:
  - `FuelPriceRepository.getNearestReferencePrice(lat, lon)`.
- Produces:
  - Chip clicável no `AddFuelupDialog` sugerindo o preço médio por litro da cidade atual.

- [ ] **Step 1: Escrever teste para o chip de sugestão de preço na Garagem**
Criar `app/src/test/kotlin/app/hunter/app/ui/GarageFuelPriceSuggestionTest.kt` testando a exibição do texto com o preço médio e o preenchimento automático do valor por litro ao clicar.

- [ ] **Step 2: Executar o teste e verificar a falha**
Run: `.\gradlew.bat :app:testDebugUnitTest --tests "app.hunter.app.ui.GarageFuelPriceSuggestionTest" --console=plain`
Expected: FAIL.

- [ ] **Step 3: Modificar `GarageScreen.kt`**
Integrar a consulta da referência de preço ao diálogo de novo abastecimento e adicionar o chip de sugestão com 1 toque.

- [ ] **Step 4: Executar o teste e validar sucesso**
Run: `.\gradlew.bat :app:testDebugUnitTest --tests "app.hunter.app.ui.GarageFuelPriceSuggestionTest" --console=plain`
Expected: PASS.

- [ ] **Step 5: Commit**
```bash
git add app/src/main/kotlin/app/hunter/app/ui/GarageScreen.kt app/src/test/kotlin/app/hunter/app/ui/GarageFuelPriceSuggestionTest.kt
git commit -m "feat(garage): adicionar sugestao de preco de combustivel no dialogo de abastecimento"
```

---

### Task 8: Documentação, Atualização de Versão e Release Checklist

**Files:**
- Modify: `CHANGELOG.md`
- Modify: `docs/ARQUITETURA.md`
- Modify: `version.txt`

- [ ] **Step 1: Atualizar `CHANGELOG.md`**
Registrar a versão `[0.7.0]` com todas as novidades do Mini Projeto 6 (base ANP, marcadores de preço, paridade 70%, Room v4 e apoio na Garagem).

- [ ] **Step 2: Atualizar `docs/ARQUITETURA.md`**
Documentar a nova seção de Preços de Combustíveis (Série Histórica ANP), a hierarquia em 3 níveis e a estrutura do pipeline ETL.

- [ ] **Step 3: Atualizar `version.txt` para 0.7**
Ajustar a versão para `0.7`.

- [ ] **Step 4: Executar a suíte de testes completa do repositório**
Run: `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat :core-model:test :navigation:test :sync-core:test :app:testDebugUnitTest --console=plain -q`
Expected: PASS com 0 falhas.

- [ ] **Step 5: Validar a compilação de release**
Run: `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat :app:assembleRelease --console=plain -q`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**
```bash
git add CHANGELOG.md docs/ARQUITETURA.md version.txt
git commit -m "docs(release): documentar Mini Projeto 6 e preparar versao 0.7.0"
```

---

### Task 9: Correção do Sync Worker, URL de Download e Pipeline ETL ANP

**Files:**
- Modify: `app/src/main/kotlin/app/hunter/app/fuel/FuelPriceSyncWorker.kt`
- Modify: `scripts/sync_anp_fuel.py`
- Modify: `.github/workflows/sync-anp.yml`
- Modify: `app/src/main/assets/anp_fuel_seed.json.gz`
- Create/Modify: `app/src/test/kotlin/app/hunter/app/fuel/FuelPriceSyncWorkerTest.kt`

**Steps:**
- [x] **Step 1:** Corrigir `DEFAULT_DOWNLOAD_URL` em `FuelPriceSyncWorker.kt` para `https://github.com/arthurbritol/rumo/releases/download/anp-data-latest/anp-fuel-latest.json.gz`.
- [x] **Step 2:** Atualizar `scripts/sync_anp_fuel.py` com suporte a URLs oficiais candidatas da ANP, extração de `.zip`/`.csv`, centróides de mais municípios e geração de seed aprimorado.
- [x] **Step 3:** Atualizar workflow `.github/workflows/sync-anp.yml` para baixar automaticamente a série de preços da ANP antes de fallback para seed.
- [x] **Step 4:** Regenerar `app/src/main/assets/anp_fuel_seed.json.gz` com base representativa completa.
- [x] **Step 5:** Criar/atualizar testes unitários e validar.

---

### Task 10: Integração Real de Postos no Mapa (`FuelMapCoordinator` + `FuelStationFinder`) e Cache Room

**Files:**
- Modify: `app/src/main/kotlin/app/hunter/app/fuel/FuelPriceRepository.kt`
- Modify: `app/src/main/kotlin/app/hunter/app/ui/FuelMapCoordinator.kt`
- Modify: `app/src/main/kotlin/app/hunter/app/ui/HunterMap.kt`
- Modify: `app/src/main/kotlin/app/hunter/app/ui/MapScreen.kt`
- Modify: `app/src/main/kotlin/app/hunter/app/HunterViewModel.kt`
- Modify: `app/src/test/kotlin/app/hunter/app/ui/FuelStationMapLayerTest.kt`

**Steps:**
- [x] **Step 1:** No `FuelPriceRepository`, implementar `enrichAndSavePlaces(places: List<Place>)` que mapeia postos do OSM com a hierarquia em 3 níveis (posto -> cidade -> estado) e insere no Room.
- [x] **Step 2:** No `FuelMapCoordinator`, injetar `FuelStationFinder`. Se o Room não retornar postos na região da bounding box, buscar postos via Overpass, enriquecer com ANP e salvar no Room.
- [x] **Step 3:** Expor `isSearchingOnline: StateFlow<Boolean>` no `FuelMapCoordinator` e `HunterViewModel`.
- [x] **Step 4:** Em `MapScreen.kt`, exibir pílula Novare indicando "Buscando postos próximos..." quando `isSearchingOnline` for verdadeiro.
- [x] **Step 5:** Validar com testes unitários em `FuelStationMapLayerTest.kt`.

---

### Task 11: Atualização Manual de Preços ANP no Perfil (`ProfileScreen.kt`)

**Files:**
- Modify: `app/src/main/kotlin/app/hunter/app/ui/ProfileScreen.kt`
- Modify: `app/src/main/kotlin/app/hunter/app/HunterViewModel.kt`
- Modify: `app/src/test/kotlin/app/hunter/app/ui/ProfileScreenTest.kt`

**Steps:**
- [x] **Step 1:** Adicionar método `syncAnpPricesNow()` no `HunterViewModel` e estado de status do sync.
- [x] **Step 2:** Adicionar seção "Preços de Combustíveis (ANP)" no `ProfileScreen.kt` com botão de atualizar e informações da base.
- [x] **Step 3:** Validar com testes unitários.

---

### Task 12: Robustez na Sugestão da Garagem (`AddFuelupDialog`)

**Files:**
- Modify: `app/src/main/kotlin/app/hunter/app/HunterViewModel.kt`
- Modify: `app/src/main/kotlin/app/hunter/app/ui/GarageScreen.kt`
- Modify: `app/src/test/kotlin/app/hunter/app/ui/GarageFuelPriceSuggestionTest.kt`

**Steps:**
- [x] **Step 1:** No `HunterViewModel.getFuelPriceReference()`, obter coordenadas com fallback para última localização conhecida do sistema (`LocationManager`) ou da última viagem gravada.
- [x] **Step 2:** Em `GarageScreen.kt`, atualizar o diálogo para consultar preço com tratamento resiliente a atraso no GPS.
- [x] **Step 3:** Validar com testes unitários.

---

### Task 13: Verificação Completa e Preparação da Release 0.7.2

**Files:**
- Modify: `CHANGELOG.md`
- Modify: `version.txt`

**Steps:**
- [x] **Step 1:** Atualizar `CHANGELOG.md` e `version.txt` para 0.7.2.
- [x] **Step 2:** Executar suíte de testes unitários.
- [x] **Step 3:** Fornecer resumo das correções e passos para publicação da release e testes no celular.
```
