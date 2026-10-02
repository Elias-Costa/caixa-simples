import 'fake-indexeddb/auto'
import { deleteDB } from 'idb'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { Produto } from '../api/cadastro'
import { caixa, type MovimentoCaixa, type SessaoCaixa } from '../api/caixa'
import { SemConexao } from '../api/cliente'
import type { OperacaoDoLote } from '../api/sincronizacao'
import { vendas, type Venda } from '../api/vendas'
import { hojeNoBalcao } from '../dataDoBalcao'
import { gravarIdentidade, gravarToken } from '../sessao/armazenamento'
import type { Identidade } from '../sessao/Identidade'
import { tokenComExpiracao } from '../sessao/tokenDeTeste'
import { criarCaixaLocal, mesclarSessoesLidas } from './caixaLocal'
import { enviarFila } from './envio'
import { lerCaixaNoAparelho, listarGestos, type GestoNaFila } from './fila'
import { criarVendaLocal } from './vendaLocal'

const ana: Identidade = {
  contaId: 'conta-a', usuarioId: 'ana', nome: 'Ana', nomeNegocio: 'Café A',
  perfil: 'OPERADOR', estoqueHabilitado: false,
}

function entrar(identidade: Identidade) {
  gravarToken(tokenComExpiracao(new Date(Date.now() + 60 * 60 * 1000)))
  gravarIdentidade(identidade)
}

function rede(online: boolean) {
  vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(online)
}

/** O servidor confirma cada operação do lote, com a revisão dada. */
function aplicadas(versao: number) {
  return async (operacoes: OperacaoDoLote[]) => operacoes.map((operacao) => ({
    operacaoId: operacao.operacaoId, resultado: 'APLICADA' as const, versao }))
}

/** A mesma sessão no servidor antes e depois de uma sangria de 5,00 enviada pelo dispositivo. */
function sessoesDoServidor(): { antes: SessaoCaixa; depois: SessaoCaixa } {
  const agora = new Date().toISOString()
  const antes: SessaoCaixa = {
    id: crypto.randomUUID(), usuarioId: ana.usuarioId, versao: 0, valorAbertura: 20,
    valorFechamentoEsperado: 20, valorFechamentoContado: null, diferenca: null, abertaEm: agora,
    fechadaEm: null, status: 'ABERTA', movimentos: [],
  }
  return { antes, depois: { ...antes, versao: 1, valorFechamentoEsperado: 15, movimentos: [{
    id: crypto.randomUUID(), tipo: 'SANGRIA', valor: 5, motivo: 'Retirada', vendaId: null,
    recebimentoId: null, criadoEm: agora }] } }
}

const cafe: Produto = {
  id: '00000000-0000-4000-8000-000000000001', versao: 1, tipo: 'PRODUTO', nome: 'Café', preco: 6.25,
  codigo: null, categoria: null, unidade: null, atributos: {},
}

/** Uma sessão ABERTA de Ana no servidor, com o extrato completo. */
function abertaNoServidor(valorAbertura: number): SessaoCaixa {
  return {
    id: crypto.randomUUID(), usuarioId: ana.usuarioId, versao: 0, valorAbertura,
    valorFechamentoEsperado: valorAbertura, valorFechamentoContado: null, diferenca: null,
    abertaEm: new Date().toISOString(), fechadaEm: null, status: 'ABERTA', movimentos: [],
  }
}

/** O que a consulta da sessão aberta devolve: o resumo, sem o extrato. */
function resumo({ movimentos: _movimentos, ...sessao }: SessaoCaixa): SessaoCaixa {
  return sessao
}

/** A consulta da sessão aberta no servidor, que pode passar a responder que não há nenhuma. */
function abertaRemota(sessao: SessaoCaixa) {
  return vi.fn(async (): Promise<SessaoCaixa | undefined> => resumo(sessao))
}

function movimento(tipo: MovimentoCaixa['tipo'], valor: number, vendaId: string | null = null): MovimentoCaixa {
  return { id: crypto.randomUUID(), tipo, valor, motivo: tipo === 'SANGRIA' ? 'Depósito' : null, vendaId,
    recebimentoId: null, criadoEm: new Date().toISOString() }
}

function apiDeVendas() {
  return { ...vendas, consultar: vi.fn() }
}

