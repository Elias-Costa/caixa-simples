import { chamarApi } from './cliente'

export type TipoProduto = 'PRODUTO' | 'SERVICO'

export type Produto = {
  id: string
  tipo: TipoProduto
  nome: string
  preco: number
  codigo: string | null
  categoria: string | null
  unidade: string | null
  atributos: Record<string, unknown>
}

export type DadosDoProduto = Omit<Produto, 'id'>

export type Cliente = {
  id: string
  nome: string
  contato: string | null
}

export type DadosDoCliente = Omit<Cliente, 'id'>

export const cadastro = {
  produtos: () => chamarApi<Produto[]>('/api/produtos'),
  criarProduto: (dados: DadosDoProduto) =>
    chamarApi<{ id: string }>('/api/produtos', { metodo: 'POST', corpo: dados }),
  editarProduto: (id: string, dados: DadosDoProduto) =>
    chamarApi<void>(`/api/produtos/${id}`, { metodo: 'PUT', corpo: {
      nome: dados.nome, preco: dados.preco, codigo: dados.codigo,
      categoria: dados.categoria, unidade: dados.unidade, atributos: dados.atributos,
    } }),
  inativarProduto: (id: string) =>
    chamarApi<void>(`/api/produtos/${id}/inativar`, { metodo: 'POST' }),
  clientes: () => chamarApi<Cliente[]>('/api/clientes'),
  clientesInativos: () => chamarApi<Cliente[]>('/api/clientes/inativos'),
  criarCliente: (dados: DadosDoCliente) =>
    chamarApi<{ id: string }>('/api/clientes', { metodo: 'POST', corpo: dados }),
  editarCliente: (id: string, dados: DadosDoCliente) =>
    chamarApi<void>(`/api/clientes/${id}`, { metodo: 'PUT', corpo: dados }),
  inativarCliente: (id: string) =>
    chamarApi<void>(`/api/clientes/${id}/inativar`, { metodo: 'POST' }),
  reativarCliente: (id: string) =>
    chamarApi<void>(`/api/clientes/${id}/reativar`, { metodo: 'POST' }),
}
