import 'fake-indexeddb/auto'
import { deleteDB } from 'idb'
import { act, renderHook, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ErroDaApi, SemConexao } from '../api/cliente'
import { registrarResposta, registrarSemResposta } from '../api/conexao'
import { sincronizacao, type OperacaoDoLote, type RevisaoDaConta } from '../api/sincronizacao'
import { conferirGestos, enfileirarGesto, iniciarEnvio, listarGestos, registrarDesfechos, type GestoNaFila } from '../offline/fila'
import { gravarIdentidade, gravarToken, sessaoFoiEncerrada } from '../sessao/armazenamento'
import type { Identidade } from '../sessao/Identidade'
import { tokenComExpiracao } from '../sessao/tokenDeTeste'
import { INTERVALO_DA_RENOVACAO, INTERVALO_DA_SONDAGEM, useEnvioAutomatico } from './sincronizacao'

const ana: Identidade = {
  contaId: 'conta-a', usuarioId: 'ana', nome: 'Ana', nomeNegocio: 'Cafeteria Aurora',
  perfil: 'OPERADOR', estoqueHabilitado: false,
}

function rede(online: boolean) {
  vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(online)
}

async function gestoNovo() {
  return enfileirarGesto({ tipo: 'cliente.criar', registroId: crypto.randomUUID(),
    payload: { nome: 'Maria', contato: null } })
}

// Fora do renderHook, para serem as mesmas funções em toda renderização, como as do Shell.
const sairFalso = vi.fn()
const relerFalso = vi.fn(async () => undefined)

afterEach(() => vi.restoreAllMocks())
beforeEach(async () => {
  await deleteDB('ecrx-caixa-offline')
  gravarToken(tokenComExpiracao(new Date(Date.now() + 60 * 60 * 1000)))
  gravarIdentidade(ana)
})

describe('envio automático no shell', () => {
  it('conta a fila sem rede, envia sozinho quando a rede volta e avisa as telas', async () => {
    await gestoNovo()
    const enviarLote = vi.spyOn(sincronizacao, 'enviarLote').mockImplementation(
      async (operacoes: OperacaoDoLote[]) => operacoes.map((operacao) => ({
        operacaoId: operacao.operacaoId, resultado: 'APLICADA' as const })))
    rede(false)
    const { result } = renderHook(() => useEnvioAutomatico(ana, sairFalso, '/vender', relerFalso))

    await waitFor(() => expect(result.current.porEnviar).toBe(1))
    expect(enviarLote).not.toHaveBeenCalled()

    rede(true)
    window.dispatchEvent(new Event('online'))
    await waitFor(() => expect(result.current.porEnviar).toBe(0))
    expect(result.current.rodada).toBe(1)
    expect(enviarLote).toHaveBeenCalledTimes(1)

    // O gesto gravado com rede sai na hora, sem esperar a repetição.
    await gestoNovo()
    await waitFor(() => expect(enviarLote).toHaveBeenCalledTimes(2))
    await waitFor(() => expect(result.current.rodada).toBe(2))
  })

  it('encerra a sessão local quando o servidor recusa o token, e a fila fica', async () => {
    rede(true)
    vi.spyOn(sincronizacao, 'enviarLote').mockRejectedValue(new ErroDaApi(401, 'Não autorizado', undefined, {}))
    const sair = vi.fn()
    renderHook(() => useEnvioAutomatico(ana, sair, '/vender', relerFalso))
    await gestoNovo()

    await waitFor(() => expect(sair).toHaveBeenCalled())
    expect((await listarGestos()).map((gesto) => gesto.estado)).toEqual(['queued'])
    // O login avisa que a sessão foi encerrada e que o registrado sem rede continua guardado.
    expect(sessaoFoiEncerrada()).toBe(true)
  })
})

