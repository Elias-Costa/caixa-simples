import 'fake-indexeddb/auto'
import { deleteDB } from 'idb'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cadastro, type Produto } from '../api/cadastro'
import { caixa, type SessaoCaixa } from '../api/caixa'
import type { OperacaoDoLote } from '../api/sincronizacao'
import { vendas } from '../api/vendas'
import { gravarIdentidade, gravarToken } from '../sessao/armazenamento'
import type { Identidade } from '../sessao/Identidade'
import { tokenComExpiracao } from '../sessao/tokenDeTeste'
import { criarCaixaLocal } from './caixaLocal'
import { enviarFila } from './envio'
import { guardarRetrato, listarGestos, type GestoNaFila } from './fila'
import { criarVendaLocal } from './vendaLocal'

const operadora: Identidade = {
  contaId: 'conta-a', usuarioId: 'ana', nome: 'Ana', nomeNegocio: 'Cafeteria Aurora',
  perfil: 'OPERADOR', estoqueHabilitado: false,
}
const administradora: Identidade = { ...operadora, usuarioId: 'bia', nome: 'Bia', perfil: 'ADMIN' }
const cafe: Produto = {
  id: '00000000-0000-4000-8000-000000000001', versao: 3, tipo: 'PRODUTO', nome: 'Café expresso',
  preco: 6.25, codigo: 'CA-1', categoria: 'Bebidas', unidade: 'un', atributos: {},
}

function entrar(identidade: Identidade) {
  gravarToken(tokenComExpiracao(new Date(Date.now() + 60 * 60 * 1000)))
  gravarIdentidade(identidade)
}

function rede(online: boolean) {
  vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(online)
}

function apiDeVendas() {
  return {
    ...vendas, iniciar: vi.fn(), daSessao: vi.fn(), conciliacoesPix: vi.fn(), consultar: vi.fn(),
    vincularCliente: vi.fn(), adicionarItem: vi.fn(), removerItem: vi.fn(), descontar: vi.fn(),
    pagar: vi.fn(), cobrarPix: vi.fn(), concluir: vi.fn(), cancelar: vi.fn(), comprovante: vi.fn(),
  }
}

function doTipo(gestos: GestoNaFila[], tipo: string): GestoNaFila {
  const encontrado = gestos.find((gesto) => gesto.tipo === tipo)
  if (!encontrado) throw new Error(`Gesto ${tipo} não está na fila`)
  return encontrado
}

async function prepararCadastro() {
  await guardarRetrato('produtos', [cafe], 0)
  await guardarRetrato('clientesAtivos', [], 0)
  await guardarRetrato('clientesInativos', [], 0)
}

beforeEach(async () => { await deleteDB('caixa-simples-offline') })
afterEach(() => vi.restoreAllMocks())

