import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  plugins: [react()],
  server: {
    // 3000 e 5173 estão ambos na allowlist CORS do backend; 3000 evita
    // conflito com outros dev servers locais.
    port: 3000,
    strictPort: true,
    // Todas as interfaces: o backoffice pode ser aberto noutra máquina da LAN sem configurar IPs.
    host: true,
    // O browser só fala com esta porta; /api é encaminhado para o backend local, que nunca
    // precisa de ser exposto à LAN.
    proxy: {
      "/api": {
        target: "http://localhost:8081",
        configure: (proxy) => {
          // O pedido proxied é servidor-a-servidor: sem Origin, o CORS do backend não o trata
          // como pedido de outra origem (o IP LAN do frontend não está, nem deve estar, na
          // allowlist). A autenticação continua a ser feita pelo Bearer token.
          proxy.on("proxyReq", (proxyReq) => proxyReq.removeHeader("origin"));
        },
      },
    },
  },
});