/** A Venda de 12,50 do dispositivo como o servidor a devolve depois de aplicar os gestos dela. */
function vendaDoServidor(id: string, sessaoCaixaId: string): Venda {
  return {
    id, sessaoCaixaId, usuarioId: ana.usuarioId, status: 'CONCLUIDA', total: 12.5,
    criadoEm: new Date().toISOString(), clienteId: null, saldoDevedor: 0, descontoDaVenda: 0, pago: 12.5,
    faltaPagar: 0, recebimentos: [],
    itens: [{ id: crypto.randomUUID(), produtoId: cafe.id, nome: 'Café', quantidade: 2, precoUnitario: 6.25,
      desconto: 0, subtotal: 12.5 }],
    parcelas: [
      { id: crypto.randomUUID(), forma: 'DINHEIRO', valor: 10, status: 'CONFIRMADO', troco: 10, pix: null },
      { id: crypto.randomUUID(), forma: 'CARTAO', valor: 2.5, status: 'CONFIRMADO', troco: 0, pix: null },
    ],
  }
}

function doTipo(gestos: GestoNaFila[], tipo: string): GestoNaFila {
  const encontrado = gestos.find((gesto) => gesto.tipo === tipo)
  if (!encontrado) throw new Error(`Gesto ${tipo} não está na fila`)
  return encontrado
}

/**
 * Com o caixa de 20,00 lido do servidor, conclui sem rede uma Venda de 12,50 paga com 10,00 em
 * dinheiro (20,00 entregues, 10,00 de troco) e 2,50 no cartão, e envia a fila.
 */
async function vendaEmDinheiroEnviada() {
  entrar(ana)
  rede(true)
  const sessao = abertaNoServidor(20)
  const remoto = { ...caixa, consultar: vi.fn(async () => sessao) }
  const local = criarCaixaLocal(remoto)
  await local.consultar(sessao.id)

  rede(false)
  const api = apiDeVendas()
  const vendasLocais = criarVendaLocal(api, local)
  const { id: vendaId } = await vendasLocais.iniciar(sessao.id)
  await vendasLocais.adicionarItem(vendaId, cafe, 2, 0)
  expect(await vendasLocais.pagar(vendaId, 'DINHEIRO', 10, 20)).toEqual({ troco: 10 })
  await vendasLocais.pagar(vendaId, 'CARTAO', 2.5)
  await vendasLocais.concluir(vendaId)
  // Só o dinheiro entra na gaveta: 20 de abertura mais 10; o troco e o cartão ficam de fora.
  expect(await local.consultar(sessao.id)).toMatchObject({ valorFechamentoEsperado: 30 })
  await enviarFila(aplicadas(1))
  api.consultar.mockResolvedValue(vendaDoServidor(vendaId, sessao.id))
  return { sessao, remoto, local, vendasLocais, vendaId }
}

beforeEach(async () => { await deleteDB('caixa-simples-offline') })
afterEach(() => vi.restoreAllMocks())

