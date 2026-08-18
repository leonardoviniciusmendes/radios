/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_DISCOVERY_BRIDGE_URL?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
