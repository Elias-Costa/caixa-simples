import { aoAvisoDeOutraAba, avisarAsOutrasAbas } from '../canalDasAbas'
import type { Identidade } from './Identidade'

// O token fica no dispositivo para o aplicativo abrir sem rede enquanto ele não expirar. A
// identidade fica ao lado porque o cabeçalho mostra o nome do negócio e de quem opera também
// sem rede, e sem rede não há como perguntar ao servidor.
const CHAVE_DO_TOKEN_LEGADO = 'ecrx-caixa.token'
const CHAVE_DA_IDENTIDADE_LEGADA = 'ecrx-caixa.identidade'
export const CHAVE_DA_SESSAO = 'ecrx-caixa.sessao'
const CHAVE_DA_SESSAO_NA_ABA = 'ecrx-caixa.sessao-na-aba'
const CHAVE_DA_SESSAO_ENCERRADA = 'ecrx-caixa.sessao-encerrada'
const CHAVE_DA_SESSAO_VENCIDA = 'ecrx-caixa.sessao-vencida'
const CHAVE_DA_VOLTA_POR_OUTRA_ABA = 'ecrx-caixa.volta-por-outra-aba'
const SESSAO_LEGADA = 'legada'

function chaveDoToken(sessao: string): string { return `ecrx-caixa.token.${sessao}` }
function chaveDaIdentidade(sessao: string): string { return `ecrx-caixa.identidade.${sessao}` }

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

/**
 * O servidor recusou o token desta aba: a senha foi trocada, os aparelhos foram derrubados ou o
 * usuário foi inativado. A marca fica na aba até o próximo login, para a tela de login dizer que a
 * sessão foi encerrada e que o registrado continua guardado. As outras abas usavam o mesmo token e vão à
 * entrada quando esta sair; o aviso é o que as faz dizer por quê, cada uma com a marca dela.
 */
export function marcarSessaoEncerrada(): void {
  gravarMarcaDeEncerrada()
  avisarAsOutrasAbas({ tipo: 'sessao-encerrada' })
}

function gravarMarcaDeEncerrada(): void {
  sessionStorage.setItem(CHAVE_DA_SESSAO_ENCERRADA, 'sim')
  avisarMarcas()
}

// A marca recebida de outra aba é gravada nesta, e o aviso não volta ao canal.
aoAvisoDeOutraAba((aviso) => {
  if (aviso.tipo === 'sessao-encerrada') gravarMarcaDeEncerrada()
})

export function sessaoFoiEncerrada(): boolean {
  return sessionStorage.getItem(CHAVE_DA_SESSAO_ENCERRADA) !== null
}

/**
 * O token venceu pelo relógio do aparelho, sem ninguém o recusar. A marca é outra porque a frase é
 * outra: aqui ninguém encerrou nada, e entrar de novo pede rede. Fica no aparelho até o próximo login,
 * e não na aba: quem deixou vencer com o aplicativo aberto, fechou e abriu de novo já não tem o token, e
 * sem a marca veria a entrada sem saber que o registrado continua guardado.
 */
export function marcarSessaoVencida(): void {
  localStorage.setItem(CHAVE_DA_SESSAO_VENCIDA, 'sim')
  avisarMarcas()
}

export function sessaoVenceu(): boolean {
  return localStorage.getItem(CHAVE_DA_SESSAO_VENCIDA) !== null
}

/**
 * Por que outra aba do aplicativo trouxe esta à entrada: alguém saiu nela e a sessão desta terminou junto,
 * ou alguém entrou nela e abriu outra sessão, e o navegador fica com uma por vez. A marca é da aba, para a
 * frase continuar depois de recarregar, e sai com a entrada completa.
 */
export type VoltaPorOutraAba = 'saiu' | 'entrou'

export function marcarVoltaPorOutraAba(motivo: VoltaPorOutraAba): void {
  sessionStorage.setItem(CHAVE_DA_VOLTA_POR_OUTRA_ABA, motivo)
  // A sessão de que a marca do 401 desta aba fala foi trocada pela de quem entrou.
  if (motivo === 'entrou') sessionStorage.removeItem(CHAVE_DA_SESSAO_ENCERRADA)
  avisarMarcas()
}

/**
 * A sessão aberta pela outra aba terminou: entrar aqui já não leva ninguém à entrada, e a frase de quem
 * entrou deixaria de ser verdade.
 */
export function tirarMarcaDeEntradaEmOutraAba(): void {
  if (voltaPorOutraAba() !== 'entrou') return
  sessionStorage.removeItem(CHAVE_DA_VOLTA_POR_OUTRA_ABA)
  avisarMarcas()
}

export function voltaPorOutraAba(): VoltaPorOutraAba | null {
  const motivo = sessionStorage.getItem(CHAVE_DA_VOLTA_POR_OUTRA_ABA)
  return motivo === 'saiu' || motivo === 'entrou' ? motivo : null
}

const ouvintesDasMarcas = new Set<() => void>()

/**
 * Avisa quando uma marca da entrada muda. A entrada já à vista troca a frase, como quando alguém entra em
 * outra aba depois de esta ter voltado à entrada. Devolve o que desliga o aviso.
 */
export function aoMudarMarcasDaEntrada(ouvinte: () => void): () => void {
  ouvintesDasMarcas.add(ouvinte)
  return () => { ouvintesDasMarcas.delete(ouvinte) }
}

function avisarMarcas(): void {
  for (const ouvinte of [...ouvintesDasMarcas]) ouvinte()
}

/**
 * A pessoa entrou de fato, com o token e a identidade na mão. As marcas saem aqui, e não quando o token
 * chega: o login que responde e a identidade que não respondem deixam a pessoa na entrada, e o motivo de
 * ela estar ali continua valendo.
 */
export function limparMarcasDaEntrada(): void {
  sessionStorage.removeItem(CHAVE_DA_SESSAO_ENCERRADA)
  sessionStorage.removeItem(CHAVE_DA_VOLTA_POR_OUTRA_ABA)
  localStorage.removeItem(CHAVE_DA_SESSAO_VENCIDA)
  avisarMarcas()
}

const ouvintesDoVencimento = new Set<() => void>()

/**
 * Quem descobre o token vencido no meio do uso, como a fila, avisa por aqui, e a sessão termina na
 * hora, em vez de a tela ficar com a recusa e a lista vazia até a próxima conferência. Devolve o que
 * desliga o aviso.
 */
export function aoVencerToken(ouvinte: () => void): () => void {
  ouvintesDoVencimento.add(ouvinte)
  return () => { ouvintesDoVencimento.delete(ouvinte) }
}

export function avisarTokenVencido(): void {
  for (const ouvinte of [...ouvintesDoVencimento]) ouvinte()
}

/**
 * Apaga toda chave do aplicativo neste navegador, inclusive as que uma sessão antiga ou uma aba
 * fechada deixou para trás: tokens, identidades e marcas. Todas começam pelo mesmo prefixo.
 */
export function apagarChavesDoAparelho(): void {
  for (const armazenamento of [localStorage, sessionStorage]) {
    const chaves: string[] = []
    for (let indice = 0; indice < armazenamento.length; indice++) {
      const chave = armazenamento.key(indice)
      if (chave?.startsWith('ecrx-caixa.')) chaves.push(chave)
    }
    for (const chave of chaves) armazenamento.removeItem(chave)
  }
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