describe('SessaoCaixa local', () => {
  it('preserva abertura, movimentos, conferência e fechamento após recarga, em ordem', async () => {
    entrar(ana)
    rede(false)
    const remoto = {
      ...caixa, abertaDoOperadorAtual: vi.fn(), historico: vi.fn(), consultar: vi.fn(),
      abrir: vi.fn(), sangrar: vi.fn(), suprir: vi.fn(), fechar: vi.fn(),
    }
    const local = criarCaixaLocal(remoto)
    const { id } = await local.abrir(20)
    expect((await local.abertaDoOperadorAtual())?.id).toBe(id)
    await local.sangrar(id, 5, 'Retirada')
    await local.suprir(id, 3, 'Troco')

    const recarregado = criarCaixaLocal(remoto)
    expect(await recarregado.consultar(id)).toMatchObject({
      status: 'ABERTA', versao: 2, valorAbertura: 20, valorFechamentoEsperado: 18,
      pendenteSincronizacao: true, movimentos: [{ tipo: 'SANGRIA', valor: 5 },
        { tipo: 'SUPRIMENTO', valor: 3 }],
    })
    expect((await recarregado.historico(hojeNoBalcao())).map((sessao) => sessao.id)).toContain(id)
    expect(await recarregado.fechar(id, 16)).toEqual({ diferenca: 2 })
    expect(await criarCaixaLocal(remoto).consultar(id)).toMatchObject({
      status: 'FECHADA', versao: 3, valorFechamentoEsperado: 18,
      valorFechamentoContado: 16, diferenca: 2, pendenteSincronizacao: true,
    })
    expect(await recarregado.abertaDoOperadorAtual()).toBeUndefined()
    const gestos = await listarGestos()
    expect(gestos.map((gesto) => gesto.tipo).sort()).toEqual([
      'caixa.abrir', 'caixa.fechar', 'caixa.sangrar', 'caixa.suprir',
    ])
    const abertura = gestos.find((gesto) => gesto.tipo === 'caixa.abrir')!
    const sangria = gestos.find((gesto) => gesto.tipo === 'caixa.sangrar')!
    const suprimento = gestos.find((gesto) => gesto.tipo === 'caixa.suprir')!
    const fechamento = gestos.find((gesto) => gesto.tipo === 'caixa.fechar')!
    expect(sangria.dependeDe).toEqual([abertura.operacaoId])
    expect(sangria.versaoBase).toBe(0)
    expect(suprimento.dependeDe).toEqual([sangria.operacaoId])
    expect(suprimento.versaoBase).toBe(1)
    expect(fechamento.dependeDe).toEqual([abertura, sangria, suprimento].map((gesto) => gesto.operacaoId))
    expect(fechamento.versaoBase).toBe(2)
    expect(remoto.abrir).not.toHaveBeenCalled()
    expect(remoto.fechar).not.toHaveBeenCalled()
  })

  it('conta a Venda em dinheiro concluída no dispositivo e a põe antes da sangria e do fechamento', async () => {
    entrar(ana)
    rede(false)
    const local = criarCaixaLocal()
    const { id } = await local.abrir(20)
    const vendasLocais = criarVendaLocal(undefined, local)
    const venda = await vendasLocais.iniciar(id)
    await vendasLocais.adicionarItem(venda.id, { id: '00000000-0000-4000-8000-000000000001', versao: 1,
      tipo: 'PRODUTO', nome: 'Café', preco: 6.25, codigo: null, categoria: null, unidade: null,
      atributos: {} }, 2, 0)
    await vendasLocais.pagar(venda.id, 'DINHEIRO', 10, 10)
    await vendasLocais.pagar(venda.id, 'CARTAO', 2.5)
    await vendasLocais.concluir(venda.id)

    // Só o dinheiro entra na gaveta: 20 de abertura mais 10 da Venda; o cartão fica de fora.
    expect(await local.consultar(id)).toMatchObject({ valorFechamentoEsperado: 30, versao: 1,
      pendenteSincronizacao: true, movimentos: [{ tipo: 'VENDA', valor: 10, vendaId: venda.id }] })
    // A sangria de 25 só cabe por causa da Venda, e por isso depende da conclusão dela.
    await local.sangrar(id, 25, 'Depósito')
    expect(await local.fechar(id, 5)).toEqual({ diferenca: 0 })

    const gestos = await listarGestos()
    const conclusao = gestos.find((gesto) => gesto.tipo === 'venda.concluir')!
    const sangria = gestos.find((gesto) => gesto.tipo === 'caixa.sangrar')!
    const fechamento = gestos.find((gesto) => gesto.tipo === 'caixa.fechar')!
    expect(sangria).toMatchObject({ dependeDe: [conclusao.operacaoId], versaoBase: 1 })
    expect(fechamento.versaoBase).toBe(2)
    expect([...fechamento.dependeDe].sort()).toEqual(gestos
      .filter((gesto) => gesto.tipo !== 'caixa.fechar').map((gesto) => gesto.operacaoId).sort())
    expect(await criarCaixaLocal().consultar(id)).toMatchObject({ status: 'FECHADA',
      valorFechamentoEsperado: 5, valorFechamentoContado: 5, diferenca: 0, versao: 3 })
  })

  it('recusa estados inválidos antes de gravar gesto', async () => {
    entrar(ana)
    rede(false)
    const local = criarCaixaLocal()
    const { id } = await local.abrir(10)
    await expect(local.abrir(0)).rejects.toThrow('Já existe')
    await expect(local.sangrar(id, 11, 'Retirada')).rejects.toThrow('maior')
    await expect(local.suprir(id, 2, ' ')).rejects.toThrow('Motivo')
    await expect(local.fechar(id, -1)).rejects.toThrow('não negativo')
    await local.fechar(id, 9)
    await expect(local.fechar(id, 9)).rejects.toThrow('já fechada')
    await expect(local.suprir(id, 1, 'Troco')).rejects.toThrow('fechada')
    expect((await listarGestos()).map((gesto) => gesto.tipo).sort()).toEqual(['caixa.abrir', 'caixa.fechar'])
  })

  it('isola sessões e gestos entre Contas e usuários, inclusive após novo login', async () => {
    rede(false)
    entrar(ana)
    const local = criarCaixaLocal()
    const primeiro = await local.abrir(20)

    entrar({ ...ana, contaId: 'conta-b' })
    expect(await local.abertaDoOperadorAtual()).toBeUndefined()
    await expect(local.consultar(primeiro.id)).rejects.toThrow('não encontrada')
    const segundo = await local.abrir(30)
    expect((await listarGestos()).map((gesto) => gesto.registroId)).toEqual([segundo.id])

    entrar({ ...ana, usuarioId: 'outra' })
    expect(await local.abertaDoOperadorAtual()).toBeUndefined()
    entrar(ana)
    expect((await local.abertaDoOperadorAtual())?.id).toBe(primeiro.id)
    expect((await listarGestos()).map((gesto) => gesto.registroId)).toEqual([primeiro.id])
  })

  it('usa extrato guardado para continuar offline e não duplica gesto em falha incerta de escrita online', async () => {
    entrar(ana)
    rede(true)
    const id = crypto.randomUUID()
    const aberta: SessaoCaixa = {
      id, usuarioId: ana.usuarioId, versao: 4, valorAbertura: 10, valorFechamentoEsperado: 12,
      valorFechamentoContado: null, diferenca: null, abertaEm: new Date().toISOString(),
      fechadaEm: null, status: 'ABERTA', movimentos: [{ id: crypto.randomUUID(),
        tipo: 'VENDA', valor: 2, motivo: null, vendaId: crypto.randomUUID(),
        recebimentoId: null, criadoEm: new Date().toISOString() }],
    }
    const remoto = { ...caixa,
      abertaDoOperadorAtual: vi.fn(async () => aberta),
      historico: vi.fn(async () => [aberta]),
      consultar: vi.fn(async () => aberta),
      abrir: vi.fn(async () => { throw new SemConexao() }),
      sangrar: vi.fn(async () => { throw new SemConexao() }),
    }
    const local = criarCaixaLocal(remoto)
    await local.abertaDoOperadorAtual()
    await local.consultar(id)
    await expect(local.sangrar(id, 1, 'Retirada')).rejects.toBeInstanceOf(SemConexao)
    expect(await listarGestos()).toEqual([])

    rede(false)
    await local.sangrar(id, 1, 'Retirada')
    expect(await criarCaixaLocal(remoto).consultar(id)).toMatchObject({
      versao: 5, valorFechamentoEsperado: 11,
      movimentos: [{ tipo: 'VENDA' }, { tipo: 'SANGRIA' }],
    })
    expect((await listarGestos())[0].versaoBase).toBe(4)
  })

  it('opera e fecha sem rede a sessão cujo esperado ficou negativo no servidor', async () => {
    entrar(ana)
    rede(true)
    const id = crypto.randomUUID()
    const cancelada = crypto.randomUUID()
    const agora = new Date().toISOString()
    // Venda em dinheiro, sangria do valor dela e o estorno do cancelamento: o servidor aceita o
    // esperado negativo que sobra, e é um suprimento que o corrige.
    const aberta: SessaoCaixa = {
      id, usuarioId: ana.usuarioId, versao: 3, valorAbertura: 0, valorFechamentoEsperado: -10,
      valorFechamentoContado: null, diferenca: null, abertaEm: agora, fechadaEm: null, status: 'ABERTA',
      movimentos: [
        { id: crypto.randomUUID(), tipo: 'VENDA', valor: 10, motivo: null, vendaId: cancelada,
          recebimentoId: null, criadoEm: agora },
        { id: crypto.randomUUID(), tipo: 'SANGRIA', valor: 10, motivo: 'Depósito', vendaId: null,
          recebimentoId: null, criadoEm: agora },
        { id: crypto.randomUUID(), tipo: 'ESTORNO', valor: 10, motivo: null, vendaId: cancelada,
          recebimentoId: null, criadoEm: agora },
      ],
    }
    const remoto = { ...caixa, consultar: vi.fn(async () => aberta) }
    const local = criarCaixaLocal(remoto)
    await local.consultar(id)

    rede(false)
    await local.suprir(id, 4, 'Troco')
    // Como no servidor, a sangria que deixaria o esperado negativo continua recusada.
    await expect(local.sangrar(id, 1, 'Retirada')).rejects.toThrow('maior que o saldo esperado')
    const vendas = criarVendaLocal(undefined, local)
    const venda = await vendas.iniciar(id)
    await vendas.adicionarItem(venda.id, { id: '00000000-0000-4000-8000-000000000001', versao: 1,
      tipo: 'PRODUTO', nome: 'Café', preco: 2.5, codigo: null, categoria: null, unidade: null,
      atributos: {} }, 1, 0)
    await vendas.pagar(venda.id, 'DINHEIRO', 2.5, 2.5)
    await vendas.concluir(venda.id)
    expect(await criarCaixaLocal(remoto).consultar(id)).toMatchObject({
      status: 'ABERTA', valorFechamentoEsperado: -3.5, versao: 5,
    })

    expect(await local.fechar(id, 0)).toEqual({ diferenca: -3.5 })
    expect(await criarCaixaLocal(remoto).consultar(id)).toMatchObject({
      status: 'FECHADA', valorFechamentoEsperado: -3.5, valorFechamentoContado: 0, diferenca: -3.5,
      versao: 6,
    })
    expect(remoto.consultar).toHaveBeenCalledTimes(1)
  })

  it('não dobra no esperado a sangria enviada quando o retrato do servidor já a contém', async () => {
    entrar(ana)
    rede(true)
    const { antes, depois } = sessoesDoServidor()
    const remoto = { ...caixa, consultar: vi.fn(async () => antes) }
    const local = criarCaixaLocal(remoto)
    await local.consultar(antes.id)

    rede(false)
    await local.sangrar(antes.id, 5, 'Retirada')
    expect(await enviarFila(aplicadas(1))).toMatchObject({ resolvidos: 1 })
    // Confirmada, mas o retrato guardado é de antes dela: continua somada, com a revisão do servidor.
    expect(await local.consultar(antes.id)).toMatchObject({ valorFechamentoEsperado: 15, versao: 1,
      pendenteSincronizacao: false })

    rede(true)
    remoto.consultar.mockResolvedValue(depois)
    expect(await local.consultar(antes.id)).toMatchObject({ valorFechamentoEsperado: 15 })
    rede(false)
    expect(await criarCaixaLocal(remoto).consultar(antes.id)).toMatchObject({
      valorFechamentoEsperado: 15, versao: 1, movimentos: [{ tipo: 'SANGRIA', valor: 5 }],
    })
  })

  it('não guarda a leitura feita com a sangria incerta, e a conta segue certa depois do reenvio', async () => {
    entrar(ana)
    rede(true)
    const { antes, depois } = sessoesDoServidor()
    const remoto = { ...caixa, consultar: vi.fn(async () => antes), historico: vi.fn(async () => [depois]) }
    const local = criarCaixaLocal(remoto)
    await local.consultar(antes.id)

    rede(false)
    await local.sangrar(antes.id, 5, 'Retirada')
    // A resposta se perde: o servidor pode ter aplicado a sangria, e o histórico já a mostra.
    await enviarFila(async () => { throw new SemConexao() })
    rede(true)
    await local.historico(hojeNoBalcao())
    await enviarFila(aplicadas(1))

    rede(false)
    expect(await criarCaixaLocal(remoto).consultar(antes.id)).toMatchObject({
      valorFechamentoEsperado: 15, versao: 1, movimentos: [{ tipo: 'SANGRIA', valor: 5 }],
    })
  })
})

