import { chamarApi } from './cliente'
import type { ValorJson } from '../offline/fila'

/** Um gesto da fila local como o servidor o recebe. */
export type OperacaoDoLote = {
  operacaoId: string
  registroId: string
  tipo: string
  payload: ValorJson
  versaoBase?: number | string
  dependeDe: string[]
  criadoEm: string
}

export type ResultadoNoServidor = 'APLICADA' | 'APLICADA_COM_REVISAO' | 'NAO_APLICADA' | 'ERRO_TRANSITORIO'

export type ResultadoDaOperacao = {
  operacaoId: string
  resultado: ResultadoNoServidor
  versao?: number
  detalhe?: string
}

/** Uma revisão ou recusa gravada no servidor, de qualquer usuário da Conta. */
export type RevisaoDaConta = {
  operacaoId: string
  usuarioId: string
  tipo: string
  registroId: string
  payload: ValorJson
  criadaEm: string
  recebidaEm: string
  resultado: 'APLICADA_COM_REVISAO' | 'NAO_APLICADA'
  detalhe?: string
  conferidaEm?: string
  conferidaPor?: string
}

export type RevisoesDaConta = { pendentes: RevisaoDaConta[]; conferidas: RevisaoDaConta[] }

export const sincronizacao = {
  enviarLote: async (operacoes: OperacaoDoLote[]) =>
    (await chamarApi<{ resultados: ResultadoDaOperacao[] }>('/api/sincronizacao', {
      metodo: 'POST', corpo: { operacoes },
    })).resultados,
  revisoes: (dia: string) =>
    chamarApi<RevisoesDaConta>(`/api/sincronizacao/revisoes?dia=${encodeURIComponent(dia)}`),
  conferir: (operacaoId: string) =>
    chamarApi<void>(`/api/sincronizacao/revisoes/${encodeURIComponent(operacaoId)}/conferencia`, {
      metodo: 'POST',
    }),
}