describe('Venda no dispositivo', () => {
  it('conclui sem rede a Venda de um Produto com total e troco certos e a recupera após recarga', async () => {
    entrar(operadora)
    rede(false)
    await prepararCadastro()
    const api = apiDeVendas()
    const caixaLocal = criarCaixaLocal()
    const { id: sessaoId } = await caixaLocal.abrir(20)
    const local = criarVendaLocal(api, caixaLocal)

    const { id } = await local.iniciar(sessaoId)
    await local.adicionarItem(id, cafe, 2, 0)
    expect(await local.pagar(id, 'DINHEIRO', 12.5, 20)).toEqual({ troco: 7.5 })
    await local.concluir(id)

    const recarregada = criarVendaLocal(api, criarCaixaLocal())
    expect(await recarregada.consultar(id)).toMatchObject({
      status: 'CONCLUIDA', total: 12.5, pago: 12.5, faltaPagar: 0, pendenteSincronizacao: true,
      itens: [{ nome: 'Café expresso', quantidade: 2, precoUnitario: 6.25, subtotal: 12.5 }],
      parcelas: [{ forma: 'DINHEIRO', valor: 12.5, troco: 7.5, status: 'CONFIRMADO' }],
    })
    expect(await recarregada.comprovante(id)).toMatchObject({
      vendaId: id, valorTotal: 12.5, troco: 7.5, pendenteSincronizacao: true,
      linhas: [{ nome: 'Café expresso', quantidade: 2, subtotal: 12.5 }],
    })
    expect(await recarregada.daSessao(sessaoId)).toEqual([expect.objectContaining({
      id, status: 'CONCLUIDA', total: 12.5, pendenteSincronizacao: true,
    })])
    expect(await criarCaixaLocal().consultar(sessaoId)).toMatchObject({
      valorFechamentoEsperado: 32.5, versao: 1, movimentos: [{ tipo: 'VENDA', valor: 12.5, vendaId: id }],
    })

    const gestos = await listarGestos()
    const abertura = doTipo(gestos, 'caixa.abrir')
    const inicio = doTipo(gestos, 'venda.iniciar')
    const item = doTipo(gestos, 'venda.adicionarItem')
    const pagamento = doTipo(gestos, 'venda.registrarPagamento')
    const conclusao = doTipo(gestos, 'venda.concluir')
    expect(inicio).toMatchObject({ registroId: id, dependeDe: [abertura.operacaoId],
      payload: { sessaoCaixaId: sessaoId } })
    expect(inicio.versaoBase).toBeUndefined()
    expect(item).toMatchObject({ dependeDe: [inicio.operacaoId], versaoBase: 0, payload: {
      produtoId: cafe.id, nome: 'Café expresso', quantidade: 2, precoUnitario: 6.25, desconto: 0,
      versaoProduto: 3 } })
    expect(pagamento).toMatchObject({ dependeDe: [item.operacaoId], versaoBase: 1,
      payload: { forma: 'DINHEIRO', valor: 12.5, valorRecebido: 20 } })
    expect(conclusao).toMatchObject({ dependeDe: [pagamento.operacaoId, abertura.operacaoId], versaoBase: 2 })
    expect(Object.keys(inicio.payload as object)).not.toContain('usuarioId')
    for (const chamada of [api.iniciar, api.adicionarItem, api.pagar, api.concluir, api.consultar]) {
      expect(chamada).not.toHaveBeenCalled()
    }
  })

  it('põe o Produto e o Cliente criados na fila antes da Venda fiada do administrador', async () => {
    entrar(administradora)
    rede(false)
    await prepararCadastro()
    const caixaLocal = criarCaixaLocal()
    const { id: sessaoId } = await caixaLocal.abrir(20)
    const { id: produtoId } = await cadastro.criarProduto({ tipo: 'PRODUTO', nome: 'Bolo de milho',
      preco: 8, codigo: null, categoria: null, unidade: null, atributos: {} })
    const { id: clienteId } = await cadastro.criarCliente({ nome: 'Dona Marta', contato: null })
    const bolo = (await cadastro.buscarProdutos('bolo')).find((produto) => produto.id === produtoId)!
    const local = criarVendaLocal(apiDeVendas(), caixaLocal)

    const { id } = await local.iniciar(sessaoId)
    await local.adicionarItem(id, bolo, 1, 0)
    await local.vincularCliente(id, clienteId)
    await local.pagar(id, 'FIADO', 8)
    await local.concluir(id)

    expect(await local.comprovante(id)).toMatchObject({ valorFiado: 8, saldoDevedor: 8, troco: 0,
      parcelas: [{ forma: 'FIADO', valor: 8 }] })
    expect(await local.consultar(id)).toMatchObject({ clienteId, saldoDevedor: 8, status: 'CONCLUIDA' })
    expect(await caixaLocal.consultar(sessaoId)).toMatchObject({ valorFechamentoEsperado: 20, versao: 0,
      movimentos: [] })
    const gestos = await listarGestos()
    expect(doTipo(gestos, 'venda.adicionarItem').dependeDe)
      .toEqual([doTipo(gestos, 'venda.iniciar').operacaoId, doTipo(gestos, 'produto.criar').operacaoId])
    expect(doTipo(gestos, 'venda.adicionarItem').payload).toMatchObject({ precoUnitario: 8, versaoProduto: 0 })
    expect(doTipo(gestos, 'venda.vincularCliente').dependeDe)
      .toEqual([doTipo(gestos, 'venda.adicionarItem').operacaoId, doTipo(gestos, 'cliente.criar').operacaoId])
  })

  it('recusa sem gravar gesto o que a raiz, o perfil ou o caixa não aceitam', async () => {
    entrar(operadora)
    rede(false)
    await prepararCadastro()
    const caixaLocal = criarCaixaLocal()
    const { id: sessaoId } = await caixaLocal.abrir(20)
    const local = criarVendaLocal(apiDeVendas(), caixaLocal)
    const { id } = await local.iniciar(sessaoId)
    await local.adicionarItem(id, cafe, 2, 0)
    const antes = (await listarGestos()).length

    await expect(local.pagar(id, 'PIX', 12.5)).rejects.toThrow('Pix não entra')
    await expect(local.cobrarPix(id, crypto.randomUUID(), 12.5)).rejects.toThrow('Pix não entra')
    await expect(local.adicionarItem(id, cafe, 1, 1)).rejects.toThrow('Só ADMIN concede desconto')
    await expect(local.descontar(id, 1)).rejects.toThrow('Só ADMIN concede desconto')
    await expect(local.pagar(id, 'FIADO', 12.5)).rejects.toThrow('Só ADMIN registra')
    await expect(local.pagar(id, 'CARTAO', 12.51)).rejects.toThrow('maior que o que falta pagar')
    await expect(local.pagar(id, 'DINHEIRO', 12.5, 10)).rejects.toThrow('menor que o valor da parcela')
    await expect(local.concluir(id)).rejects.toThrow('faltam')
    await expect(local.vincularCliente(id, 'cliente-de-outro-lugar')).rejects.toThrow('desconhecido')
    await expect(local.cancelar(id)).rejects.toThrow('depois da sincronização')
    await expect(local.removerItem(id, 'item-inexistente')).rejects.toThrow('não está nesta Venda')
    expect(await listarGestos()).toHaveLength(antes)

    await local.pagar(id, 'CARTAO', 12.5)
    await caixaLocal.fechar(sessaoId, 20)
    await expect(local.concluir(id)).rejects.toThrow('não está ABERTA')
    await expect(local.iniciar(sessaoId)).rejects.toThrow('não está ABERTA')
    expect((await listarGestos()).map((gesto) => gesto.tipo).sort()).toEqual([
      'caixa.abrir', 'caixa.fechar', 'venda.adicionarItem', 'venda.iniciar', 'venda.registrarPagamento',
    ])
    expect((await local.consultar(id)).status).toBe('ABERTA')
  })

  it('usa a API com rede e sem pendência, e a Venda do servidor não continua sem rede', async () => {
    entrar(operadora)
    rede(true)
    const api = apiDeVendas()
    api.iniciar.mockResolvedValue({ id: 'venda-do-servidor' })
    api.adicionarItem.mockResolvedValue({ id: 'item-do-servidor' })
    api.pagar.mockResolvedValue({ troco: 0 })
    const local = criarVendaLocal(api, criarCaixaLocal())

    expect(await local.iniciar('sessao-do-servidor')).toEqual({ id: 'venda-do-servidor' })
    await local.adicionarItem('venda-do-servidor', cafe, 1, 0)
    await local.pagar('venda-do-servidor', 'CARTAO', 6.25)
    await local.concluir('venda-do-servidor')
    expect(api.adicionarItem).toHaveBeenCalledWith('venda-do-servidor', cafe.id, 1, 0)
    expect(api.pagar).toHaveBeenCalledWith('venda-do-servidor', 'CARTAO', 6.25, undefined)
    expect(api.concluir).toHaveBeenCalledWith('venda-do-servidor')
    expect(await listarGestos()).toEqual([])

    rede(false)
    await expect(local.adicionarItem('venda-do-servidor', cafe, 1, 0))
      .rejects.toThrow('continua quando a rede voltar')
    await expect(local.consultar('venda-do-servidor')).rejects.toThrow('continua quando a rede voltar')
    expect(await local.conciliacoesPix()).toEqual([])
    expect(await listarGestos()).toEqual([])
  })

  it('mantém no dispositivo a Venda nova de uma sessão com gesto pendente, mesmo com rede', async () => {
    entrar(operadora)
    rede(true)
    const sessaoId = crypto.randomUUID()
    const aberta: SessaoCaixa = {
      id: sessaoId, usuarioId: operadora.usuarioId, versao: 2, valorAbertura: 10,
      valorFechamentoEsperado: 10, valorFechamentoContado: null, diferenca: null,
      abertaEm: new Date().toISOString(), fechadaEm: null, status: 'ABERTA',
      movimentos: [],
    }
    const caixaLocal = criarCaixaLocal({ ...caixa, abertaDoOperadorAtual: vi.fn(async () => aberta),
      consultar: vi.fn(async () => aberta) })
    await caixaLocal.abertaDoOperadorAtual()
    rede(false)
    await caixaLocal.suprir(sessaoId, 5, 'Troco')

    rede(true)
    const api = apiDeVendas()
    const local = criarVendaLocal(api, caixaLocal)
    const { id } = await local.iniciar(sessaoId)
    expect(api.iniciar).not.toHaveBeenCalled()
    expect(doTipo(await listarGestos(), 'venda.iniciar')).toMatchObject({ registroId: id, dependeDe: [] })
    await local.adicionarItem(id, cafe, 1, 0)
    expect(api.adicionarItem).not.toHaveBeenCalled()
  })

  it('não mostra nem continua a Venda de outra Conta', async () => {
    rede(false)
    entrar(operadora)
    await prepararCadastro()
    const caixaLocal = criarCaixaLocal()
    const { id: sessaoId } = await caixaLocal.abrir(20)
    const local = criarVendaLocal(apiDeVendas(), caixaLocal)
    const { id } = await local.iniciar(sessaoId)
    await local.adicionarItem(id, cafe, 1, 0)

    entrar({ ...operadora, contaId: 'conta-b', nomeNegocio: 'Loja da Esquina' })
    expect(await local.daSessao(sessaoId)).toEqual([])
    await expect(local.consultar(id)).rejects.toThrow('continua quando a rede voltar')
    await expect(local.adicionarItem(id, cafe, 1, 0)).rejects.toThrow('continua quando a rede voltar')
    expect(await listarGestos()).toEqual([])

    entrar(operadora)
    expect((await local.consultar(id)).itens).toHaveLength(1)
  })

  it('deixa de marcar como pendente a Venda cujos gestos tiveram resultado', async () => {
    entrar(operadora)
    rede(false)
    await prepararCadastro()
    const api = apiDeVendas()
    const caixaLocal = criarCaixaLocal()
    const { id: sessaoId } = await caixaLocal.abrir(20)
    const local = criarVendaLocal(api, caixaLocal)
    const { id } = await local.iniciar(sessaoId)
    await local.adicionarItem(id, cafe, 1, 0)
    await local.pagar(id, 'DINHEIRO', 6.25, 10)
    await local.concluir(id)

    await enviarFila(async (operacoes: OperacaoDoLote[]) => operacoes.map((operacao) => ({
      operacaoId: operacao.operacaoId, resultado: 'APLICADA' as const })))

    expect(await local.consultar(id)).toMatchObject({ status: 'CONCLUIDA', pendenteSincronizacao: false })
    expect(await local.comprovante(id)).toMatchObject({ valorTotal: 6.25, pendenteSincronizacao: false })
    expect(await local.daSessao(sessaoId)).toEqual([expect.objectContaining({ id,
      pendenteSincronizacao: false })])
    expect(await caixaLocal.consultar(sessaoId)).toMatchObject({ valorFechamentoEsperado: 26.25,
      pendenteSincronizacao: false })
    expect(api.comprovante).not.toHaveBeenCalled()
  })
})
