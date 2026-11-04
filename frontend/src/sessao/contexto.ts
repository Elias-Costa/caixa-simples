import { createContext } from 'react'
import type { Identidade } from './Identidade'

export type Sessao = {
  /** Nula quando não há ninguém autenticado neste dispositivo. */
  identidade: Identidade | null
  entrar: (email: string, senha: string) => Promise<void>
  sair: () => void
  atualizarIdentidade: (identidade: Identidade, sessao: string) => void
  /**
   * Pergunta de novo ao servidor quem está operando e guarda a resposta; o 401 encerra a sessão.
   * Sem resposta, fica a identidade guardada, e a promessa termina sem ela.
   */
  relerIdentidade: () => Promise<Identidade | undefined>
}

export const SessaoContext = createContext<Sessao | null>(null)