describe('o número do cabeçalho', () => {
  const VENDA = '00000000-0000-4000-8000-000000000011'
  const SESSAO = '00000000-0000-4000-8000-000000000021'
  const dona: Identidade = { ...ana, perfil: 'ADMIN' }

  /** Os gestos de uma venda, como o balcão os grava sem rede. */
  async function vendaNaFila(vendaId = VENDA) {
    const inicio = await enfileirarGesto({ tipo: 'venda.iniciar', registroId: vendaId,
      payload: { sessaoCaixaId: SESSAO, criadoEm: new Date().toISOString() } })
    const item = await enfileirarGesto({ tipo: 'venda.adicionarItem', registroId: vendaId, dependeDe: [inicio.operacaoId],
      payload: { itemId: 'item', produtoId: 'produto', nome: 'Sonho', quantidade: 1, precoUnitario: 5, desconto: 0, versaoProduto: 1 } })
    const parcela = await enfileirarGesto({ tipo: 'venda.registrarPagamento', registroId: vendaId, dependeDe: [item.operacaoId],
      payload: { pagamentoId: 'parcela', forma: 'DINHEIRO', valor: 5, valorRecebido: 5 } })
    const conclusao = await enfileirarGesto({ tipo: 'venda.concluir', registroId: vendaId, dependeDe: [parcela.operacaoId],
      payload: { concluidoEm: new Date().toISOString() } })
    return [inicio, item, parcela, conclusao]
  }

  /** A venda toda recusada, gesto a gesto, como a cascata faz. */
  async function vendaRecusada(vendaId = VENDA) {
    const gestos = await vendaNaFila(vendaId)
    await iniciarEnvio(gestos.map((gesto) => gesto.operacaoId))
    await registrarDesfechos(gestos.map((gesto) => ({ operacaoId: gesto.operacaoId, estado: 'needs_review' as const,
      resultado: { aplicada: false, detalhe: 'O caixa foi fechado.' } })))
    return gestos
  }

  function naLista(gesto: GestoNaFila): RevisaoDaConta {
    return { operacaoId: gesto.operacaoId, usuarioId: 'bruno', tipo: gesto.tipo, registroId: gesto.registroId,
      payload: gesto.payload, criadaEm: gesto.criadoEm, recebidaEm: gesto.criadoEm, resultado: 'NAO_APLICADA' }
  }

  it('o por enviar conta registros: a venda inteira conta um, e cada movimento de caixa, outro', async () => {
    rede(false)
    await vendaNaFila()
    await enfileirarGesto({ tipo: 'caixa.sangrar', registroId: SESSAO, payload: { valor: 5, motivo: 'Cofre' } })
    await enfileirarGesto({ tipo: 'caixa.suprir', registroId: SESSAO, payload: { valor: 9, motivo: 'Troco' } })
    await gestoNovo()

    const { result } = renderHook(() => useEnvioAutomatico(ana, sairFalso, '/vender', relerFalso))

    await waitFor(() => expect(result.current.porEnviar).toBe(4))
  })

  it('o em revisão conta registros, e não os gestos da mesma venda', async () => {
    rede(false)
    await vendaRecusada()

    const { result } = renderHook(() => useEnvioAutomatico(ana, sairFalso, '/vender', relerFalso))

    await waitFor(() => expect(result.current.emRevisao).toBe(1))
  })

  it('no administrador, conta os registros da lista da Conta, e o do aparelho não soma de novo', async () => {
    gravarIdentidade(dona)
    rede(true)
    const propria = await vendaRecusada()
    const deOutro = crypto.randomUUID()
    const revisoes = vi.spyOn(sincronizacao, 'revisoes').mockResolvedValue({ conferidas: [], pendentes: [
      ...propria.map(naLista),
      { ...naLista(propria[0]), operacaoId: crypto.randomUUID(), registroId: deOutro },
      { ...naLista(propria[1]), operacaoId: crypto.randomUUID(), registroId: deOutro },
    ] })

    const { result } = renderHook(() => useEnvioAutomatico(dona, sairFalso, '/vender', relerFalso))

    await waitFor(() => expect(revisoes).toHaveBeenCalled())
    await waitFor(() => expect(result.current.emRevisao).toBe(2))
    // A rodada de envio da montagem ainda corre. Terminada depois do teste, ela releria a lista da Conta já
    // sem o simulado, e a recusa da chamada de verdade deixaria o teste seguinte sem servidor.
    await waitFor(() => expect(result.current.enviando).toBe(false))
  })

  it('no administrador, o que foi conferido só neste aparelho não baixa o número, porque continua na lista', async () => {
    gravarIdentidade(dona)
    rede(true)
    const gestos = await vendaRecusada()
    vi.spyOn(sincronizacao, 'revisoes').mockResolvedValue({ conferidas: [], pendentes: gestos.map(naLista) })
    await conferirGestos(gestos.map((gesto) => gesto.operacaoId))

    const { result } = renderHook(() => useEnvioAutomatico(dona, sairFalso, '/vender', relerFalso))

    await waitFor(() => expect(result.current.emRevisao).toBe(1))
    // A faixa conta o mesmo: a venda está na lista, então ainda falta conferir.
    await waitFor(() => expect(result.current.vendasRecusadas[0]?.conferida).toBe(false))
    // Como no teste acima, a rodada da montagem termina antes do fim deste.
    await waitFor(() => expect(result.current.enviando).toBe(false))
  })

  it('no administrador sem rede e sem leitura nenhuma, conta os registros em revisão guardados no aparelho', async () => {
    gravarIdentidade(dona)
    rede(false)
    await vendaRecusada()
    const revisoes = vi.spyOn(sincronizacao, 'revisoes')

    const { result } = renderHook(() => useEnvioAutomatico(dona, sairFalso, '/vender', relerFalso))

    await waitFor(() => expect(result.current.emRevisao).toBe(1))
    expect(revisoes).not.toHaveBeenCalled()
  })

  it('no administrador, a rede que cai deixa o número da última leitura', async () => {
    gravarIdentidade(dona)
    rede(true)
    const gestos = await vendaRecusada()
    vi.spyOn(sincronizacao, 'revisoes').mockResolvedValue({ conferidas: [], pendentes: [
      ...gestos.map(naLista), { ...naLista(gestos[0]), operacaoId: crypto.randomUUID(), registroId: crypto.randomUUID() },
    ] })
    const { result } = renderHook(() => useEnvioAutomatico(dona, sairFalso, '/vender', relerFalso))
    await waitFor(() => expect(result.current.emRevisao).toBe(2))

    rede(false)
    act(() => { window.dispatchEvent(new Event('offline')) })
    act(() => result.current.atualizar())

    await waitFor(() => expect(result.current.enviando).toBe(false))
    expect(result.current.emRevisao).toBe(2)
    rede(true)
  })
})

