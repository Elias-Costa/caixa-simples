import { createContext } from 'react'
import type { Identidade } from './Identidade'

export type Sessao = {
  /** Nula quando não há ninguém autenticado neste dispositivo. */
  identidade: Identidade | null
  entrar: (email: string, senha: string) => Promise<void>
  sair: () => void
  atualizarIdentidade: (identidade: Identidade, sessao: string) => void
}

export const SessaoContext = createContext<Sessao | null>(null)
