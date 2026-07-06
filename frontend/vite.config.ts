import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  plugins: [react()],
  server: {
    // 3000 e 5173 estão ambos na allowlist CORS do backend; 3000 evita
    // conflito com outros dev servers locais.
    port: 3000,
    strictPort: true,
  },
});