describe('servidor fora com o Wi-Fi ligado', () => {
  it('relê a identidade depois da rodada que o servidor respondeu, e não da que ficou sem resposta', async () => {
    rede(true)
    await gestoNovo()
    const enviarLote = vi.spyOn(sincronizacao, 'enviarLote').mockRejectedValueOnce(new SemConexao())
    const reler = vi.fn(async () => undefined)
    const { result } = renderHook(() => useEnvioAutomatico(ana, sairFalso, '/vender', reler))
    await waitFor(() => expect(enviarLote).toHaveBeenCalledTimes(1))
    await waitFor(() => expect(result.current.enviando).toBe(false))
    expect(reler).not.toHaveBeenCalled()

    enviarLote.mockImplementation(async (operacoes: OperacaoDoLote[]) => operacoes.map((operacao) => ({
      operacaoId: operacao.operacaoId, resultado: 'APLICADA' as const })))
    act(() => result.current.enviarAgora())

    await waitFor(() => expect(result.current.porEnviar).toBe(0))
    await waitFor(() => expect(reler).toHaveBeenCalledTimes(1))
  })

  it('sem resposta do servidor, a sondagem pergunta por ele, e a volta envia a fila', async () => {
    rede(true)
    await gestoNovo()
    const enviarLote = vi.spyOn(sincronizacao, 'enviarLote').mockImplementation(
      async (operacoes: OperacaoDoLote[]) => operacoes.map((operacao) => ({
        operacaoId: operacao.operacaoId, resultado: 'APLICADA' as const })))
    registrarSemResposta()
    const reler = vi.fn(async () => undefined)
    // Só os intervalos são falsos, e andam também com o relógio de verdade: a espera da Testing
    // Library continua conferindo, e o salto manual chega à sondagem.
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'], shouldAdvanceTime: true })
    try {
      const { result } = renderHook(() => useEnvioAutomatico(ana, sairFalso, '/vender', reler))
      await waitFor(() => expect(result.current.porEnviar).toBe(1))
      expect(enviarLote).not.toHaveBeenCalled()

      act(() => { vi.advanceTimersByTime(INTERVALO_DA_SONDAGEM) })
      expect(reler).toHaveBeenCalledTimes(1)
      // A rede do navegador que volta também faz perguntar na hora.
      act(() => { window.dispatchEvent(new Event('online')) })
      expect(reler).toHaveBeenCalledTimes(2)

      act(() => registrarResposta())
      await waitFor(() => expect(enviarLote).toHaveBeenCalledTimes(1))
      await waitFor(() => expect(result.current.porEnviar).toBe(0))
      act(() => { vi.advanceTimersByTime(INTERVALO_DA_SONDAGEM) })
      // Com o servidor respondendo, a sondagem para; a releitura da rodada respondida é outra.
      await waitFor(() => expect(reler).toHaveBeenCalledTimes(3))
    } finally {
      vi.useRealTimers()
    }
  })
})

