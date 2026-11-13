import 'fake-indexeddb/auto'
import { deleteDB } from 'idb'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { Cliente, DadosDoCliente, DadosDoProduto, Produto } from '../api/cadastro'
import { SemConexao } from '../api/cliente'
import { registrarResposta, registrarSemResposta } from '../api/conexao'
import type { OperacaoDoLote } from '../api/sincronizacao'
import { gravarIdentidade, gravarToken } from '../sessao/armazenamento'
import type { Identidade } from '../sessao/Identidade'
import { tokenComExpiracao } from '../sessao/tokenDeTeste'
import { criarCadastroLocal } from './cadastroLocal'
import { enviarFila } from './envio'
import { enfileirarGesto, guardarRetrato, lerRetrato, listarGestos, mudarEstadoDoGesto } from './fila'

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

function remoto(produtos: Produto[] = [], clientes: Cliente[] = [], produtosInativos: Produto[] = []) {
  return {
    produtos: vi.fn(async () => produtos),
    produtosInativos: vi.fn(async () => produtosInativos),
    buscarProdutos: vi.fn(async (_termo: string) => produtos),
    criarProduto: vi.fn(async (_dados: DadosDoProduto, _id?: string) => ({ id: crypto.randomUUID() })),
    editarProduto: vi.fn(async (_id: string, _dados: DadosDoProduto) => undefined),
    inativarProduto: vi.fn(async (_id: string) => undefined),
    reativarProduto: vi.fn(async (_id: string) => undefined),
    clientes: vi.fn(async () => clientes),
    clientesInativos: vi.fn(async () => [] as Cliente[]),
    criarCliente: vi.fn(async (_dados: DadosDoCliente, _id?: string) => ({ id: crypto.randomUUID() })),
    editarCliente: vi.fn(async (_id: string, _dados: DadosDoCliente) => undefined),
    inativarCliente: vi.fn(async (_id: string) => undefined),
    reativarCliente: vi.fn(async (_id: string) => undefined),
    removerCliente: vi.fn(async (_id: string) => undefined),
  }
}

function rede(online: boolean) {
  vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(online)
}

beforeEach(async () => {
  await deleteDB('ecrx-caixa-offline')
})
afterEach(() => vi.restoreAllMocks())

