// Config só dos testes de integração (Durable Object de verdade, via workerd).
// Os testes unitários (npm test) usam node:test e não passam por aqui.
import { defineConfig } from "vitest/config";
import { cloudflareTest } from "@cloudflare/vitest-pool-workers";

export default defineConfig({
  plugins: [
    cloudflareTest({
      wrangler: { configPath: "./wrangler.toml" },
      miniflare: {
        // Segredos e variáveis só para o teste.
        bindings: { NEARBY_SECRET: "segredo-de-teste", FIREBASE_PROJECT_ID: "projeto-de-teste" },
      },
    }),
  ],
  test: {
    include: ["test/int/**/*.int.test.js"],
  },
});
