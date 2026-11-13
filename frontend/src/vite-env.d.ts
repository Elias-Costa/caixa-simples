/// <reference types="vite/client" />
/// <reference types="vite-plugin-pwa/react" />

interface ImportMetaEnv {
  /** O commit do build, que o CI passa ao build da imagem; não vem no build feito na máquina. */
  readonly VITE_VERSAO?: string
}