describe('cadastro local', () => {
  it('remove Cliente online e limpa o retrato e o gesto enviado neste aparelho', async () => {
    entrar(ana)
    rede(true)
    const id = '00000000-0000-4000-8000-000000000009'
    const servidor = remoto([], [{ id, versao: 1, nome: 'Nome pessoal', contato: 'contato' }])
    const cadastro = criarCadastroLocal(servidor)
    await cadastro.clientes()
    const gesto = await enfileirarGesto({ tipo: 'cliente.editar', registroId: id,
      payload: { nome: 'Nome pessoal', contato: 'contato' } })
    await mudarEstadoDoGesto(gesto.operacaoId, 'queued', 'syncing')
    await mudarEstadoDoGesto(gesto.operacaoId, 'syncing', 'sent', { aplicada: true })

    await cadastro.removerCliente(id)
    expect(servidor.removerCliente).toHaveBeenCalledWith(id)
    expect(await listarGestos()).toEqual([])
    rede(false)
    expect(await cadastro.clientes()).toEqual([])
  })

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
    await guardarRetrato('produtos', [], 0)
    await guardarRetrato('clientesAtivos', [], 0)
    await guardarRetrato('clientesInativos', [], 0)
    const a = await cadastro.criarProduto({ tipo: 'PRODUTO', nome: 'Café', preco: 5,
      codigo: null, categoria: null, unidade: null, atributos: {} })
    await cadastro.criarCliente({ nome: 'Maria', contato: null })

    entrar({ ...ana, contaId: 'conta-b', nomeNegocio: 'Loja da Esquina' })
    await guardarRetrato('produtos', [], 0)
    await guardarRetrato('clientesAtivos', [], 0)
    await guardarRetrato('clientesInativos', [], 0)
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
    // O código casa inteiro e vem primeiro; no nome, o acento não conta, nos dois sentidos.
    expect((await cadastro.buscarProdutos('cafe')).map((produto) => produto.nome))
      .toEqual(['Água', 'Bolo de café', 'Café expresso'])
    expect((await cadastro.buscarProdutos('café')).map((produto) => produto.nome))
      .toEqual(['Bolo de café', 'Café expresso'])
    expect((await cadastro.buscarProdutos('agua')).map((produto) => produto.nome)).toEqual(['Água'])
    await expect(cadastro.buscarProdutos('  ')).rejects.toThrow('Informe o nome ou o código')

    await cadastro.inativarProduto(boloDeCafe.id)
    const novo = await cadastro.criarProduto({ tipo: 'PRODUTO', nome: 'Café coado', preco: 4,
      codigo: null, categoria: null, unidade: null, atributos: {} })
    rede(true)
    servidor.buscarProdutos.mockClear()
    expect((await cadastro.buscarProdutos('café')).map((produto) => produto.id)).toEqual([novo.id, expresso.id])
    expect(servidor.buscarProdutos).not.toHaveBeenCalled()
  })

  it('a edição enviada não esconde o preço trocado depois no servidor', async () => {
    entrar(ana)
    rede(true)
    const servidor = remoto([sugerido])
    const cadastro = criarCadastroLocal(servidor)
    await cadastro.produtos()

    rede(false)
    await cadastro.editarProduto(sugerido.id, { ...sugerido, preco: 8 })
    await enviarFila(async (operacoes: OperacaoDoLote[]) => operacoes.map((operacao) => ({
      operacaoId: operacao.operacaoId, resultado: 'APLICADA' as const, versao: 5 })))
    // Sem leitura nova, a edição confirmada continua valendo, com a revisão do servidor.
    expect((await cadastro.produtos())[0]).toMatchObject({ preco: 8, versao: 5 })

    // Outro aparelho trocou o preço depois, e a leitura nova já contém a edição deste.
    rede(true)
    servidor.produtos.mockResolvedValue([{ ...sugerido, preco: 9, versao: 6 }])
    expect((await cadastro.produtos())[0]).toMatchObject({ preco: 9, versao: 6 })
    rede(false)
    expect((await cadastro.produtos())[0]).toMatchObject({ preco: 9, versao: 6 })
  })

  it('reativa Produto sem rede, com a recusa do servidor quando o código foi para outro item', async () => {
    entrar(ana)
    rede(true)
    const item = (id: string, nome: string, codigo: string | null): Produto => ({
      id, versao: 3, tipo: 'PRODUTO', nome, preco: 5, codigo, categoria: null, unidade: 'un', atributos: {},
    })
    const paoDeQueijo = item('00000000-0000-4000-8000-000000000021', 'Pão de queijo', '789')
    const paoFrances = item('00000000-0000-4000-8000-000000000022', 'Pão francês', '789')
    const boloVelho = item('00000000-0000-4000-8000-000000000023', 'Bolo de fubá', null)
    const servidor = remoto([paoDeQueijo], [], [paoFrances, boloVelho])
    const cadastro = criarCadastroLocal(servidor)
    // As duas listas vêm num pedido só, e o retrato das duas tem a mesma ordem da leitura.
    const [ativos, inativos] = await Promise.all([cadastro.produtos(), cadastro.produtosInativos()])
    expect(ativos.map((produto) => produto.nome)).toEqual(['Pão de queijo'])
    expect(inativos.map((produto) => produto.nome)).toEqual(['Pão francês', 'Bolo de fubá'])
    expect(servidor.produtos).toHaveBeenCalledTimes(1)
    expect(servidor.produtosInativos).toHaveBeenCalledTimes(1)
    expect(await lerRetrato('produtosInativos')).toEqual({ dados: [paoFrances, boloVelho], ordemDaLeitura: 0 })

    rede(false)
    await cadastro.reativarProduto(boloVelho.id)
    await expect(cadastro.reativarProduto(paoFrances.id)).rejects.toThrow('O código 789 já é de Pão '
      + 'de queijo, que está ativo. Para reativar Pão francês, mude o código de Pão de queijo ou inative-o antes.')
    await expect(cadastro.editarProduto(paoFrances.id, { ...paoFrances, preco: 6 }))
      .rejects.toThrow('O produto Pão francês está inativo; reative-o antes de editar.')
    expect((await cadastro.produtos()).map((produto) => produto.nome)).toEqual(['Pão de queijo', 'Bolo de fubá'])
    expect((await cadastro.buscarProdutos('bolo')).map((produto) => produto.nome)).toEqual(['Bolo de fubá'])

    // Com o dono do código fora do catálogo, a reativação passa, também sem rede.
    await cadastro.inativarProduto(paoDeQueijo.id)
    await cadastro.reativarProduto(paoFrances.id)
    expect((await cadastro.produtos()).map((produto) => produto.nome)).toEqual(['Pão francês', 'Bolo de fubá'])
    expect((await cadastro.produtosInativos()).map((produto) => produto.nome)).toEqual(['Pão de queijo'])
    // Registros diferentes gravados no mesmo milissegundo não têm ordem entre si.
    const gestos = await listarGestos()
    expect(gestos.map((gesto) => gesto.tipo).sort())
      .toEqual(['produto.inativar', 'produto.reativar', 'produto.reativar'])
    expect(gestos.find((gesto) => gesto.registroId === boloVelho.id))
      .toMatchObject({ tipo: 'produto.reativar', versaoBase: 3, payload: {} })
    expect(servidor.reativarProduto).not.toHaveBeenCalled()

    entrar({ ...ana, usuarioId: 'operador', perfil: 'OPERADOR' })
    await expect(cadastro.reativarProduto(paoDeQueijo.id)).rejects.toThrow('Só ADMIN')
  })

  it('lê as duas listas de clientes juntas, num pedido só para a tela que pede as duas', async () => {
    entrar(ana)
    rede(true)
    const servidor = remoto([], [{ id: '00000000-0000-4000-8000-000000000002', versao: 1,
      nome: 'Dona Marta', contato: null }])
    const cadastro = criarCadastroLocal(servidor)
    const [ativos, inativos] = await Promise.all([cadastro.clientes(), cadastro.clientesInativos()])
    expect(ativos.map((cliente) => cliente.nome)).toEqual(['Dona Marta'])
    expect(inativos).toEqual([])
    expect(servidor.clientes).toHaveBeenCalledTimes(1)
    expect(servidor.clientesInativos).toHaveBeenCalledTimes(1)
    expect(await lerRetrato('clientesInativos')).toEqual({ dados: [], ordemDaLeitura: 0 })
  })
})