describe('a renovação com o aplicativo aberto', () => {
  function aVista(visivel: boolean) {
    return vi.spyOn(document, 'visibilityState', 'get').mockReturnValue(visivel ? 'visible' : 'hidden')
  }

  afterEach(() => vi.useRealTimers())

  /** A fila está vazia, e nenhuma rodada pergunta nada: quem perguntar é a renovação. */
  function montar() {
    const reler = vi.fn(async () => undefined)
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'], shouldAdvanceTime: true })
    renderHook(() => useEnvioAutomatico(ana, sairFalso, '/vender', reler))
    return reler
  }

  it('à vista e com servidor, pergunta a cada hora, sem ninguém tocar', () => {
    rede(true)
    aVista(true)
    const reler = montar()

    act(() => { vi.advanceTimersByTime(INTERVALO_DA_RENOVACAO - 1000) })
    expect(reler).not.toHaveBeenCalled()

    act(() => { vi.advanceTimersByTime(1000) })
    expect(reler).toHaveBeenCalledTimes(1)
    act(() => { vi.advanceTimersByTime(INTERVALO_DA_RENOVACAO) })
    expect(reler).toHaveBeenCalledTimes(2)
  })

  it('oculta, não pergunta: o celular no bolso não mantém a sessão viva', () => {
    rede(true)
    aVista(false)
    const reler = montar()

    act(() => { vi.advanceTimersByTime(INTERVALO_DA_RENOVACAO) })
    act(() => { document.dispatchEvent(new Event('visibilitychange')) })
    expect(reler).not.toHaveBeenCalled()
  })

  it('voltar à tela pergunta na hora, sem esperar a hora cheia', () => {
    rede(true)
    const visibilidade = aVista(false)
    const reler = montar()

    visibilidade.mockReturnValue('visible')
    act(() => { document.dispatchEvent(new Event('visibilitychange')) })
    expect(reler).toHaveBeenCalledTimes(1)
  })

  it('sem rede, não pergunta: a chamada só marcaria o que já se sabe', () => {
    rede(false)
    aVista(true)
    const reler = montar()

    act(() => { vi.advanceTimersByTime(INTERVALO_DA_RENOVACAO) })
    act(() => { document.dispatchEvent(new Event('visibilitychange')) })
    expect(reler).not.toHaveBeenCalled()
  })

  it('a rede que volta pergunta mesmo com a fila vazia, e é isso que a faz renovar', () => {
    rede(false)
    aVista(true)
    const reler = montar()

    rede(true)
    act(() => { window.dispatchEvent(new Event('online')) })
    expect(reler).toHaveBeenCalledTimes(1)
  })
})
