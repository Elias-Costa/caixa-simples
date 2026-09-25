import 'fake-indexeddb/auto'
import { deleteDB } from 'idb'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { Cliente, DadosDoCliente, DadosDoProduto, Produto } from '../api/cadastro'
import { SemConexao } from '../api/cliente'
import { gravarIdentidade, gravarToken } from '../sessao/armazenamento'
import type { Identidade } from '../sessao/Identidade'
import { tokenComExpiracao } from '../sessao/tokenDeTeste'
import { criarCadastroLocal } from './cadastroLocal'
import { guardarRetrato, listarGestos } from './fila'

const ana: Identidade = {
  contaId: 'conta-a', usuarioId: 'ana', nome: 'Ana', nomeNegocio: 'Cafeteria Aurora',
  perfil: 'ADMIN', estoqueHabilitado: false,
}
const sugerido: Produto = {
  id: '00000000-0000-4000-8000-000000000001', versao: 4, tipo: 'PRODUTO',
  nome: 'Café expresso', preco: 0, codigo: null, categoria: 'Bebidas', unidade: 'un', atributos: {},
}

function entrar(identidade: Identidade) {
  gravarToken(tokenComExpiracao(new Date(Date.now() + 60 * 60 * 1000)))
  gravarIdentidade(identidade)
}

function remoto(produtos: Produto[] = [], clientes: Cliente[] = []) {
  return {
    produtos: vi.fn(async () => produtos),
    buscarProdutos: vi.fn(async (_termo: string) => produtos),
    criarProduto: vi.fn(async (_dados: DadosDoProduto) => ({ id: crypto.randomUUID() })),
    editarProduto: vi.fn(async (_id: string, _dados: DadosDoProduto) => undefined),
    inativarProduto: vi.fn(async (_id: string) => undefined),
    clientes: vi.fn(async () => clientes),
    clientesInativos: vi.fn(async () => [] as Cliente[]),
    criarCliente: vi.fn(async (_dados: DadosDoCliente) => ({ id: crypto.randomUUID() })),
    editarCliente: vi.fn(async (_id: string, _dados: DadosDoCliente) => undefined),
    inativarCliente: vi.fn(async (_id: string) => undefined),
    reativarCliente: vi.fn(async (_id: string) => undefined),
  }
}

function rede(online: boolean) {
  vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(online)
}

beforeEach(async () => {
  await deleteDB('caixa-simples-offline')
})
afterEach(() => vi.restoreAllMocks())