describe('cadastro com o servidor fora e o Wi-Fi ligado', () => {
  it('o produto cuja criação ficou sem resposta é guardado na fila, e o seguinte nem tenta o servidor', async () => {
    entrar(ana)
    rede(true)
    await guardarRetrato('produtos', [], 0)
    await guardarRetrato('produtosInativos', [], 0)
    const api = remoto()
    api.criarProduto.mockImplementation(async () => {
      registrarSemResposta()
      throw new SemConexao()
    })
    const local = criarCadastroLocal(api)
    const dados: DadosDoProduto = { tipo: 'PRODUTO', nome: 'Pão de queijo', preco: 4, codigo: null,
      categoria: null, unidade: 'un', atributos: {} }

    const { id } = await local.criarProduto(dados)
    await local.criarProduto({ ...dados, nome: 'Bolo de milho' })

    expect(api.criarProduto).toHaveBeenCalledTimes(1)
    // O gesto leva o id que a chamada levou: se ela gravou, o servidor reconhece o mesmo item.
    expect(api.criarProduto).toHaveBeenCalledWith(dados, id)
    // Registros diferentes gravados no mesmo milissegundo não têm ordem entre si.
    const gestos = await listarGestos()
    expect(gestos.map((gesto) => [gesto.tipo, gesto.payload])).toEqual(expect.arrayContaining([
      ['produto.criar', expect.objectContaining({ nome: 'Pão de queijo' })],
      ['produto.criar', expect.objectContaining({ nome: 'Bolo de milho' })],
    ]))
    expect(gestos.find((gesto) => (gesto.payload as { nome: string }).nome === 'Pão de queijo')?.registroId).toBe(id)
  })

  it('o cliente cuja criação ficou sem resposta vai para a fila com o id que a chamada levou', async () => {
    entrar(ana)
    rede(true)
    // O teste anterior deixou o aparelho sem servidor.
    registrarResposta()
    const api = remoto()
    api.criarCliente.mockImplementation(async () => {
      registrarSemResposta()
      throw new SemConexao()
    })
    const local = criarCadastroLocal(api)
    const dados: DadosDoCliente = { nome: 'Dona Marta', contato: '71 98888-0000' }

    const { id } = await local.criarCliente(dados)

    expect(api.criarCliente).toHaveBeenCalledTimes(1)
    expect(api.criarCliente).toHaveBeenCalledWith(dados, id)
    expect(await listarGestos()).toEqual([expect.objectContaining({
      tipo: 'cliente.criar', registroId: id, payload: { nome: 'Dona Marta', contato: '71 98888-0000' },
    })])
  })

  it('com resposta, o cliente e o produto levam ao servidor um id novo a cada cadastro', async () => {
    entrar(ana)
    rede(true)
    // O teste anterior deixou o aparelho sem servidor.
    registrarResposta()
    const api = remoto()
    const local = criarCadastroLocal(api)
    const produto: DadosDoProduto = { tipo: 'PRODUTO', nome: 'Broa', preco: 3, codigo: null,
      categoria: null, unidade: 'un', atributos: {} }

    await local.criarProduto(produto)
    await local.criarProduto(produto)
    await local.criarCliente({ nome: 'Bia', contato: null })

    const idsDosProdutos = api.criarProduto.mock.calls.map((chamada) => chamada[1])
    expect(idsDosProdutos).toEqual([expect.any(String), expect.any(String)])
    expect(idsDosProdutos[0]).not.toBe(idsDosProdutos[1])
    expect(api.criarCliente).toHaveBeenCalledWith({ nome: 'Bia', contato: null }, expect.any(String))
    expect(await listarGestos()).toEqual([])
  })
})

