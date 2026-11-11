# Integração ANP Semanal, Postos e Preços de Combustíveis Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Integrar a extração oficial semanal da ANP a partir da página de últimas semanas pesquisadas (filtrando estritamente Gasolina Comum, Gasolina Aditivada e Etanol para todo o Brasil), corrigir a descoberta e ordenação de postos por proximidade via OpenStreetMap/Photon, permitir ao usuário informar/editar os preços da bomba diretamente no app com persistência no Room, e exibir os marcadores com preços no mapa com botão de busca na área visível.

**Architecture:** 
1. **ETL Oficial ANP (`scripts/sync_anp_fuel.py`):** Rastreador e extrator nativo em Python (usando `urllib`, `zipfile` e `ElementTree`, zero dependências externas) que acessa a página oficial de últimas semanas pesquisadas (`https://www.gov.br/anp/pt-br/assuntos/precos-e-defesa-da-concorrencia/precos/levantamento-de-precos-de-combustiveis-ultimas-semanas-pesquisadas`), baixa o relatório de revendas mais recente (`revendas_lpc_*.xlsx`), filtra exclusivamente Gasolina Comum, Gasolina Aditivada e Etanol, e gera o payload compactado com 27 estados e centenas de cidades do Brasil.
2. **Descoberta Rápida e Filtrada (`FuelStationFinder.kt`):** Consulta ultra-rápida via Komoot Photon (< 1s) com fallback, filtrando estritamente pelo raio geográfico solicitado (`<= 8 km`) e ordenando do posto mais próximo para o mais distante.
3. **Persistência e Resolução de Preços (`FuelPriceRepository.kt` & Room):** Mapeia os postos descobertos para médias municipais/estaduais da ANP e suporta atualização manual pelo usuário (`updateStationPrices`), gravando no Room local.
4. **Interface e Mapa (`MapScreen.kt`, `FuelStationCard.kt`, `HunterMap.kt`):** Exibe no mapa os marcadores com o preço principal em pílula contrastante, botão flutuante "Buscar postos nesta área", card com termômetro de paridade Etanol x Gasolina (Regra dos 70%) e diálogo "Informar preço da bomba" para edição em tempo real.

**Tech Stack:** Python 3 (ETL nativo), Kotlin, Jetpack Compose, Room v4, MapLibre GL Android SDK, Komoot Photon API.

**Spec:** URL oficial da ANP (`/levantamento-de-precos-de-combustiveis-ultimas-semanas-pesquisadas`), filtrando apenas Gasolina Comum, Gasolina Aditivada e Etanol de todo o Brasil; Design System Novare; Custo zero de nuvem (Firebase Spark).

## Global Constraints

- 100% Spark Friendly: Zero escritas de postos públicos no Firestore ou Cloud Functions.
- Banco local Room (`AppDatabase` v4) com persistência em cache e tolerância estrita a offline.
- Padrão Novare Design System: Paleta Dark/Light com Midnight Navy (`#141C2B`), Accent Orange (`#FF7A1A`), tipografia limpa e ícones Font Awesome 6.
- Java SDK: `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"`.

## Review Focus

1. **Filtro Estrito dos Combustíveis Solicitados:** O pipeline da ANP e a interface devem processar e exibir exclusivamente Gasolina Comum, Gasolina Aditivada e Etanol, ignorando GLP, GNV e Diesel.
2. **Ordenação por Distância Real:** Postos retornados pela busca online devem ser filtrados por `Geo.distanceM <= radiusM` e ordenados do mais próximo ao mais distante, impedindo que postos a 30+ km apareçam na frente de postos a 1 km.
3. **Carregamento Automático da Base Oficial:** O script Python deve rodar e atualizar `anp_fuel_seed.json.gz` com os dados reais da semana mais recente de todas as 27 UFs e 400+ municípios.
4. **Informar Preço da Bomba na UI:** O motociclista deve ter um botão no card do posto para abrir o diálogo de edição de preços, salvando no Room e atualizando o mapa e o card imediatamente.
5. **Busca na Área Visível no Mapa:** O mapa deve dispor de botão "Buscar postos nesta área" quando a camada estiver ativa, permitindo ao usuário navegar livremente e acionar a busca sob demanda.

---

