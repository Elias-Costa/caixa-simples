import { chamarApi } from './cliente'

export type EstoqueDoProduto = {
  id: string
  nome: string
  codigo: string | null
  unidade: string | null
  estoqueAtual: number
  estoqueMinimo: number
}

export const estoque = {
  produtos: () => chamarApi<EstoqueDoProduto[]>('/api/estoque/produtos'),
  baixo: () => chamarApi<EstoqueDoProduto[]>('/api/estoque/baixo'),
  /** Com o id do movimento gerado no aparelho, o reenvio depois de uma resposta perdida não move o saldo de novo. */
  ajustar: (id: string, diferenca: number, motivo: string, movimentoId?: string) =>
    chamarApi<void>(`/api/estoque/produtos/${id}/ajustes`, {
      metodo: 'POST', corpo: { movimentoId, diferenca, motivo },
    }),
  /** A mercadoria comprada que chegou; o motivo, opcional, vai só quando foi escrito. */
  registrarEntrada: (id: string, quantidade: number, motivo: string, movimentoId?: string) =>
    chamarApi<void>(`/api/estoque/produtos/${id}/entradas`, {
      metodo: 'POST', corpo: motivo ? { movimentoId, quantidade, motivo } : { movimentoId, quantidade },
    }),
  definirMinimo: (id: string, minimo: number) =>
    chamarApi<void>(`/api/estoque/produtos/${id}/minimo`, {
      metodo: 'PUT', corpo: { minimo },
    }),
}
