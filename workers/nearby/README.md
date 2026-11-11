# Worker `nearby` (Rolê por perto)

Servidor em tempo real do "Rolê por perto", em Cloudflare Workers + Durable Objects.

## Testes

```
npm install
npm test          # unitários (node:test, sem Cloudflare): geo, auth, protocol, match, presence
npm run test:int  # integração da Celula (vitest + @cloudflare/vitest-pool-workers, roda o workerd)
```

- `npm test` só pega `test/*.test.js`; a integração fica em `test/int/*.int.test.js` e não entra nele.
- `npm run test:int` baixa/usa o binário do `workerd` (precisa de `npm install` feito) e leva cerca de 20 s.
- O pacote `@cloudflare/vitest-pool-workers` foi renomeado pela Cloudflare para `@cloudflare/vitest-plugin`
  (a 0.23.0 é a última versão do nome antigo e imprime um aviso). Funciona; a migração fica para depois.

## Hibernação da `Celula`

A `Celula` usa a API de hibernação de WebSockets. O estado de cada pessoa (uid, sessão, rolê,
última posição real, quando foi vista) é gravado no **anexo do próprio WebSocket**
(`serializeAttachment`, até 2 KB) a cada `procurar`/`posicao`/`parar`. Quando o objeto acorda,
o construtor reconstrói a `Presence` dos anexos de `ctx.getWebSockets()`. Nada de presença vai
para `ctx.storage` (que só guarda o alarme de 30 s). Consequências:

- uma conexão que cai some junto com o anexo, então não sobra pessoa fantasma;
- o que fica só na memória e se perde ao hibernar é o controle de envio (no máximo uma lista a
  cada 2 s por conexão); o próximo evento recria.

O teste `hibernação: o estado é reconstruído dos anexos dos WebSockets` cobre a escolha.

## Contrato interno Worker -> `Celula`

A `Celula` só é alcançada pelo Worker, que já verificou o ID token. O pedido de upgrade leva:
`x-rumo-uid` (obrigatório) e `x-rumo-exp` (opcional, `exp` do token em segundos; a conexão é
fechada 300 s depois dele). O servidor nunca devolve `uid`, nome nem posição real.