### Task 1: Atualizar `sync_anp_fuel.py` para extrair da página oficial da ANP com filtro de Gasolina e Etanol

**Files:**
- Modify: `scripts/sync_anp_fuel.py`
- Modify: `app/src/main/assets/anp_fuel_seed.json.gz`

- [ ] **Step 1: Implementar o rastreador e parser de XLSX nativo em `scripts/sync_anp_fuel.py`**

Configurar a URL base `https://www.gov.br/anp/pt-br/assuntos/precos-e-defesa-da-concorrencia/precos/levantamento-de-precos-de-combustiveis-ultimas-semanas-pesquisadas`, fazer o parse nativo do `revendas_lpc_*.xlsx` com `zipfile` + `ElementTree`, e filtrar estritamente `GASOLINA COMUM`, `GASOLINA ADITIVADA` e `ETANOL`.

- [ ] **Step 2: Executar o script para gerar o novo payload real da ANP**

Run: `python scripts/sync_anp_fuel.py --output app/src/main/assets/anp_fuel_seed.json.gz --auto-anp`
Expected: 27 estados, 400+ cidades e postos reais de novembro/2026 salvos no asset compactado.

- [ ] **Step 3: Commit da extração oficial da ANP**

```bash
git add scripts/sync_anp_fuel.py app/src/main/assets/anp_fuel_seed.json.gz
git commit -m "feat(fuel): integrar extrator oficial da pesquisa semanal da ANP com filtro de gasolina e etanol"
```

---

### Task 2: Otimizar `FuelStationFinder` com ordenação por proximidade e descarte de postos fora do raio

**Files:**
- Modify: `navigation/src/main/kotlin/app/hunter/nav/FuelStationFinder.kt`
- Modify: `navigation/src/main/kotlin/app/hunter/nav/PhotonGeocoder.kt`
- Test: `navigation/src/test/kotlin/app/hunter/nav/NavigationTest.kt`

- [ ] **Step 1: Escrever teste em `NavigationTest.kt` validando ordenação por proximidade**

Validar que postos fora do raio especificado são descartados e que a lista retornada vem ordenada crescentemente por distância até a coordenada do usuário.

- [ ] **Step 2: Executar teste e validar falha**

Run: `.\gradlew.bat :navigation:test --console=plain`

- [ ] **Step 3: Implementar cálculo de distância, filtro e ordenação no `FuelStationFinder.kt`**

Calcular `Geo.distanceM`, filtrar por `distance <= radiusM` e ordenar crescentemente antes de retornar `take(limit)`.

- [ ] **Step 4: Executar testes de navegação e validar sucesso**

Run: `.\gradlew.bat :navigation:test --console=plain`
Expected: PASS

- [ ] **Step 5: Commit do `FuelStationFinder`**

```bash
git add navigation/src/main/kotlin/app/hunter/nav/FuelStationFinder.kt navigation/src/main/kotlin/app/hunter/nav/PhotonGeocoder.kt navigation/src/test/kotlin/app/hunter/nav/NavigationTest.kt
git commit -m "fix(nav): ordenar postos por proximidade e filtrar por raio no FuelStationFinder"
```

---

### Task 3: Suportar atualização manual de preços no Room (`AnpFuelDao` & `FuelPriceRepository`)

**Files:**
- Modify: `app/src/main/kotlin/app/hunter/app/data/anp/AnpFuelDao.kt`
- Modify: `app/src/main/kotlin/app/hunter/app/fuel/FuelPriceRepository.kt`
- Test: `app/src/test/kotlin/app/hunter/app/fuel/FuelPriceRepositoryTest.kt`

- [ ] **Step 1: Escrever teste unitário para `updateStationPrices` no `FuelPriceRepositoryTest.kt`**

- [ ] **Step 2: Implementar query no `AnpFuelDao` e método no `FuelPriceRepository`**

Suportar atualização pontual dos preços de Gasolina Comum, Gasolina Aditivada e Etanol para qualquer posto pelo seu `id`.

