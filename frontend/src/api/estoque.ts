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
  ajustar: (id: string, diferenca: number, motivo: string) =>
    chamarApi<void>(`/api/estoque/produtos/${id}/ajustes`, {
      metodo: 'POST', corpo: { diferenca, motivo },
    }),
  definirMinimo: (id: string, minimo: number) =>
    chamarApi<void>(`/api/estoque/produtos/${id}/minimo`, {
      metodo: 'PUT', corpo: { minimo },
    }),
}
