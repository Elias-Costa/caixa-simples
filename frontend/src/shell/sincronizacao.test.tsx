import 'fake-indexeddb/auto'
import { deleteDB } from 'idb'
import { act, renderHook, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ErroDaApi, SemConexao } from '../api/cliente'
import { registrarResposta, registrarSemResposta } from '../api/conexao'
import { sincronizacao, type OperacaoDoLote } from '../api/sincronizacao'
import { enfileirarGesto, listarGestos } from '../offline/fila'
import { gravarIdentidade, gravarToken, sessaoFoiEncerrada } from '../sessao/armazenamento'
import type { Identidade } from '../sessao/Identidade'
import { tokenComExpiracao } from '../sessao/tokenDeTeste'
import { INTERVALO_DA_SONDAGEM, useEnvioAutomatico } from './sincronizacao'

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
