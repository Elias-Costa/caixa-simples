import { ErroDaApi, SemConexao, SessaoAlterada } from '../api/cliente'
import { sincronizacao, type OperacaoDoLote, type ResultadoDaOperacao } from '../api/sincronizacao'
import {
  iniciarEnvio, listarGestos, ordenarPorDependencia, recuperarEnviosInterrompidos, registrarDesfechos,
  sessaoPodeUsarAFila, type Desfecho, type GestoNaFila,
} from './fila'

/** Operações por pedido; o servidor recusa o lote inteiro acima disso. */
export const LIMITE_DO_LOTE = 100

/**
 * Como terminou uma rodada de envio.
 *
 * - sem-fila: sem IndexedDB ou sem sessão válida; a fila nem foi lida.
 * - ocupada: outra rodada, nesta aba ou em outra, está enviando.
 * - concluida: todos os lotes tiveram resposta, mesmo que algum gesto tenha falhado nela.
 * - interrompida: sem rede, erro do servidor ou lote recusado; o resto espera a próxima rodada.
 * - sessao-recusada: o servidor não aceitou o token; a fila fica, e é preciso entrar de novo.
 */
export type DesfechoDaRodada = 'sem-fila' | 'ocupada' | 'concluida' | 'interrompida' | 'sessao-recusada'

export type RodadaDeEnvio = { desfecho: DesfechoDaRodada; resolvidos: number }

type EnviarLote = (operacoes: OperacaoDoLote[]) => Promise<ResultadoDaOperacao[]>

const TRAVA_DO_ENVIO = 'caixa-simples-envio-da-fila'
let rodadaNestaAba = false

/**
 * Envia a fila do usuário autenticado ao servidor e grava o resultado de cada gesto.
 *
 * Uma rodada por vez em todo o aparelho: a trava do navegador é exclusiva entre abas, e quem não
 * a consegue desiste em vez de esperar, porque a rodada que está em curso já envia o que há. Os
 * candidatos são os gestos na fila e os com falha, em ordem de dependência, em lotes de até
 * {@link LIMITE_DO_LOTE}. Repetir é inofensivo: o servidor devolve o resultado gravado para o
 * mesmo id de operação.
 */
export async function enviarFila(enviar: EnviarLote = sincronizacao.enviarLote): Promise<RodadaDeEnvio> {
  if (!('indexedDB' in globalThis) || !sessaoPodeUsarAFila()) return { desfecho: 'sem-fila', resolvidos: 0 }
  if (rodadaNestaAba) return { desfecho: 'ocupada', resolvidos: 0 }
  rodadaNestaAba = true
  try {
    return await comTravaDoAparelho(() => rodada(enviar)) ?? { desfecho: 'ocupada', resolvidos: 0 }
  } finally {
    rodadaNestaAba = false
  }
}

async function comTravaDoAparelho(executar: () => Promise<RodadaDeEnvio>): Promise<RodadaDeEnvio | undefined> {
  // Navegador sem a trava entre abas conta só com a guarda desta aba.
  if (!('locks' in navigator) || !navigator.locks) return executar()
  return navigator.locks.request(TRAVA_DO_ENVIO, { ifAvailable: true },
    async (trava) => trava ? executar() : undefined)
}

async function rodada(enviar: EnviarLote): Promise<RodadaDeEnvio> {
  // Com a trava na mão, nenhuma outra rodada está enviando: o gesto que ficou em envio é de uma
  // rodada interrompida, e vira falha antes de ser repetido.
  await recuperarEnviosInterrompidos()
  const candidatos = ordenarPorDependencia((await listarGestos())
    .filter((gesto) => gesto.estado === 'queued' || gesto.estado === 'failed'))
  let resolvidos = 0
  for (let inicio = 0; inicio < candidatos.length; inicio += LIMITE_DO_LOTE) {
    const lote = await iniciarEnvio(candidatos.slice(inicio, inicio + LIMITE_DO_LOTE)
      .map((gesto) => gesto.operacaoId))
    if (!lote.length) continue
    let resposta: ResultadoDaOperacao[]
    try {
      resposta = await enviar(lote.map(paraOperacao))
    } catch (falha) {
      return { desfecho: await depoisDaFalha(lote, falha), resolvidos }
    }
    const desfechos = lote.map((gesto) => desfechoDe(gesto, resposta))
    await registrarDesfechos(desfechos)
    resolvidos += desfechos.filter((desfecho) => desfecho.estado === 'sent'
      || desfecho.estado === 'needs_review').length
  }
  return { desfecho: 'concluida', resolvidos }
}

function paraOperacao(gesto: GestoNaFila): OperacaoDoLote {
  return {
    operacaoId: gesto.operacaoId, registroId: gesto.registroId, tipo: gesto.tipo, payload: gesto.payload,
    versaoBase: gesto.versaoBase, dependeDe: gesto.dependeDe, criadoEm: gesto.criadoEm,
  }
}

function desfechoDe(gesto: GestoNaFila, resposta: ResultadoDaOperacao[]): Desfecho {
  const operacaoId = gesto.operacaoId
  const recebido = resposta.find((item) => item.operacaoId === operacaoId)
  if (!recebido) return { operacaoId, estado: 'failed', falha: 'O servidor não devolveu resultado para este gesto.' }
  const versao = typeof recebido.versao === 'number' ? { versao: recebido.versao } : {}
  const detalhe = recebido.detalhe ? { detalhe: recebido.detalhe } : {}
  switch (recebido.resultado) {
    case 'APLICADA':
      return { operacaoId, estado: 'sent', resultado: { aplicada: true, ...versao } }
    case 'APLICADA_COM_REVISAO':
      return { operacaoId, estado: 'needs_review', resultado: { aplicada: true, ...versao, ...detalhe } }
    case 'NAO_APLICADA':
      return { operacaoId, estado: 'needs_review', resultado: { aplicada: false, ...detalhe } }
    default:
      return { operacaoId, estado: 'failed', falha: recebido.detalhe ?? 'Falha temporária no servidor.' }
  }
}

/**
 * O que fazer com o lote que ficou sem resposta. O 401 volta à fila, porque o servidor não leu o
 * lote. Sem rede, erro do servidor e lote recusado viram falha: o pedido pode ter sido aplicado
 * antes de a resposta se perder. Com a sessão trocada no meio, nada é gravado, porque os gestos
 * são de outro usuário; eles viram falha quando esse usuário voltar.
 */
async function depoisDaFalha(lote: GestoNaFila[], falha: unknown): Promise<DesfechoDaRodada> {
  if (falha instanceof SessaoAlterada) return 'interrompida'
  if (falha instanceof ErroDaApi && falha.status === 401) {
    await registrarDesfechos(lote.map((gesto) => ({ operacaoId: gesto.operacaoId, estado: 'queued' })))
    return 'sessao-recusada'
  }
  const motivo = falha instanceof SemConexao
    ? 'Sem conexão com o servidor; o envio é repetido quando a rede voltar.'
    : falha instanceof ErroDaApi
      ? `O servidor recusou o envio: ${falha.detalhe ?? falha.titulo}`
      : 'O envio falhou antes da resposta do servidor.'
  await registrarDesfechos(lote.map((gesto) => ({ operacaoId: gesto.operacaoId, estado: 'failed', falha: motivo })))
  return 'interrompida'
}
