# Atribuição

## WazeTripper (Apache-2.0)

O módulo `pod-protocol` é uma reimplementação em Kotlin do protocolo BLE do Tripper Pod, baseada em
**[jnsdias/wazetripper](https://github.com/jnsdias/wazetripper)** (Apache-2.0):

- `docs/PROTOCOL.md` — formato dos pacotes, tabelas de ícones, tempos de handshake/keepalive;
- `src/com/waze/wazetripper/TripperProtocol.java` — montagem dos pacotes;
- `src/test/.../PacketsCheck.java` — vetores de teste (capturas reais de um Pod da Meteor 350) portados para
  `pod-protocol/src/test/.../PodPacketsTest.kt`.

O repositório de referência fica em `ref/wazetripper` (somente leitura, ignorado pelo git deste projeto).
Os arquivos derivados mantêm os termos da licença Apache-2.0 do projeto original (ver
`ref/wazetripper/LICENSE`) e citam a origem nos comentários de cabeçalho.

O WazeTripper por sua vez credita o **wazeology** (agstrc, Apache-2.0); nada do pipeline de patch do Waze é
usado aqui — este app **não modifica o Waze**.

## Sem afiliação

Projeto pessoal, independente, **sem afiliação** com Royal Enfield / Eicher Motors, Google ou Waze.
"Royal Enfield", "Hunter 350" e "Tripper Pod" são marcas de seus respectivos donos.

## Aviso de compatibilidade

O protocolo foi observado apenas no Pod da **Meteor 350**. A compatibilidade com o Pod da **Hunter 350** é
esperada, mas **não verificada** (a moto chega em 30/11/2026). Os testes de `pod-protocol` garantem que os
bytes gerados batem com as capturas documentadas, não que o Pod da Hunter os aceite.
