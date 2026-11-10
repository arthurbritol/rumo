# Roteiro de testes manuais: localização compartilhada com amigos

Para executar com **duas contas em dois aparelhos** (ou dois emuladores, ou um aparelho e um emulador), com o `google-services.json` no build e as regras de segurança do Firestore publicadas (veja [FIREBASE.md](FIREBASE.md#localização-compartilhada-com-amigos-versão-052)).

Os testes automatizados (unitários JVM e testes de segurança no emulador Firestore) cobrem a lógica de negócio, modelos e regras de acesso. **Este roteiro valida a experiência de ponta a ponta no aparelho físico**: ciclo de vida Bluetooth com o Tripper Pod, alternância de telas Compose, renderização dos marcadores no MapLibre e integridade dos dados no Firestore.

---

## Preparação do ambiente

1. **Aparelhos e Contas**:
   - **Aparelho A**: Usuário **Ana**, com moto cadastrada e amizade aceita com Beto.
   - **Aparelho B**: Usuário **Beto**, com moto cadastrada e amizade aceita com Ana.
2. **Conexão Bluetooth (Pod)**:
   - Aparelho A conectado ao Tripper Pod físico da moto (`RE_DISP`), ou simulando conexão BLE (`PodPhase.CONNECTED`) via teste instrumentado / ambiente de depuração.
3. **Console do Firebase**:
   - Console aberto em **Firestore Database** para acompanhar em tempo real as coleções:
     - `permissoesLocalizacao/{de_para}`
     - `localizacoes/{uid}`
4. **Logcat (opcional para depuração)**:
   - Filtro: `adb logcat -s Rumo/social Rumo/pod Rumo/map`

---

## Cenário 1: Permissão individual amigo a amigo

**Objetivo:** Garantir que o compartilhamento seja estritamente individual e controlado por interruptor na aba Amigos.

- [ ] 1.1 No Aparelho A (Ana), abra a aba **Amigos**.
- [ ] 1.2 Localize o card do amigo **Beto**. Verifique que há o interruptor **"Compartilhar localização"** (inicialmente desativado).
- [ ] 1.3 Ative o interruptor no card de Beto.
- [ ] 1.4 No console do Firebase, confirme que foi criado o documento em `permissoesLocalizacao/{uidAna}_{uidBeto}` com os campos `de: uidAna`, `para: uidBeto` e `criadoEm`.
- [ ] 1.5 No Aparelho B (Beto), confira que o interruptor no card de Ana **permanece desativado** (a permissão é unidirecional; Ana autorizou Beto, mas Beto não autorizou Ana).
- [ ] 1.6 No Aparelho A, desative o interruptor de Beto: confirme que o documento `permissoesLocalizacao/{uidAna}_{uidBeto}` é excluído do Firestore.
- [ ] 1.7 Ative novamente o interruptor de Beto no Aparelho A para prosseguir para os próximos cenários.

---

## Cenário 2: Moto desconectada (sem compartilhamento)

**Objetivo:** Confirmar que com a moto desligada/desconectada nenhuma coordenada é enviada ou exibida, mesmo com permissão ativada.

- [ ] 2.1 Garanta que o Tripper Pod no Aparelho A (Ana) esteja **desconectado** (`PodPhase.DISCONNECTED` ou `SEARCHING`).
- [ ] 2.2 No console do Firebase, verifique que **NÃO** existe o documento `localizacoes/{uidAna}` (ou se existia, está deletado).
- [ ] 2.3 No Aparelho B (Beto):
  - [ ] Na aba **Amigos**, o card de Ana mostra apenas o modelo da moto e os km da semana. **Não** deve aparecer o badge "Na estrada" nem o botão "Ver no mapa".
  - [ ] Na aba **Mapa**, **nenhum** marcador de moto referente a Ana é desenhado no mapa.

---

## Cenário 3: Moto conectada (Ana entra na estrada)

**Objetivo:** Validar o envio reativo de localização assim que o Tripper Pod conecta e a renderização imediata para o amigo autorizado.

- [ ] 3.1 No Aparelho A (Ana), ligue a ignição da moto e conecte o Tripper Pod (o app transiciona para `PodPhase.CONNECTED`).
- [ ] 3.2 Movimente o aparelho ou aguarde a primeira leitura de GPS da `AndroidLocationSource`.
- [ ] 3.3 No console do Firebase, confirme que o documento `localizacoes/{uidAna}` foi criado contendo:
  - `latitude`: valor numérico válido (-90..90)
  - `longitude`: valor numérico válido (-180..180)
  - `bearing`: ângulo de direção da moto (ou nulo se parado)
  - `speedKmh`: velocidade atual (ou nulo)
  - `updatedAtMs`: timestamp recente em milissegundos
- [ ] 3.4 No Aparelho B (Beto), na aba **Amigos**:
  - [ ] O card de Ana passa a exibir o badge destacado com ponto indicador e o texto **"Na estrada"**.
  - [ ] Ao lado do badge, aparece o botão **"Ver no mapa"**.
- [ ] 3.5 No Aparelho B (Beto), na aba **Mapa**:
  - [ ] Surge no mapa o marcador com ícone de moto apontando na direção de deslocamento (`bearing`).
  - [ ] O marcador exibe o nome de Ana e o indicador de tempo relativo (ex: **"Ana • agora"** ou **"Ana • há 1 min"**).

---

## Cenário 4: Atalho "Ver no mapa" e centralização de câmera

**Objetivo:** Testar a navegação fluida entre a lista de amigos e o mapa com foco no parceiro.

- [ ] 4.1 No Aparelho B (Beto), estando na aba **Amigos**, toque no botão **"Ver no mapa"** no card de Ana.
- [ ] 4.2 O app deve alternar automaticamente para a aba **Mapa**.
- [ ] 4.3 A câmera do MapLibre anima e centraliza suavemente na coordenada atual da moto de Ana.
- [ ] 4.4 O marcador de moto de Ana deve estar visível e centralizado na tela.

---

## Cenário 5: Desconexão da moto (corte imediato)

**Objetivo:** Garantir que desligar a moto ou desconectar o Bluetooth interrompe a transmissão e remove o marcador na hora.

- [ ] 5.1 No Aparelho A (Ana), desligue a ignição da moto ou desconecte o Bluetooth do Tripper Pod.
- [ ] 5.2 No console do Firebase, confirme que o documento `localizacoes/{uidAna}` foi **deletado imediatamente** pelo `LiveLocationPublisher`.
- [ ] 5.3 No Aparelho B (Beto):
  - [ ] Na aba **Mapa**, o marcador de moto de Ana desaparece imediatamente da tela.
  - [ ] Na aba **Amigos**, o badge "Na estrada" e o botão "Ver no mapa" no card de Ana desaparecem.

---

## Cenário 6: Revogação da permissão com moto conectada

**Objetivo:** Garantir que revogar a permissão corta o acesso do amigo mesmo enquanto a moto continua conectada na estrada.

- [ ] 6.1 No Aparelho A (Ana), reconecte o Tripper Pod para voltar ao status `PodPhase.CONNECTED`.
- [ ] 6.2 No Aparelho B (Beto), confirme que o marcador de moto de Ana reapareceu no mapa.
- [ ] 6.3 No Aparelho A (Ana), vá na aba **Amigos** e **desative o interruptor "Compartilhar localização"** no card de Beto.
- [ ] 6.4 No console do Firebase, confirme que o documento `permissoesLocalizacao/{uidAna}_{uidBeto}` foi apagado.
- [ ] 6.5 Como Beto era o único amigo autorizado de Ana, o `LiveLocationPublisher` em A detecta que a lista de amigos autorizados ficou vazia e remove `localizacoes/{uidAna}`.
- [ ] 6.6 No Aparelho B (Beto), a leitura de `localizacoes/{uidAna}` é imediatamente bloqueada pelas regras de segurança do Firestore:
  - [ ] O marcador de Ana no mapa de Beto desaparece na hora.
  - [ ] O badge "Na estrada" no card de Ana na aba Amigos desaparece.

---

## Cenário 7: Desfazer amizade e Bloqueio de usuário

**Objetivo:** Garantir que o encerramento do vínculo de amizade invalide o compartilhamento nas regras do Firestore.

- [ ] 7.1 Restabeleça o compartilhamento: Ana autoriza Beto e ambos permanecem com amizade ativa.
- [ ] 7.2 No Aparelho A (Ana), toque no menu de opções (três pontos) no card de Beto > **Desfazer amizade**.
- [ ] 7.3 Verifique que a amizade é removida em ambos os aparelhos.
- [ ] 7.4 Tente consultar ou manter listener no Aparelho B: as regras de segurança do Firestore recusam o acesso a `localizacoes/{uidAna}` (pois `amigos(uidAna, uidBeto)` retorna falso). O marcador e badge somem definitivamente.
- [ ] 7.5 *(Opcional)* Refaça a amizade e teste o **Bloquear**: ao bloquear, convites, amizades e permissões são anulados, impedindo qualquer leitura de localização.

---

## Cenário 8: Exclusão de conta (LGPD / Limpeza completa)

**Objetivo:** Validar que ao apagar a conta, todos os rastros de localização e permissões são excluídos definitivamente da nuvem.

- [ ] 8.1 Configure Ana e Beto compartilhando localização ativamente.
- [ ] 8.2 No console do Firebase, confirme a existência de:
  - `localizacoes/{uidAna}`
  - `permissoesLocalizacao/{uidAna}_{uidBeto}`
- [ ] 8.3 No Aparelho A (Ana), abra a **Garagem** > ícone de perfil no topo > **Apagar meus dados e minha conta**. Digite `APAGAR` e confirme.
- [ ] 8.4 Aguarde a conclusão da exclusão e verifique no console do Firebase:
  - [ ] O documento `localizacoes/{uidAna}` foi **deletado**.
  - [ ] O documento `permissoesLocalizacao/{uidAna}_{uidBeto}` foi **deletado**.
  - [ ] Qualquer documento em `permissoesLocalizacao` onde Ana figurava como `para` foi **deletado**.
  - [ ] Permanece apenas a lápide `usuarios/{uidAna}/config/exclusao` com `pending: true` (protegendo contra reenvio).
- [ ] 8.5 No Aparelho B (Beto), Ana desaparece da lista de amigos e nenhum marcador ou localização órfã permanece visível.

---

## Cenário 9: Casos de borda e tolerância a falhas

- [ ] 9.1 **Queda abrupta / Celular desligado na estrada**:
  - Se o celular de Ana desligar repentinamente por falta de bateria sem enviar o `clearLocation`, Beto mantém o último ponto no mapa por no máximo **5 minutos**. Após 5 minutos sem atualização de `updatedAtMs`, o filtro do cliente considera o ponto expirado e oculta o marcador automaticamente.
- [ ] 9.2 **Economia de cota e bateria**:
  - Em deslocamento contínuo, verifique no logcat ou console que novas escritas no Firestore respeitam o intervalo mínimo de **30 segundos** ou deslocamento de **100 metros**, evitando consumo excessivo da cota diária do plano gratuito Spark.
