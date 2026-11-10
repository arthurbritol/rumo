# Especificação de Design: Estilização Profissional das Telas e Ícones Font Awesome

**Data:** 10/11/2026  
**Status:** Pronto para Aprovação  
**Autor:** Antigravity & Arthur

---

## 1. Contexto e Motivação

O app Rumo (antigo Hunter Companion) evoluiu rapidamente pelas releases 0.1 a 0.5.2, implementando mapas offline, navegação curva a curva, odômetro, manutenção, conexão Bluetooth com o Tripper Pod, conta e sincronização na nuvem (Projeto 1), amigos e rolês em grupo (Projeto 2) e localização compartilhada com amigos (Projeto 3).

Apesar de a lógica e as regras estarem sólidas e testadas, as interfaces das telas construídas recentemente mantiveram um visual básico e funcional com forte aparência de "protótipo gerado por IA":
- Blocos cinzas idênticos empilhados verticalmente (`HCard` plano com borda cinza).
- Campos de texto padrão de formulário administrativo (`OutlinedTextField` cru).
- Ícones genéricos do Material (`Place`, `List`, `Build`, `Person`, `Settings`).
- Falta de hierarquia visual e profundidade tipográfica (sem números tabulares, sem unidades integradas, sem contrastes dinâmicos).

Esta rodada de estilização transforma todo o app em um **produto com acabamento profissional e funcional**, tomando como referência o design moderno do **Novare Finance** (`finance.novaresystems.com.br`) e a família de ícones do **Font Awesome**, mantendo intactas todas as regras de negócio, planos do Firebase e estabilidade de compilação.

---

## 2. Metas, Não-Metas e Anti-Metas

### 2.1 Metas (Goals)
1. **Nova Paleta e Sistema de Superfícies (Midnight Slate):**
   - Canvas / Fundo: `#0B111D` a `#0F172A` (azul-noite profundo, eliminando o preto/cinza chapado).
   - Superfície de Cartões / Nível 1: `#141C2B` com micro-borda de iluminação interna `#1E2D44`.
   - Superfície Elevada / Inputs / Nível 2: `#1A2436` com foco tátil.
   - Acento Principal: Laranja Âmbar vibrante `#FF7A1A` (com gradiente dinâmico `#FF7A1A` → `#EA580C`).
   - Acentos de Estado: Verde Tático `#10B981` (status "Na estrada", economia, ativo), Âmbar Alerta `#F59E0B` (manutenção, atenção), Vermelho Rubi `#EF4444` (crítico, erro, badge de notificação) e Azul Elétrico `#38BDF8` (informações de GPS/Pod).
2. **Biblioteca de Ícones Font Awesome em Vetores Nativos:**
   - Vetores Android XML em `app/src/main/res/drawable/` gerados com precisão a partir dos paths oficiais do Font Awesome 6:
     - `fa_motorcycle.xml` (Garagem, status de moto, amigo rodando)
     - `fa_map_location_dot.xml` (Aba Mapa)
     - `fa_route.xml` (Aba Viagens / Trajetos)
     - `fa_users.xml` (Aba Amigos / Rolê em Grupo)
     - `fa_gas_pump.xml` (Combustível / Autonomia)
     - `fa_wrench.xml` (Manutenção / Revisão)
     - `fa_gauge_high.xml` (Hodômetro / Painel)
     - `fa_bell.xml` e `fa_bell_dot.xml` (Notificações / Alertas)
     - `fa_user.xml` (Perfil / Piloto)
     - `fa_plus.xml` (Botão de ação rápida / Criar)
     - `fa_bluetooth.xml` (Tripper Pod / Conexão)
     - `fa_magnifying_glass.xml` (Busca de amigos)
3. **Componentes Primitivos Refinados (`Components.kt`):**
   - `HCard`: profundidade moderna, borda sutil `#1E2D44`, padding de 16-18dp, cantos arredondados de 20-22dp.
   - `SquircleIcon`: container quadrado arredondado (squircle) com fundo colorido de alto contraste (ex.: verde translúcido para moto ativa, âmbar para ferramentas, roxo para amigos) abrigando o ícone Font Awesome.
   - `HeroMetricCard`: cartão centralizado para grandes instrumentos (hodômetro, resumo financeiro/social do rolê), com unidade de medida estilizada, valor tabular grande e pill badge de tendência.
   - `SearchInputField`: campo de busca com altura reduzida (48dp), ícone Font Awesome embutido e visual compacto.
   - `RumoPrimaryButton` & `RumoSecondaryButton`: botões com acabamento tátil, curvatura elegante e suporte opcional a ícones Font Awesome.
4. **Barra de Navegação Inferior (`RumoBottomBar`):**
   - Barra estilizada em pílula flutuante / container com fundo `#0F172A`, ícones Font Awesome com proporção harmoniosa e indicador luminoso ativo com micro-glow.
