import type { Identidade } from './Identidade'

// O token fica no dispositivo para o aplicativo abrir sem rede enquanto ele não expirar. A
// identidade fica ao lado porque o cabeçalho mostra o nome do negócio e de quem opera também
// sem rede, e sem rede não há como perguntar ao servidor.
const CHAVE_DO_TOKEN_LEGADO = 'caixa-simples.token'
const CHAVE_DA_IDENTIDADE_LEGADA = 'caixa-simples.identidade'
export const CHAVE_DA_SESSAO = 'caixa-simples.sessao'
const CHAVE_DA_SESSAO_NA_ABA = 'caixa-simples.sessao-na-aba'
const SESSAO_LEGADA = 'legada'

function chaveDoToken(sessao: string): string { return `caixa-simples.token.${sessao}` }
function chaveDaIdentidade(sessao: string): string { return `caixa-simples.identidade.${sessao}` }

export function lerToken(): string | null {
  const sessao = localStorage.getItem(CHAVE_DA_SESSAO) ?? SESSAO_LEGADA
  return lerTokenDaSessao(sessao)
}

export function lerTokenDaSessao(sessao: string): string | null {
  return localStorage.getItem(chaveDoToken(sessao))
    ?? (sessao === SESSAO_LEGADA ? localStorage.getItem(CHAVE_DO_TOKEN_LEGADO) : null)
}

export function sessaoDaAba(): string | null {
  return sessionStorage.getItem(CHAVE_DA_SESSAO_NA_ABA)
}

export function fixarSessaoDaAba(sessao: string): void {
  sessionStorage.setItem(CHAVE_DA_SESSAO_NA_ABA, sessao)
}

export function gravarToken(token: string): string {
  const anterior = sessaoAtual()
  const nova = crypto.randomUUID()
  // O token nasce na sua própria chave antes de a sessão ativa mudar. Uma resposta da sessão
  // antiga pode chegar em outra aba, mas só escreve na chave antiga, nunca no token novo.
  localStorage.setItem(chaveDoToken(nova), token)
  localStorage.removeItem(CHAVE_DA_IDENTIDADE_LEGADA)
  localStorage.removeItem(CHAVE_DO_TOKEN_LEGADO)
  localStorage.setItem(CHAVE_DA_SESSAO, nova)
  fixarSessaoDaAba(nova)
  if (anterior) {
    localStorage.removeItem(chaveDoToken(anterior))
    localStorage.removeItem(chaveDaIdentidade(anterior))
  }
  return nova
}

/** Mantém o mesmo identificador durante as renovações, inclusive entre abas. */
export function sessaoAtual(): string | null {
  const sessao = localStorage.getItem(CHAVE_DA_SESSAO) ?? SESSAO_LEGADA
  return lerTokenDaSessao(sessao) ? sessao : null
}

export function renovarTokenDaSessao(sessao: string, token: string): void {
  if (sessaoAtual() !== sessao) return
  localStorage.setItem(chaveDoToken(sessao), token)
  // Outra aba pode mudar a sessão entre a comparação e a escrita. A chave antiga não é lida
  // pela sessão nova; removê-la aqui evita deixar um token órfão no dispositivo.
  if (sessaoAtual() !== sessao) localStorage.removeItem(chaveDoToken(sessao))
}

export function lerIdentidade(): Identidade | null {
  const sessao = localStorage.getItem(CHAVE_DA_SESSAO) ?? SESSAO_LEGADA
  const guardada = localStorage.getItem(chaveDaIdentidade(sessao))
    ?? (sessao === SESSAO_LEGADA ? localStorage.getItem(CHAVE_DA_IDENTIDADE_LEGADA) : null)
  if (!guardada) return null
  try {
    return JSON.parse(guardada) as Identidade
  } catch {
    return null
  }
}

export function gravarIdentidade(identidade: Identidade, sessao = sessaoAtual()): void {
  if (!sessao) return
  localStorage.setItem(chaveDaIdentidade(sessao), JSON.stringify(identidade))
  if (sessaoAtual() !== sessao) localStorage.removeItem(chaveDaIdentidade(sessao))
}

export function limparSessao(): void {
  const anterior = sessaoDaAba()
  sessionStorage.removeItem(CHAVE_DA_SESSAO_NA_ABA)
  // Uma aba antiga não deve derrubar a sessão que outra aba abriu depois.
  if (!anterior || sessaoAtual() !== anterior) return
  localStorage.removeItem(CHAVE_DO_TOKEN_LEGADO)
  localStorage.removeItem(CHAVE_DA_IDENTIDADE_LEGADA)
  localStorage.removeItem(CHAVE_DA_SESSAO)
  if (anterior) {
    localStorage.removeItem(chaveDoToken(anterior))
    localStorage.removeItem(chaveDaIdentidade(anterior))
  }
}
