# Estilização Profissional das Telas e Ícones Font Awesome - Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Transformar todo o visual do app Rumo em uma experiência profissional, refinada e funcional inspirada no Novare Finance (`finance.novaresystems.com.br`) e na família de ícones oficiais Font Awesome, com suporte adaptável claro e escuro.

**Architecture:** Implementação baseada em Design System primitivo centralizado: criação de vetores XML nativos do Font Awesome em `res/drawable`, tokens semânticos adaptáveis em `Theme.kt`, novos blocos fundamentais (`HCard`, `SquircleIcon`, `HeroMetricCard`, `RumoSearchField`, `RumoBottomBar`) em `Components.kt`, e aplicação consistente em todas as 5 abas (`GarageScreen`, `FriendsScreen`, `RoleScreens`, `ProfileScreen`, `TripsScreen`, `PodScreen`), sem alterar regras de negócio ou chamadas ao Firebase.

**Tech Stack:** Kotlin, Jetpack Compose (Material 3), Android Vector Drawables nativos (Font Awesome 6), SharedPreferences, Room, Firestore.

**Spec:** [docs/superpowers/specs/2026-11-10-estilizacao-telas-design.md](file:///c:/Users/arthur.lucio/Documents/Tripper%20Pod/docs/superpowers/specs/2026-11-10-estilizacao-telas-design.md)

## Global Constraints

- Compilar com JAVA_HOME configurado em `C:\Program Files\Android\Android Studio\jbr`.
- Nenhuma dependência externa pesada de terceiros adicionada ao `build.gradle.kts`.
- Textos e mensagens estritamente em `pt-BR`.
- Suporte adaptável total a tema escuro (`Midnight Navy`) e claro (`Clean Slate`).
- Preservar 100% da lógica e chamadas de Firebase, Room, Ble e GPS.
- Todos os testes de `:sync-core:test` e `:app:testDebugUnitTest` devem passar.

## Review Focus

1. **Mapas Offline na Garagem:** A reestruturação da Garagem com cockpit de hodômetro e mini-cards de resumo não pode ocultar nem quebrar a seção de download e gerenciamento de regiões offline.
2. **Navegação para o Perfil:** O `onOpenProfile` deve continuar funcionando a partir da Garagem e passar a funcionar também a partir do avatar na tela de Amigos.
3. **Compatibilidade dos Ícones Font Awesome:** Os vetores XML do Font Awesome devem renderizar perfeitamente em todas as densidades (DPI) sem distorção e aceitar `tint` dinâmico do Compose.
4. **Legibilidade e Contraste em Tema Claro:** Todos os textos, números tabulares e squircles com fundos translúcidos devem manter alto contraste (WCAG AA) quando o sistema estiver em tema claro.
5. **Comportamento em Telas Pequenas:** O cockpit da Garagem e os cards de rolê com 2 colunas devem se ajustar sem quebra de linha ou corte de texto em dispositivos menores.

---

### Task 1: Biblioteca de Vetores Font Awesome em XML (`res/drawable/fa_*.xml`)

**Files:**
- Create:
  - `app/src/main/res/drawable/fa_motorcycle.xml`
  - `app/src/main/res/drawable/fa_map_location_dot.xml`
  - `app/src/main/res/drawable/fa_route.xml`
  - `app/src/main/res/drawable/fa_users.xml`
  - `app/src/main/res/drawable/fa_gas_pump.xml`
  - `app/src/main/res/drawable/fa_wrench.xml`
  - `app/src/main/res/drawable/fa_gauge_high.xml`
  - `app/src/main/res/drawable/fa_bell.xml`
  - `app/src/main/res/drawable/fa_user.xml`
  - `app/src/main/res/drawable/fa_plus.xml`
  - `app/src/main/res/drawable/fa_bluetooth.xml`
  - `app/src/main/res/drawable/fa_magnifying_glass.xml`
- Test: `app/src/test/kotlin/app/hunter/app/ui/FontAwesomeDrawablesTest.kt`

**Interfaces:**
- Produces: Identificadores `R.drawable.fa_*` consumíveis por `painterResource(R.drawable.fa_*)` e `Icon`.

- [ ] **Step 1: Escrever teste que valida o carregamento de todos os recursos vetoriais Font Awesome**

```kotlin
package app.hunter.app.ui

import app.hunter.app.R
import org.junit.Assert.assertNotEquals
import org.junit.Test

class FontAwesomeDrawablesTest {
    @Test
    fun allFontAwesomeDrawablesExist() {
        val drawables = listOf(
            R.drawable.fa_motorcycle,
            R.drawable.fa_map_location_dot,
            R.drawable.fa_route,
            R.drawable.fa_users,
            R.drawable.fa_gas_pump,
            R.drawable.fa_wrench,
            R.drawable.fa_gauge_high,
            R.drawable.fa_bell,
            R.drawable.fa_user,
            R.drawable.fa_plus,
            R.drawable.fa_bluetooth,
            R.drawable.fa_magnifying_glass,
        )
        drawables.forEach { id ->
            assertNotEquals(0, id)
        }
    }
}
```

- [ ] **Step 2: Executar o teste para verificar que falha (símbolos inexistentes)**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "app.hunter.app.ui.FontAwesomeDrawablesTest" --console=plain -q`
Expected: FAIL com símbolos não encontrados em `R.drawable`.

- [ ] **Step 3: Criar os 12 vetores XML em `app/src/main/res/drawable/fa_*.xml`**

Gerar os arquivos vetoriais Android (`vector android:width="24dp" android:height="24dp" android:viewportWidth="512" android:viewportHeight="512"` com pathData oficial do Font Awesome 6).

- [ ] **Step 4: Executar o teste para verificar que passa**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "app.hunter.app.ui.FontAwesomeDrawablesTest" --console=plain -q`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git commit -m "feat(ui): adicionar biblioteca de icones vetoriais Font Awesome"
```

---

### Task 2: Tokens e Paleta Adaptável (Dark & Light) em `Theme.kt`

**Files:**
- Modify: `app/src/main/kotlin/app/hunter/app/ui/Theme.kt`
- Test: `app/src/test/kotlin/app/hunter/app/ui/ThemeTokensTest.kt`

**Interfaces:**
- Consumes: M3 `darkColorScheme`, `lightColorScheme`, `LocalDark`.
- Produces: Novas cores semânticas (`NovareAccent`, `NovareSuccess`, `NovareWarning`, `NovareError`, `NovareInfo`, superfícies `DarkSurface`, `LightSurface`, etc.).

- [ ] **Step 1: Escrever teste para os tokens de cor adaptáveis**

```kotlin
package app.hunter.app.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ThemeTokensTest {
    @Test
    fun novareTokensHaveExpectedValues() {
        assertEquals(Color(0xFFFF7A1A), NovareAccent)
        assertEquals(Color(0xFF10B981), NovareSuccess)
        assertEquals(Color(0xFFF59E0B), NovareWarning)
        assertEquals(Color(0xFFEF4444), NovareError)
        assertEquals(Color(0xFF38BDF8), NovareInfo)
    }

    @Test
    fun darkAndLightSurfacesAreDistinct() {
        assertNotEquals(DarkMidnightSurface, LightCleanSurface)
        assertNotEquals(DarkMidnightBackground, LightCleanBackground)
    }
}
```

- [ ] **Step 2: Executar teste para verificar que falha**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "app.hunter.app.ui.ThemeTokensTest" --console=plain -q`
Expected: FAIL.

- [ ] **Step 3: Implementar tokens e esquemas de cores adaptáveis em `Theme.kt`**

Atualizar `Theme.kt` para incorporar `DarkMidnightBackground` (`#0B111D`), `DarkMidnightSurface` (`#141C2B`), `LightCleanBackground` (`#F1F5F9`), `LightCleanSurface` (`#FFFFFF`), os acentos Novare e ajustar o `Dark` e `Light` colorScheme.

- [ ] **Step 4: Executar teste para verificar que passa**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "app.hunter.app.ui.ThemeTokensTest" --console=plain -q`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git commit -m "feat(ui): implementar paleta adaptavel Novare em Theme.kt"
```

---

### Task 3: Componentes Primitivos Centrais em `Components.kt`

**Files:**
- Modify: `app/src/main/kotlin/app/hunter/app/ui/Components.kt`
- Test: `app/src/test/kotlin/app/hunter/app/ui/ComponentsStructureTest.kt`

**Interfaces:**
- Consumes: Tokens de `Theme.kt` e drawables de `R.drawable.fa_*`.
- Produces: `SquircleIcon`, `HeroMetricCard`, `RumoSearchField`, `RumoBadge`, `HCard` aprimorado com micro-borda, `RumoPrimaryButton` e `RumoSecondaryButton`.

- [ ] **Step 1: Escrever teste para funções e propriedades dos novos componentes**

```kotlin
package app.hunter.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ComponentsStructureTest {
    @Test
    fun squircleDimensionsAreStandardized() {
        assertEquals(44, SQUIRCLE_DEFAULT_SIZE_DP)
        assertEquals(12, SQUIRCLE_DEFAULT_CORNER_DP)
    }
}
```

- [ ] **Step 2: Executar teste para verificar que falha**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "app.hunter.app.ui.ComponentsStructureTest" --console=plain -q`
Expected: FAIL.

- [ ] **Step 3: Implementar os componentes em `Components.kt`**

- `HCard`: fundo `surface`, borda `outline.copy(alpha = 0.5f)`, shape `RoundedCornerShape(22.dp)`.
- `SquircleIcon`: `Box` de 44x44dp com shape `RoundedCornerShape(12.dp)`, fundo colorido e ícone de 22dp com tint.
- `HeroMetricCard`: cartão centralizado para instrumentos (unidade, valor tabular, pill de tendência).
- `RumoSearchField`: campo de 48dp de altura com cantos de 14dp, background `surfaceVariant`, `fa_magnifying_glass` integrado.
- `RumoPrimaryButton`: botão com 54dp de altura, gradiente `#FF7A1A` → `#EA580C`, cantos de 16dp.
- `RumoSecondaryButton`: botão outline refinado com cantos de 16dp.

- [ ] **Step 4: Executar testes de componentes e app**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "app.hunter.app.ui.ComponentsStructureTest" --console=plain -q`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git commit -m "feat(ui): implementar componentes primitivos refinados em Components.kt"
```

---

### Task 4: Barra de Navegação Inferior com Font Awesome (`Tab.kt` & `MainActivity.kt`)

**Files:**
- Modify: `app/src/main/kotlin/app/hunter/app/ui/Tab.kt`
- Modify: `app/src/main/kotlin/app/hunter/app/MainActivity.kt:302-332`
- Test: `app/src/test/kotlin/app/hunter/app/ui/TabIconsTest.kt`

**Interfaces:**
- Consumes: `R.drawable.fa_*` e tokens de `Theme.kt`.
- Produces: Barra inferior flutuante sem pill cinza, com ícones Font Awesome e indicador ativo luminoso.

- [ ] **Step 1: Escrever teste para os ícones da enum `Tab`**

```kotlin
package app.hunter.app.ui

import app.hunter.app.R
import org.junit.Assert.assertEquals
import org.junit.Test

class TabIconsTest {
    @Test
    fun tabsUseFontAwesomeIcons() {
        assertEquals(R.drawable.fa_map_location_dot, Tab.Map.iconResId)
        assertEquals(R.drawable.fa_route, Tab.Trips.iconResId)
        assertEquals(R.drawable.fa_motorcycle, Tab.Garage.iconResId)
        assertEquals(R.drawable.fa_users, Tab.Friends.iconResId)
        assertEquals(R.drawable.fa_bluetooth, Tab.Pod.iconResId)
    }
}
```

- [ ] **Step 2: Executar teste para verificar que falha**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "app.hunter.app.ui.TabIconsTest" --console=plain -q`
Expected: FAIL (`iconResId` não existe em `Tab`).

- [ ] **Step 3: Atualizar `Tab.kt` e a `NavigationBar` em `MainActivity.kt`**

- `Tab`: incluir propriedade `iconResId: Int`.
- `MainActivity.kt`: renderizar os ícones via `painterResource(t.iconResId)`, container `#0F172A`, indicador de traço luminoso ativo sem pill retangular cinza, badge vermelho discreto para pendências.

- [ ] **Step 4: Executar teste para verificar que passa**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "app.hunter.app.ui.TabIconsTest" --console=plain -q`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git commit -m "feat(ui): modernizar barra de navegacao inferior com icones Font Awesome"
```

---

### Task 5: Redesign da Tela de Garagem (`GarageScreen.kt`)

**Files:**
- Modify: `app/src/main/kotlin/app/hunter/app/ui/GarageScreen.kt`

**Interfaces:**
- Consumes: `HeroMetricCard`, `SquircleIcon`, `R.drawable.fa_*`, `onOpenProfile`.
- Preserves: `vm.odometerKm`, `vm.attention`, `FuelSection`, `MaintenanceSection`, `OfflineSection`.

- [ ] **Step 1: Modificar `GarageScreen.kt` para a nova estrutura visual**

1. Topo: Cabeçalho com nome do modelo da moto, badge do Pod com `fa_bluetooth`, e botão de avatar para abrir o Perfil (`onOpenProfile`).
2. Cockpit Fixo Hero:
   - `HeroMetricCard` do hodômetro com unidade `km` em destaque e valor tabular grande.
   - Grid 2 colunas: Autonomia (`fa_gas_pump` em squircle laranja com barra gráfica de tanque) e Revisão (`fa_wrench` em squircle âmbar com aviso de quilometragem restante).
3. Seções Alternadas (Chips):
   - Chips refinados estilo pílula moderna: `Combustível`, `Manutenção`, `Mapas offline`.
   - Seção de Mapas Offline preservada 100% e reestilizada com cards modernos e indicadores claros de download.

- [ ] **Step 2: Compilar e rodar testes do app**

Run: `./gradlew.bat :app:testDebugUnitTest --console=plain -q`
Expected: BUILD SUCCESSFUL, todos os testes passam.

- [ ] **Step 3: Commit**

```bash
git commit -m "feat(ui): redesenhar tela de garagem com cockpit digital e squircles Font Awesome"
```

---

### Task 6: Redesign da Aba Amigos & Rolês (`FriendsScreen.kt` & `RoleScreens.kt`)

**Files:**
- Modify: `app/src/main/kotlin/app/hunter/app/ui/FriendsScreen.kt`
- Modify: `app/src/main/kotlin/app/hunter/app/ui/RoleScreens.kt`
- Modify: `app/src/main/kotlin/app/hunter/app/MainActivity.kt` (repassar `onOpenProfile` para `FriendsRoute`)

**Interfaces:**
- Consumes: `RumoSearchField`, `SquircleIcon`, `R.drawable.fa_*`, `onOpenProfile`.
- Preserves: `SocialUi`, `SocialViewModel`, todas as ações sociais e de localização compartilhada.

- [ ] **Step 1: Conectar `onOpenProfile` em `FriendsRoute` e `FriendsScreen`**

Passar `onOpenProfile` a partir de `MainActivity.kt` para que o toque no avatar do header de Amigos também abra o Perfil.

- [ ] **Step 2: Redesenhar o topo, busca e lista de amigos em `FriendsScreen.kt`**

1. Header: "Fala, [Nome]", avatar circular com borda e sino `fa_bell` (com badge de pendências e clique que rola para os convites recebidos).
2. Busca: `RumoSearchField` compacto com lupa Font Awesome.
3. Cards de Amigos:
   - Amigo na estrada: `SquircleIcon` verde vibrante com `fa_motorcycle` + status ao vivo (velocidade, tempo decorrido) + botão tático "Ver no mapa".
   - Amigo offline: `SquircleIcon` com `fa_user` + km rodados na semana.
4. Cards de Rolê:
   - `SquircleIcon` laranja com `fa_users` + mini-cards de orçamento médio e alcance sugerido.

- [ ] **Step 3: Redesenhar formulários e subtelas em `RoleScreens.kt`**

- `CreateRoleScreen` e `RoleDetailScreen` estilizadas com inputs compactos, cards de participantes com squircles e métricas financeiras tabulares.

- [ ] **Step 4: Compilar e rodar testes de unidade**

Run: `./gradlew.bat :app:testDebugUnitTest --console=plain -q`
Expected: BUILD SUCCESSFUL, todos os testes passam.

- [ ] **Step 5: Commit**

```bash
git commit -m "feat(ui): redesenhar aba amigos e roles com visual Novare e icones Font Awesome"
```

---

### Task 7: Redesign das Telas de Perfil, Viagens e Pod (`ProfileScreen.kt`, `TripsScreen.kt`, `PodScreen.kt`)

**Files:**
- Modify: `app/src/main/kotlin/app/hunter/app/ui/ProfileScreen.kt`
- Modify: `app/src/main/kotlin/app/hunter/app/ui/TripsScreen.kt`
- Modify: `app/src/main/kotlin/app/hunter/app/ui/PodScreen.kt`

**Interfaces:**
- Consumes: Novos componentes de `Components.kt` e tokens de `Theme.kt`.

- [ ] **Step 1: Redesenhar `ProfileScreen.kt`**

Organizar as seções em cartões limpos (`HCard`): Dados da Moto (marca, modelo, ano), Identificação do Piloto (nome, e-mail), Privacidade Social (switch de busca por nome) e Ações de Conta (sair, apagar conta, rever intro).

- [ ] **Step 2: Redesenhar `TripsScreen.kt`**

Cards de histórico de viagens com `SquircleIcon` de rota (`fa_route`), data/hora em fonte muted e métricas de distância, velocidade média e duração em layout tabular alinhado.

- [ ] **Step 3: Redesenhar `PodScreen.kt`**

Card de cockpit do Tripper Pod com indicador visual de status Bluetooth (`fa_bluetooth`), bateria (se suportada) e botões táteis de conexão e calibração.

- [ ] **Step 4: Compilar e rodar todos os testes de unidade**

Run: `./gradlew.bat :app:testDebugUnitTest :sync-core:test --console=plain -q`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git commit -m "feat(ui): unificar estilo profissional em Perfil, Viagens e Pod"
```

---

### Task 8: Verificação Completa, Build de Release e Documentação

**Files:**
- Modify: `CHANGELOG.md`
- Modify: `docs/ARQUITETURA.md`
- Remove: arquivos temporários de mockup em `.superpowers/brainstorm/`

- [ ] **Step 1: Executar suite completa de testes**

Run: `./gradlew.bat :sync-core:test :app:testDebugUnitTest --console=plain -q`
Expected: PASS com 0 falhas.

- [ ] **Step 2: Executar build de release do Android**

Run: `./gradlew.bat :app:assembleRelease --console=plain -q`
Expected: BUILD SUCCESSFUL gerando o APK de release sem warnings impeditivos.

- [ ] **Step 3: Atualizar `CHANGELOG.md` e `docs/ARQUITETURA.md`**

Registrar a rodada de estilização completa (Design System Novare Midnight/Clean Slate + Ícones Font Awesome 6 em todas as telas).

- [ ] **Step 4: Finalizar servidor do Visual Companion**

Encerrar o servidor do Visual Companion e limpar arquivos temporários.

- [ ] **Step 5: Commit**

```bash
git commit -m "docs(ui): documentar novo design system Novare e icones Font Awesome"
```