describe('SessaoCaixa local diante do que o servidor já sabe', () => {
  it('mantém na gaveta a Venda em dinheiro lida do servidor antes do caixa', async () => {
    const { sessao, remoto, local, vendasLocais, vendaId } = await vendaEmDinheiroEnviada()

    // Com rede, só a Venda é lida; o retrato do caixa ainda é o de antes dela.
    rede(true)
    await vendasLocais.consultar(vendaId)

    rede(false)
    expect(await criarCaixaLocal(remoto).consultar(sessao.id)).toMatchObject({
      valorFechamentoEsperado: 30, movimentos: [{ tipo: 'VENDA', valor: 10, vendaId }],
    })
    // A sangria de 25 só cabe por causa da Venda, e continua dependendo da conclusão dela.
    await local.sangrar(sessao.id, 25, 'Depósito')
    const gestos = await listarGestos()
    expect(doTipo(gestos, 'caixa.sangrar').dependeDe).toEqual([doTipo(gestos, 'venda.concluir').operacaoId])
    expect(await local.consultar(sessao.id)).toMatchObject({ valorFechamentoEsperado: 5 })

    // Com a sangria enviada, o caixa lido do servidor já tem as duas, e nada conta duas vezes.
    await enviarFila(aplicadas(2))
    rede(true)
    remoto.consultar.mockResolvedValue({ ...sessao, versao: 2, valorFechamentoEsperado: 5,
      movimentos: [movimento('VENDA', 10, vendaId), movimento('SANGRIA', 25)] })
    await local.consultar(sessao.id)
    rede(false)
    expect(await criarCaixaLocal(remoto).consultar(sessao.id)).toMatchObject({
      valorFechamentoEsperado: 5, versao: 2, pendenteSincronizacao: false,
    })
    expect(await listarGestos()).toEqual([])
  })

  it('não dobra a Venda em dinheiro quando o caixa é lido do servidor antes dela', async () => {
    const { sessao, remoto, local, vendasLocais, vendaId } = await vendaEmDinheiroEnviada()

    rede(true)
    remoto.consultar.mockResolvedValue({ ...sessao, versao: 1, valorFechamentoEsperado: 30,
      movimentos: [movimento('VENDA', 10, vendaId)] })
    await local.consultar(sessao.id)
    rede(false)
    expect(await criarCaixaLocal(remoto).consultar(sessao.id)).toMatchObject({
      valorFechamentoEsperado: 30, versao: 1, movimentos: [{ tipo: 'VENDA' }],
    })

    rede(true)
    await vendasLocais.consultar(vendaId)
    rede(false)
    expect(await criarCaixaLocal(remoto).consultar(sessao.id)).toMatchObject({
      valorFechamentoEsperado: 30, versao: 1, movimentos: [{ tipo: 'VENDA' }],
    })
    await local.sangrar(sessao.id, 25, 'Depósito')
    expect(await local.consultar(sessao.id)).toMatchObject({ valorFechamentoEsperado: 5, versao: 2 })
    // Os dois retratos contêm a Venda: os gestos dela saem da fila, e a sangria fica para envio.
    expect((await listarGestos()).map((gesto) => gesto.tipo)).toEqual(['caixa.sangrar'])
  })

  it('guarda as duas sessões lidas ao mesmo tempo, sem uma apagar a outra', async () => {
    entrar(ana)
    rede(true)
    const ontem = { ...abertaNoServidor(10), status: 'FECHADA' as const, valorFechamentoContado: 10,
      diferenca: 0, fechadaEm: new Date().toISOString() }
    const hoje = abertaNoServidor(20)
    const remoto = { ...caixa, consultar: vi.fn(async (id: string) => id === ontem.id ? ontem : hoje) }
    const local = criarCaixaLocal(remoto)
    await Promise.all([local.consultar(ontem.id), local.consultar(hoje.id)])

    rede(false)
    expect(await local.consultar(ontem.id)).toMatchObject({ status: 'FECHADA', valorFechamentoContado: 10 })
    expect(await local.consultar(hoje.id)).toMatchObject({ status: 'ABERTA', valorFechamentoEsperado: 20 })
  })

  it('não traz de volta o caixa fechado em outro aparelho, e outro abre sem rede', async () => {
    entrar(ana)
    rede(true)
    const sessao = abertaNoServidor(20)
    const remoto = { ...caixa, abertaDoOperadorAtual: abertaRemota(sessao),
      consultar: vi.fn(async () => sessao) }
    const local = criarCaixaLocal(remoto)
    expect((await local.abertaDoOperadorAtual())?.id).toBe(sessao.id)
    await local.consultar(sessao.id)

    remoto.abertaDoOperadorAtual.mockResolvedValue(undefined)
    expect(await local.abertaDoOperadorAtual()).toBeUndefined()

    rede(false)
    expect(await criarCaixaLocal(remoto).abertaDoOperadorAtual()).toBeUndefined()
    await expect(local.sangrar(sessao.id, 1, 'Retirada')).rejects.toThrow('fechada')
    await expect(local.fechar(sessao.id, 20)).rejects.toThrow('já fechada')
    await expect(criarVendaLocal(apiDeVendas(), local).iniciar(sessao.id)).rejects.toThrow('não está ABERTA')
    // Sem o fechamento lido, a sessão aparece fechada, sem hora, valor contado ou diferença inventados.
    expect(await local.consultar(sessao.id)).toMatchObject({ status: 'FECHADA', fechadaEm: null,
      valorFechamentoContado: null, diferenca: null, fechamentoSemValores: true, valorFechamentoEsperado: 20 })
    expect(await listarGestos()).toEqual([])
    const { id } = await local.abrir(10)
    expect((await local.abertaDoOperadorAtual())?.id).toBe(id)
  })

  it('mostra o fechamento lido do servidor no lugar da sessão marcada como fechada', async () => {
    entrar(ana)
    rede(true)
    const sessao = abertaNoServidor(20)
    const remoto = { ...caixa, abertaDoOperadorAtual: abertaRemota(sessao),
      consultar: vi.fn(async () => sessao) }
    const local = criarCaixaLocal(remoto)
    await local.consultar(sessao.id)
    remoto.abertaDoOperadorAtual.mockResolvedValue(undefined)
    await local.abertaDoOperadorAtual()
    expect((await lerCaixaNoAparelho()).fechadasNoServidor).toEqual([sessao.id])

    const fechadaEm = new Date().toISOString()
    remoto.consultar.mockResolvedValue({ ...sessao, versao: 1, status: 'FECHADA', fechadaEm,
      valorFechamentoContado: 18, diferenca: 2 })
    await local.consultar(sessao.id)
    rede(false)
    const lida = await local.consultar(sessao.id)
    expect(lida).toMatchObject({ status: 'FECHADA', fechadaEm, valorFechamentoContado: 18, diferenca: 2 })
    expect(lida.fechamentoSemValores).toBeUndefined()
    expect((await lerCaixaNoAparelho()).fechadasNoServidor).toEqual([])
  })

  it('não traz de volta o caixa fechado com rede quando a rede cai logo depois', async () => {
    entrar(ana)
    rede(true)
    const sessao = abertaNoServidor(20)
    const remoto = { ...caixa, consultar: vi.fn(async () => sessao),
      fechar: vi.fn(async () => ({ diferenca: 0 })) }
    const local = criarCaixaLocal(remoto)
    await local.consultar(sessao.id)
    expect(await local.fechar(sessao.id, 20)).toEqual({ diferenca: 0 })

    rede(false)
    expect(await local.abertaDoOperadorAtual()).toBeUndefined()
    expect(await local.consultar(sessao.id)).toMatchObject({ status: 'FECHADA', fechamentoSemValores: true })
    await expect(local.fechar(sessao.id, 20)).rejects.toThrow('já fechada')
    expect(remoto.fechar).toHaveBeenCalledTimes(1)
    expect(await listarGestos()).toEqual([])
  })

  it('troca a sessão ABERTA guardada pela que o servidor diz aberta', async () => {
    entrar(ana)
    rede(true)
    const antiga = abertaNoServidor(20)
    const nova = abertaNoServidor(50)
    const remoto = { ...caixa, abertaDoOperadorAtual: abertaRemota(antiga),
      consultar: vi.fn(async () => antiga) }
    const local = criarCaixaLocal(remoto)
    await local.abertaDoOperadorAtual()
    await local.consultar(antiga.id)

    remoto.abertaDoOperadorAtual.mockResolvedValue(resumo(nova))
    expect((await local.abertaDoOperadorAtual())?.id).toBe(nova.id)
    rede(false)
    expect((await local.abertaDoOperadorAtual())?.id).toBe(nova.id)
    expect(await local.consultar(antiga.id)).toMatchObject({ status: 'FECHADA', fechamentoSemValores: true })
    await expect(local.sangrar(antiga.id, 1, 'Retirada')).rejects.toThrow('fechada')
  })

  it('tira de operação o caixa que o servidor fechou sem perder o gesto pendente dele', async () => {
    entrar(ana)
    rede(true)
    const sessao = abertaNoServidor(20)
    const remoto = { ...caixa, abertaDoOperadorAtual: abertaRemota(sessao),
      consultar: vi.fn(async () => sessao) }
    const local = criarCaixaLocal(remoto)
    await local.consultar(sessao.id)
    rede(false)
    await local.suprir(sessao.id, 5, 'Troco')

    // O suprimento ainda não saiu, e a sessão foi fechada em outro aparelho.
    rede(true)
    remoto.abertaDoOperadorAtual.mockResolvedValue(undefined)
    expect(await local.abertaDoOperadorAtual()).toBeUndefined()
    rede(false)
    expect(await local.abertaDoOperadorAtual()).toBeUndefined()
    expect(await local.consultar(sessao.id)).toMatchObject({ status: 'FECHADA', fechamentoSemValores: true,
      pendenteSincronizacao: true, valorFechamentoEsperado: 25, movimentos: [{ tipo: 'SUPRIMENTO', valor: 5 }] })
    expect(await listarGestos()).toEqual([expect.objectContaining({ tipo: 'caixa.suprir', estado: 'queued' })])
  })

  it('preserva a abertura feita sem rede que o servidor ainda não recebeu', async () => {
    entrar(ana)
    rede(false)
    const remoto = { ...caixa, abertaDoOperadorAtual: vi.fn(async () => undefined) }
    const local = criarCaixaLocal(remoto)
    const { id } = await local.abrir(15)

    rede(true)
    expect((await local.abertaDoOperadorAtual())?.id).toBe(id)
    rede(false)
    expect((await local.abertaDoOperadorAtual())?.id).toBe(id)
    expect(await listarGestos()).toEqual([expect.objectContaining({ tipo: 'caixa.abrir', estado: 'queued' })])
  })

  it('não traz de volta o caixa aberto sem rede, já enviado, que o servidor fechou', async () => {
    entrar(ana)
    rede(false)
    const remoto = { ...caixa, abertaDoOperadorAtual: vi.fn(async () => undefined) }
    const local = criarCaixaLocal(remoto)
    const { id } = await local.abrir(15)
    await enviarFila(aplicadas(0))

    // Antes de este aparelho reler a sessão, ela foi fechada em outro aparelho.
    rede(true)
    expect(await local.abertaDoOperadorAtual()).toBeUndefined()
    rede(false)
    expect(await local.abertaDoOperadorAtual()).toBeUndefined()
    expect(await local.consultar(id)).toMatchObject({ status: 'FECHADA', fechamentoSemValores: true,
      valorAbertura: 15 })
    await expect(local.sangrar(id, 1, 'Retirada')).rejects.toThrow('fechada')
  })
})

