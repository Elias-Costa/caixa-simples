import { chamarApi } from './cliente'
import { criarCadastroLocal } from '../offline/cadastroLocal'

export type TipoProduto = 'PRODUTO' | 'SERVICO'

export type Produto = {
  id: string
  versao?: number | null
  tipo: TipoProduto
  nome: string
  preco: number
  codigo: string | null
  categoria: string | null
  unidade: string | null
  atributos: Record<string, unknown>
}

export type DadosDoProduto = Omit<Produto, 'id' | 'versao'>

export type Cliente = {
  id: string
  versao?: number
  nome: string
  contato: string | null
}

export type DadosDoCliente = Omit<Cliente, 'id' | 'versao'>

const remoto = {
  produtos: () => chamarApi<Produto[]>('/api/produtos'),
  produtosInativos: () => chamarApi<Produto[]>('/api/produtos/inativos'),
  buscarProdutos: (termo: string) =>
    chamarApi<Produto[]>(`/api/produtos/busca?termo=${encodeURIComponent(termo)}`),
  /** Com o id gerado no aparelho, o reenvio depois de uma resposta perdida não grava outro item. */
  criarProduto: (dados: DadosDoProduto, produtoId?: string) =>
    chamarApi<{ id: string }>('/api/produtos', { metodo: 'POST', corpo: { produtoId, ...dados } }),
  editarProduto: (id: string, dados: DadosDoProduto) =>
    chamarApi<void>(`/api/produtos/${id}`, { metodo: 'PUT', corpo: {
      nome: dados.nome, preco: dados.preco, codigo: dados.codigo,
      categoria: dados.categoria, unidade: dados.unidade, atributos: dados.atributos,
    } }),
  inativarProduto: (id: string) =>
    chamarApi<void>(`/api/produtos/${id}/inativar`, { metodo: 'POST' }),
  reativarProduto: (id: string) =>
    chamarApi<void>(`/api/produtos/${id}/reativar`, { metodo: 'POST' }),
  clientes: () => chamarApi<Cliente[]>('/api/clientes'),
  clientesInativos: () => chamarApi<Cliente[]>('/api/clientes/inativos'),
  /** Com o id gerado no aparelho, o reenvio depois de uma resposta perdida não grava outro cliente. */
  criarCliente: (dados: DadosDoCliente, clienteId?: string) =>
    chamarApi<{ id: string }>('/api/clientes', { metodo: 'POST', corpo: { clienteId, ...dados } }),
  editarCliente: (id: string, dados: DadosDoCliente) =>
    chamarApi<void>(`/api/clientes/${id}`, { metodo: 'PUT', corpo: dados }),
  inativarCliente: (id: string) =>
    chamarApi<void>(`/api/clientes/${id}/inativar`, { metodo: 'POST' }),
  reativarCliente: (id: string) =>
    chamarApi<void>(`/api/clientes/${id}/reativar`, { metodo: 'POST' }),
  removerCliente: (id: string) =>
    chamarApi<void>(`/api/clientes/${id}/remover`, { metodo: 'POST' }),
}

export const cadastro = criarCadastroLocal(remoto)