5. **Redesign de Todas as Telas:**
   - **Garagem (`GarageScreen.kt`):** Hodômetro como instrumento digital hero, mini-cards de autonomia (com barra gráfica de combustível) e manutenção (com alerta de revisão) com squircles coloridos, chips de seleção modernos.
   - **Amigos & Rolês (`FriendsScreen.kt` e `RoleScreens.kt`):** Header com cumprimento e sino de notificações, busca moderna, cards de amigos com avatar + squircle e badge "Na estrada" pulsante, cards de rolê com foco nas métricas de orçamento e alcance.
   - **Perfil (`ProfileScreen.kt`):** Organização em seções agrupadas com cards limpos, formulário de moto estilizado e controle de privacidade para busca por nome.
   - **Viagens (`TripsScreen.kt`):** Lista de viagens com trajeto e data em visual tabular limpo.
   - **Pod (`PodScreen.kt`):** Status de conexão Bluetooth com badge visual vivo e ações táteis.

### 2.2 Não-Metas (Non-Goals)
- Não alterar regras de negócio, Firestore Security Rules ou chamadas de API.
- Não alterar a lógica de background do GPS, do Tripper Pod ou do `LiveLocationPublisher`.
- Não adicionar bibliotecas pesadas de terceiros (usar ícones em Android Vector Drawables nativos e Compose padrão).

### 2.3 Anti-Metas (Anti-Goals)
- Não quebrar o tema claro (Light theme continua funcionando através dos tokens do `Theme.kt`).
- Não degradar a performance de renderização do Compose (manter recomposições eficientes e estáveis).
- Não regredir nenhum teste automatizado existente em `:sync-core:test` e `:app:testDebugUnitTest`.

---

## 3. Abordagens Consideradas

### Abordagem 1: Design System Primitivo Centralizado + Aplicação por Tela (Escolhida)
* **Descrição:** Definir os tokens em `Theme.kt`, adicionar os vetores Font Awesome em `res/drawable`, refatorar e enriquecer os componentes base em `Components.kt`, e depois aplicar esses blocos nas telas individuais.
* **Prós:** Consistência perfeita em todo o app, facilidade de manutenção futura, zero duplicação de regras de estilo, respeito aos padrões de arquitetura do Android Compose.
* **Contras:** Requer construir e validar a camada base antes de editar as telas.
* **Decisão:** Escolhida e aprovada pelo dono.

### Abordagem 2: Reformulação Local Tela a Tela
* **Descrição:** Modificar cada tela isoladamente sem unificar componentes base.
* **Contras:** Código redundante, inconsistência em raios de bordas, cores e tamanhos de ícones. Rejeitada.

### Abordagem 3: Biblioteca Externa de Design System
* **Descrição:** Incluir pacotes externos pesados de UI.
* **Contras:** Aumento do tamanho do APK, perda de controle fino da identidade e risco de incompatibilidades com o Gradle/JVM do projeto. Rejeitada.

---

## 4. Detalhamento Técnico da Implementação

### 4.1 Vetores Font Awesome (`app/src/main/res/drawable/`)
Os ícones Font Awesome serão adicionados como `VectorDrawable` XML padronizados (viewport 24x24 ou 512x512 conforme o padrão FA, `android:fillColor="?attr/colorControlNormal"` ou `@color/white` com suporte a `tint` dinâmico pelo Compose `Icon` ou `painterResource`):
- `fa_motorcycle.xml`
- `fa_map_location_dot.xml`
- `fa_route.xml`
- `fa_users.xml`
- `fa_gas_pump.xml`
- `fa_wrench.xml`
- `fa_gauge_high.xml`
- `fa_bell.xml`
- `fa_user.xml`
- `fa_plus.xml`
- `fa_bluetooth.xml`
- `fa_magnifying_glass.xml`

### 4.2 Novo `Theme.kt` (Paleta Adaptável Claro / Escuro)

O tema do app adapta-se automaticamente à preferência do sistema Android (`isSystemInDarkTheme()`), com superfícies de alto contraste em ambos os modos inspiradas na elegância do Novare Finance:

#### Modo Escuro (Dark Mode - Midnight Navy)
```kotlin
val DarkBackground = Color(0xFF0B111D)
val DarkSurface = Color(0xFF141C2B)
val DarkSurfaceVariant = Color(0xFF1E2D44)
val DarkOutline = Color(0xFF24344D)
val DarkOnSurface = Color(0xFFF8FAFC)
val DarkOnSurfaceVariant = Color(0xFF94A3B8)
```

#### Modo Claro (Light Mode - Clean Slate)
```kotlin
val LightBackground = Color(0xFFF1F5F9)
val LightSurface = Color(0xFFFFFFFF)
val LightSurfaceVariant = Color(0xFFE2E8F0)
val LightOutline = Color(0xFFCBD5E1)
val LightOnSurface = Color(0xFF0F172A)
val LightOnSurfaceVariant = Color(0xFF64748B)
```

#### Cores de Estado e Acentos Compartilhados
- **Primária / Acento:** Laranja Âmbar (`#FF7A1A` no dark, `#EA580C` no light para contraste)
- **Sucesso / Na Estrada:** Verde Esmeralda (`#10B981` no dark, `#059669` no light)
- **Atenção / Revisão:** Âmbar Alerta (`#F59E0B` no dark, `#D97706` no light)
- **Erro / Notificações:** Vermelho Rubi (`#EF4444` no dark, `#DC2626` no light)
- **Info / Conexão Pod:** Azul Celeste (`#38BDF8` no dark, `#0284C7` no light)

