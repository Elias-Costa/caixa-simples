import 'fake-indexeddb/auto'
import { deleteDB } from 'idb'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { caixa, type SessaoCaixa } from '../api/caixa'
import { SemConexao } from '../api/cliente'
import type { OperacaoDoLote } from '../api/sincronizacao'
import { hojeNoBalcao } from '../dataDoBalcao'
import { gravarIdentidade, gravarToken } from '../sessao/armazenamento'
import type { Identidade } from '../sessao/Identidade'
import { tokenComExpiracao } from '../sessao/tokenDeTeste'
import { criarCaixaLocal } from './caixaLocal'
import { enviarFila } from './envio'
import { listarGestos } from './fila'
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
