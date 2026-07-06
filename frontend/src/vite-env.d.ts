/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_TAXIA_API_BASE_URL?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
