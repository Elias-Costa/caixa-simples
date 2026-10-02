import { chamarApi } from './cliente'

export type SessaoCaixa = {
  id: string
  versao?: number
  usuarioId: string
  valorAbertura: number
  valorFechamentoEsperado: number
  valorFechamentoContado: number | null
  diferenca: number | null
  abertaEm: string
  fechadaEm: string | null
  status: 'ABERTA' | 'FECHADA'
  movimentos?: MovimentoCaixa[]
  pendenteSincronizacao?: boolean
  /** Só do aparelho: o servidor disse que a sessão fechou, e os valores do fechamento não foram lidos. */
  fechamentoSemValores?: boolean
  /**
   * Só do aparelho: a sessão mudou com rede depois da última leitura guardada, e o esperado e a
   * diferença que o aparelho tem ficaram para trás.
   */
  saldoDesatualizado?: boolean
}

export type MovimentoCaixa = {
  id: string
  tipo: 'VENDA' | 'SANGRIA' | 'SUPRIMENTO' | 'ESTORNO' | 'RECEBIMENTO'
  valor: number
  motivo: string | null
  vendaId: string | null
  recebimentoId: string | null
  criadoEm: string
}

export const caixa = {
  abertaDoOperadorAtual: () => chamarApi<SessaoCaixa | undefined>('/api/caixa/sessoes/aberta'),
  consultar: (id: string) => chamarApi<SessaoCaixa>(`/api/caixa/sessoes/${id}`),
  historico: (dia: string, operadorId?: string) =>
    chamarApi<SessaoCaixa[]>(`/api/caixa/sessoes?dia=${encodeURIComponent(dia)}${operadorId ? `&operadorId=${encodeURIComponent(operadorId)}` : ''}`),
  abrir: (valorAbertura: number) =>
    chamarApi<{ id: string }>('/api/caixa/sessoes', { metodo: 'POST', corpo: { valorAbertura } }),
  sangrar: (id: string, valor: number, motivo: string, movimentoId: string) =>
    chamarApi<void>(`/api/caixa/sessoes/${id}/sangrias`, {
      metodo: 'POST', corpo: { movimentoId, valor, motivo },
    }),
  suprir: (id: string, valor: number, motivo: string, movimentoId: string) =>
    chamarApi<void>(`/api/caixa/sessoes/${id}/suprimentos`, {
      metodo: 'POST', corpo: { movimentoId, valor, motivo },
    }),
  fechar: (id: string, valorContado: number) =>
    chamarApi<{ diferenca: number }>(`/api/caixa/sessoes/${id}/fechamento`, { metodo: 'POST', corpo: { valorContado } }),
}
