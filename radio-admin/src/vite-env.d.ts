/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_RADIO_DEVICE_IPS?: string;
  readonly VITE_RADIO_HTTP_PORT?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