describe('cadastro ainda não copiado para o aparelho', () => {
  it('diz que a cópia sai quando a rede voltar, sem pedir a quem já entrou que entre de novo', async () => {
    entrar(ana)
    rede(false)
    const local = criarCadastroLocal(remoto())

    await expect(local.produtos()).rejects.toThrow(
      'O catálogo ainda não foi copiado para este aparelho. Ele é copiado quando a rede voltar.')
    await expect(local.buscarProdutos('café')).rejects.toThrow(
      'O catálogo ainda não foi copiado para este aparelho. Ele é copiado quando a rede voltar.')
    await expect(local.clientes()).rejects.toThrow(
      'O cadastro de clientes ainda não foi copiado para este aparelho. Ele é copiado quando a rede voltar.')
  })

  it('com o Wi-Fi ligado e o servidor fora, diz que a cópia sai quando o servidor responder', async () => {
    entrar(ana)
    rede(true)
    const semResposta = async (): Promise<never> => {
      registrarSemResposta()
      throw new SemConexao()
    }
    const local = criarCadastroLocal({ ...remoto(), produtos: vi.fn(semResposta), clientes: vi.fn(semResposta) })

    await expect(local.produtos()).rejects.toThrow(
      'O catálogo ainda não foi copiado para este aparelho. Ele é copiado quando o servidor responder.')
    await expect(local.clientes()).rejects.toThrow(
      'O cadastro de clientes ainda não foi copiado para este aparelho. Ele é copiado quando o servidor responder.')
  })
})
