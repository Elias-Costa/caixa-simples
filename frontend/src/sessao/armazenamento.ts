import type { Identidade } from './Identidade'

// O token fica no dispositivo para o aplicativo abrir sem rede enquanto ele não expirar. A
// identidade fica ao lado porque o cabeçalho mostra o nome do negócio e de quem opera também
// sem rede, e sem rede não há como perguntar ao servidor.
const CHAVE_DO_TOKEN = 'caixa-simples.token'
const CHAVE_DA_IDENTIDADE = 'caixa-simples.identidade'

export function lerToken(): string | null {
  return localStorage.getItem(CHAVE_DO_TOKEN)
}

export function gravarToken(token: string): void {
  localStorage.setItem(CHAVE_DO_TOKEN, token)
}

export function lerIdentidade(): Identidade | null {
  const guardada = localStorage.getItem(CHAVE_DA_IDENTIDADE)
  if (!guardada) return null
  try {
    return JSON.parse(guardada) as Identidade
  } catch {
    return null
  }
}

export function gravarIdentidade(identidade: Identidade): void {
  localStorage.setItem(CHAVE_DA_IDENTIDADE, JSON.stringify(identidade))
}

export function limparSessao(): void {
  localStorage.removeItem(CHAVE_DO_TOKEN)
  localStorage.removeItem(CHAVE_DA_IDENTIDADE)
}