describe('mesclarSessoesLidas', () => {
  const guardada = { ...abertaNoServidor(20), ordemDaLeitura: 5 }

  it('não troca a sessão guardada por uma leitura mais antiga', () => {
    const lida = { ...guardada, versao: 3, valorFechamentoEsperado: 99 }
    expect(mesclarSessoesLidas({ sessoes: [guardada], fechadasNoServidor: [] }, [lida], 4, ana.usuarioId))
      .toEqual({ sessoes: [guardada], fechadasNoServidor: [] })
    expect(mesclarSessoesLidas({ sessoes: [guardada], fechadasNoServidor: [] }, [lida], 5, ana.usuarioId).sessoes)
      .toEqual([{ ...lida, ordemDaLeitura: 5 }])
  })

  it('não volta a ABERTA a sessão guardada FECHADA e tira a marca quando o fechamento é lido', () => {
    const fechada = { ...guardada, versao: 2, status: 'FECHADA' as const, fechadaEm: new Date().toISOString(),
      valorFechamentoContado: 20, diferenca: 0 }
    const comFechamento = mesclarSessoesLidas({ sessoes: [guardada], fechadasNoServidor: [guardada.id] },
      [fechada], 6, ana.usuarioId)
    expect(comFechamento).toEqual({ sessoes: [{ ...fechada, ordemDaLeitura: 6 }], fechadasNoServidor: [] })
    expect(mesclarSessoesLidas(comFechamento, [resumo(guardada)], 7, ana.usuarioId)).toEqual(comFechamento)
  })

  it('ignora a sessão de outro operador', () => {
    const deOutro = { ...abertaNoServidor(10), usuarioId: 'outra' }
    expect(mesclarSessoesLidas({ sessoes: [], fechadasNoServidor: [] }, [deOutro], 0, ana.usuarioId))
      .toEqual({ sessoes: [], fechadasNoServidor: [] })
  })
})