Squircles utilizam fundos translúcidos com alpha ajustado (`color.copy(alpha = 0.15f)`) sobre as superfícies de ambos os modos, mantendo o ícone Font Awesome em destaque vibrante.

### 4.3 Novos Componentes em `Components.kt`
- `SquircleIcon(iconRes: Int, backgroundColor: Color, tintColor: Color, modifier: Modifier)`: Caixa arredondada de 44x44dp com cantos de 12dp e ícone centralizado de 22dp.
- `HeroMetricCard(title: String, unit: String, value: String, trendText: String?, trendPositive: Boolean, onAction: (() -> Unit)?)`: Cartão destacado de instrumento.
- `RumoSearchField(value: String, onValueChange: (String) -> Unit, placeholder: String, modifier: Modifier)`: Input compacto com `fa_magnifying_glass` integrado.
- `HCard`: Atualizado para cor `MaterialTheme.colorScheme.surface` (`MidnightSurface`) e borda sutil `MidnightOutline.copy(alpha = 0.6f)`.
- `StatusPill(text: String, active: Boolean)`: Pílula para "Na estrada" com indicador circular verde vivo.

### 4.4 Barra de Navegação Inferior (`MainActivity.kt` & `Tab.kt`)
- `Tab.kt` atualizado para expor os `iconResId` dos vetores Font Awesome (`R.drawable.fa_map_location_dot`, `R.drawable.fa_route`, `R.drawable.fa_motorcycle`, `R.drawable.fa_users`, `R.drawable.fa_bluetooth`).
- `NavigationBar` estilizada com container `#0F172A`, elevação sutil, borda superior suave, sem o pill cinza padrão do M3, usando indicador de traço luminoso na aba ativa.

### 4.5 Telas Específicas
1. **`GarageScreen.kt`:**
   - Topo: Nome da moto + status do Pod com `fa_bluetooth`, mais o botão de perfil à direita (mantendo o acesso direto ao Perfil a partir da Garagem).
   - Cockpit Fixo de Resumo:
     - Hero Card: `HeroMetricCard` do hodômetro total com unidade `km` em laranja e número grande.
     - Grid 2 colunas: Autonomia (`fa_gas_pump` em squircle laranja) e Revisão (`fa_wrench` em squircle âmbar).
   - Seções Alternadas (Chips estilizados modernos):
     - Mantemos os chips logo abaixo do cockpit: `Combustível`, `Manutenção`, `Mapas offline`.
     - A seção de **Mapas offline** é mantida intacta e ganha a mesma roupagem moderna (cards de regiões salvas com status de download em verde/azul).
2. **`FriendsScreen.kt` & `RoleScreens.kt`:**
   - Topo: Header com avatar do piloto (tocar nele também abre o Perfil via `onOpenProfile`), cumprimento e o sino `fa_bell` com badge vermelho quando houver convites pendentes (tocar no sino com pendências rola suavemente para a seção "Convites recebidos").
   - Campo de busca compacto `RumoSearchField`.
   - Cards de amigos: `SquircleIcon` verde para quem está "Na estrada" + status tabular (velocidade, tempo) e botão tático "Ver no mapa". `SquircleIcon` roxo/azul para amigos offline com km da semana.
   - Cards de rolê: `SquircleIcon` laranja + resumo de orçamento médio e alcance sugerido em badges limpos.
3. **`ProfileScreen.kt`:**
   - Agrupamento em cartões limpos para Dados da Moto, Identificação do Piloto e Privacidade na Busca por Nome.

### 4.6 Decisões Técnicas Consolidadas (Builder Check)
- **Acesso ao Perfil:** O `onOpenProfile` é acionado tanto pelo avatar no topo da `GarageScreen` quanto pelo avatar no header da `FriendsScreen`, garantindo acesso imediato em qualquer uma das abas principais.
- **Mapas Offline:** Preservados 100% como a terceira seção da Garagem, garantindo que o usuário nunca perca a capacidade de baixar e gerenciar mapas offline.
- **Sino de Notificações:** Funciona como âncora/atalho para a seção de convites recebidos na aba Amigos, evitando modais redundantes.

---

## 5. Verificação e Critérios de Aceite

1. **Compilação e Testes:**
   - `./gradlew.bat :sync-core:test :app:testDebugUnitTest :app:assembleRelease --console=plain -q` compila sem erros e todos os testes passam.
2. **Qualidade Visual:**
   - As 5 telas e a barra inferior refletem fielmente a identidade do Novare Finance com ícones Font Awesome.
   - Ausência de elementos cinzas crus ou inputs padrão de tutorial.
3. **Regressão Zero:**
   - Todas as ações existentes (adicionar abastecimento, registrar revisão, buscar amigo, mandar convite, aceitar rolê, ver amigo no mapa) continuam funcionando perfeitamente.