- [ ] **Step 3: Executar testes e validar sucesso**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "app.hunter.app.fuel.FuelPriceRepositoryTest" --console=plain`
Expected: PASS

- [ ] **Step 4: Commit da persistência de preços manuais**

```bash
git add app/src/main/kotlin/app/hunter/app/data/anp/AnpFuelDao.kt app/src/main/kotlin/app/hunter/app/fuel/FuelPriceRepository.kt app/src/test/kotlin/app/hunter/app/fuel/FuelPriceRepositoryTest.kt
git commit -m "feat(fuel): permitir edicao e atualizacao manual de precos no Room"
```

---

### Task 4: Criar Diálogo "Informar Preço da Bomba" e ajustar `FuelStationCard.kt`

**Files:**
- Modify: `app/src/main/kotlin/app/hunter/app/ui/FuelStationCard.kt`
- Modify: `app/src/main/kotlin/app/hunter/app/HunterViewModel.kt`
- Modify: `app/src/main/kotlin/app/hunter/app/ui/MapScreen.kt`

- [ ] **Step 1: Ajustar `FuelStationCard.kt` para focar nos 3 combustíveis solicitados (Gasolina Comum, Gasolina Aditivada, Etanol)**

Layout limpo com cards dos 3 combustíveis e o termômetro de paridade Etanol x Gasolina. Adicionar botão Novare "Informar preços da bomba" com ícone `fa_gas_pump`.

- [ ] **Step 2: Criar o diálogo composable `EditFuelPricesDialog` com campos numéricos e formatação de moeda**

- [ ] **Step 3: Adicionar método `updateStationPrices` no `HunterViewModel.kt` e conectar no `MapScreen.kt`**

Ao salvar os preços no diálogo, atualiza a entidade no Room, atualiza o posto selecionado e emite a atualização na lista do mapa.

- [ ] **Step 4: Executar testes unitários**

Run: `.\gradlew.bat :app:testDebugUnitTest --console=plain`
Expected: PASS

- [ ] **Step 5: Commit do diálogo de preços**

```bash
git add app/src/main/kotlin/app/hunter/app/ui/FuelStationCard.kt app/src/main/kotlin/app/hunter/app/HunterViewModel.kt app/src/main/kotlin/app/hunter/app/ui/MapScreen.kt
git commit -m "feat(fuel): adicionar dialogo para informar precos da bomba no card do posto"
```

---

### Task 5: Adicionar Botão "Buscar postos nesta área" e inicialização com GPS no `MapScreen.kt`

**Files:**
- Modify: `app/src/main/kotlin/app/hunter/app/ui/MapScreen.kt`
- Modify: `app/src/main/kotlin/app/hunter/app/ui/FuelMapCoordinator.kt`
- Modify: `app/src/main/kotlin/app/hunter/app/ui/HunterMap.kt`
- Test: `app/src/test/kotlin/app/hunter/app/ui/FuelStationMapLayerTest.kt`

- [ ] **Step 1: Ajustar `FuelMapCoordinator.kt` para priorizar a posição GPS real e suportar busca manual forçada**

- [ ] **Step 2: Adicionar botão flutuante Novare "Buscar postos nesta área" no topo do mapa em `MapScreen.kt`**

- [ ] **Step 3: Conectar clique do botão à função `vm.searchFuelStationsInArea()` com animação de loading suave**

- [ ] **Step 4: Executar testes de camada do mapa**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "app.hunter.app.ui.FuelStationMapLayerTest" --console=plain`
Expected: PASS

- [ ] **Step 5: Commit da busca na área visível**

```bash
git add app/src/main/kotlin/app/hunter/app/ui/MapScreen.kt app/src/main/kotlin/app/hunter/app/ui/FuelMapCoordinator.kt app/src/main/kotlin/app/hunter/app/ui/HunterMap.kt app/src/test/kotlin/app/hunter/app/ui/FuelStationMapLayerTest.kt
git commit -m "feat(map): adicionar botao buscar postos nesta area e sincronizar com GPS"
```

---

### Task 6: Verificação Completa e Compilação da Release

**Files:**
- Verification test suite
- Assemble Release APK

- [ ] **Step 1: Rodar a suíte completa de testes de todos os módulos**

Run: `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat :core-model:test :navigation:test :sync-core:test :app:testDebugUnitTest --console=plain -q`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Gerar APK de release para validação de compilação**

Run: `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat :app:assembleRelease --console=plain -q`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Documentar no CHANGELOG.md e preparar para push**

```bash
git add CHANGELOG.md
git commit -m "docs: registrar integracao ANP semanal, informe de precos e busca no mapa"
```