describe('cadastro local', () => {
  it('guarda o catálogo já copiado no servidor e edita Produto e Cliente sem rede após recarga', async () => {
    entrar(ana)
    rede(true)
    const servidor = remoto([sugerido], [{ id: '00000000-0000-4000-8000-000000000002',
      versao: 7, nome: 'Dona Marta', contato: null }])
    const cadastro = criarCadastroLocal(servidor)
    expect((await cadastro.produtos()).map((item) => item.nome)).toEqual(['Café expresso'])
    expect(await listarGestos()).toEqual([])
    await cadastro.clientes()

    rede(false)
    const recarregado = criarCadastroLocal(servidor)
    await recarregado.editarProduto(sugerido.id, { ...sugerido, nome: 'Café da casa', preco: 8 })
    const criado = await recarregado.criarProduto({
      tipo: 'SERVICO', nome: 'Entrega', preco: 3, codigo: null,
      categoria: null, unidade: null, atributos: {},
    })
    await recarregado.editarProduto(criado.id, {
      tipo: 'SERVICO', nome: 'Entrega local', preco: 4, codigo: null,
      categoria: null, unidade: null, atributos: {},
    })
    await recarregado.editarCliente('00000000-0000-4000-8000-000000000002', {
      nome: 'Marta', contato: '9999',
    })
    const clienteNovo = await recarregado.criarCliente({ nome: 'Bia', contato: null })

    expect((await recarregado.produtos()).map((item) => item.nome)).toEqual(['Café da casa', 'Entrega local'])
    expect((await recarregado.clientes()).map((item) => item.nome)).toEqual(['Marta', 'Bia'])
    expect(criado.id).toMatch(/^[0-9a-f-]{36}$/)
    expect(clienteNovo.id).toMatch(/^[0-9a-f-]{36}$/)
    const gestos = await listarGestos()
    expect(gestos).toHaveLength(5)
    expect(gestos.find((item) => item.tipo === 'produto.editar' && item.registroId === sugerido.id))
      .toMatchObject({ versaoBase: 4, estado: 'queued' })
    const criacao = gestos.find((item) => item.tipo === 'produto.criar')!
    expect(gestos.find((item) => item.tipo === 'produto.editar' && item.registroId === criado.id))
      .toMatchObject({ versaoBase: 0, dependeDe: [criacao.operacaoId] })
    expect(gestos.find((item) => item.tipo === 'cliente.editar'))
      .toMatchObject({ versaoBase: 7 })
    expect(servidor.criarProduto).not.toHaveBeenCalled()
    expect(servidor.editarProduto).not.toHaveBeenCalled()

    entrar({ ...ana, usuarioId: 'operador', perfil: 'OPERADOR' })
    expect((await recarregado.produtos()).map((item) => item.nome)).toEqual(['Café expresso'])
    expect(await listarGestos()).toEqual([])
    await expect(recarregado.criarProduto({ tipo: 'PRODUTO', nome: 'Outro', preco: 1,
      codigo: null, categoria: null, unidade: null, atributos: {} })).rejects.toThrow('Só ADMIN')
  })

  it('isola Contas com Produto e Cliente homônimos no retrato e na fila', async () => {
    rede(false)
    const cadastro = criarCadastroLocal(remoto())
    entrar(ana)
    await guardarRetrato('produtos', [])
    await guardarRetrato('clientesAtivos', [])
    await guardarRetrato('clientesInativos', [])
    const a = await cadastro.criarProduto({ tipo: 'PRODUTO', nome: 'Café', preco: 5,
      codigo: null, categoria: null, unidade: null, atributos: {} })
    await cadastro.criarCliente({ nome: 'Maria', contato: null })

    entrar({ ...ana, contaId: 'conta-b', nomeNegocio: 'Loja da Esquina' })
    await guardarRetrato('produtos', [])
    await guardarRetrato('clientesAtivos', [])
    await guardarRetrato('clientesInativos', [])
    expect(await cadastro.produtos()).toEqual([])
    expect(await cadastro.clientes()).toEqual([])
    const b = await cadastro.criarProduto({ tipo: 'PRODUTO', nome: 'Café', preco: 9,
      codigo: null, categoria: null, unidade: null, atributos: {} })
    await cadastro.criarCliente({ nome: 'Maria', contato: null })
    expect((await cadastro.produtos())[0]).toMatchObject({ id: b.id, preco: 9 })
    expect(await listarGestos()).toHaveLength(2)

    entrar(ana)
    expect((await cadastro.produtos())[0]).toMatchObject({ id: a.id, preco: 5 })
    expect((await cadastro.clientes()).map((item) => item.nome)).toEqual(['Maria'])
    expect(await listarGestos()).toHaveLength(2)
  })

  it('busca no catálogo do dispositivo com a regra do balcão e só usa a API sem pendência', async () => {
    entrar(ana)
    const item = (id: string, nome: string, codigo: string | null): Produto => ({
      id, versao: 1, tipo: 'PRODUTO', nome, preco: 5, codigo, categoria: null, unidade: null, atributos: {},
    })
    const bolo = item('00000000-0000-4000-8000-000000000011', 'Bolo', 'bolo')
    const boloDeCafe = item('00000000-0000-4000-8000-000000000012', 'Bolo de café', null)
    const expresso = item('00000000-0000-4000-8000-000000000013', 'Café expresso', 'CA-1')
    const agua = item('00000000-0000-4000-8000-000000000014', 'Água', 'cafe')
    rede(true)
    const servidor = remoto([expresso, boloDeCafe, bolo, agua])
    const cadastro = criarCadastroLocal(servidor)
    await cadastro.produtos()
    expect(await cadastro.buscarProdutos('bolo')).toEqual([expresso, boloDeCafe, bolo, agua])
    expect(servidor.buscarProdutos).toHaveBeenCalledWith('bolo')

    servidor.buscarProdutos.mockRejectedValueOnce(new SemConexao())
    expect((await cadastro.buscarProdutos(' BOLO ')).map((produto) => produto.nome)).toEqual(['Bolo', 'Bolo de café'])

    rede(false)
    expect((await cadastro.buscarProdutos('ca-1')).map((produto) => produto.nome)).toEqual(['Café expresso'])
    expect((await cadastro.buscarProdutos('cafe')).map((produto) => produto.nome)).toEqual(['Água'])
    expect((await cadastro.buscarProdutos('café')).map((produto) => produto.nome))
      .toEqual(['Bolo de café', 'Café expresso'])
    await expect(cadastro.buscarProdutos('  ')).rejects.toThrow('Informe nome ou código')

    await cadastro.inativarProduto(boloDeCafe.id)
    const novo = await cadastro.criarProduto({ tipo: 'PRODUTO', nome: 'Café coado', preco: 4,
      codigo: null, categoria: null, unidade: null, atributos: {} })
    rede(true)
    servidor.buscarProdutos.mockClear()
    expect((await cadastro.buscarProdutos('café')).map((produto) => produto.id)).toEqual([novo.id, expresso.id])
    expect(servidor.buscarProdutos).not.toHaveBeenCalled()
  })
})
