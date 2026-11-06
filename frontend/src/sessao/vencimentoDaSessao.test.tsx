import 'fake-indexeddb/auto'
import { deleteDB } from 'idb'
import { act, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { enfileirarGesto, listarGestos } from '../offline/fila'
import { gravarIdentidade, gravarToken, lerToken, renovarTokenDaSessao, sessaoAtual, sessaoFoiEncerrada,
  sessaoVenceu } from './armazenamento'
import type { Identidade } from './Identidade'
import { SessaoProvider } from './SessaoProvider'
import { tokenComExpiracao } from './tokenDeTeste'
import { useSessao } from './useSessao'

const UMA_HORA = 60 * 60 * 1000
const INTERVALO_DA_CONFERENCIA = 30_000

const guardada: Identidade = {
  usuarioId: '1', nome: 'Ana', perfil: 'OPERADOR', contaId: '2',
  nomeNegocio: 'Cafeteria Aurora', estoqueHabilitado: false,
}

function QuemEsta() {
  const { identidade } = useSessao()
  return <p>{identidade ? `${identidade.nome} em ${identidade.nomeNegocio}` : 'ninguém'}</p>
}

function aberto() {
  render(<SessaoProvider><QuemEsta /></SessaoProvider>)
  expect(screen.getByText('Ana em Cafeteria Aurora')).toBeInTheDocument()
}

/** O relógio passa do vencimento sem nenhum intervalo disparar: é o aparelho que dormiu. */
function dormirAteVencer() {
  vi.setSystemTime(Date.now() + UMA_HORA + 1000)
}

async function esperarAEntrada() {
  expect(await screen.findByText('ninguém')).toBeInTheDocument()
  expect(lerToken()).toBeNull()
  expect(sessaoVenceu()).toBe(true)
  expect(sessaoFoiEncerrada()).toBe(false)
}

beforeEach(async () => {
  await deleteDB('ecrx-caixa-offline')
  vi.stubGlobal('fetch', vi.fn(() => new Promise<Response>(() => undefined)))
  gravarToken(tokenComExpiracao(new Date(Date.now() + UMA_HORA)))
  gravarIdentidade(guardada)
  // O relógio e os intervalos são falsos, e os dois andam também com o tempo de verdade: a espera da
  // Testing Library continua conferindo, e o salto manual chega à conferência.
  vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval', 'Date'], shouldAdvanceTime: true })
})

afterEach(() => {
  vi.useRealTimers()
  vi.restoreAllMocks()
})

describe('o token que vence com o aplicativo aberto', () => {
  it('com a tela parada, a conferência leva à entrada, e a fila continua para o próximo login', async () => {
    const gesto = await enfileirarGesto({ tipo: 'cliente.criar', registroId: crypto.randomUUID(),
      payload: { nome: 'Maria', contato: null } })
    aberto()

    act(() => { vi.advanceTimersByTime(UMA_HORA + INTERVALO_DA_CONFERENCIA) })

    await esperarAEntrada()
    gravarToken(tokenComExpiracao(new Date(Date.now() + UMA_HORA)))
    gravarIdentidade(guardada)
    expect((await listarGestos()).map((guardado) => guardado.operacaoId)).toEqual([gesto.operacaoId])
  })

  it('fechar e abrir de novo depois de vencer ainda sabe que venceu, até o próximo login', async () => {
    aberto()
    act(() => { vi.advanceTimersByTime(UMA_HORA + INTERVALO_DA_CONFERENCIA) })
    await esperarAEntrada()

    // A aba nova não tem nada da anterior, e o token já saiu do aparelho.
    sessionStorage.clear()
    expect(sessaoVenceu()).toBe(true)

    gravarToken(tokenComExpiracao(new Date(Date.now() + UMA_HORA)))
    expect(sessaoVenceu()).toBe(false)
  })

  it('o aparelho que acorda e volta à tela vai à entrada na hora, sem esperar o intervalo', async () => {
    vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible')
    aberto()
    dormirAteVencer()

    act(() => { document.dispatchEvent(new Event('visibilitychange')) })

    await esperarAEntrada()
  })

  it('a rede que volta com o token vencido vai à entrada na hora', async () => {
    aberto()
    dormirAteVencer()

    act(() => { window.dispatchEvent(new Event('online')) })

    await esperarAEntrada()
  })

  it('a fila que recusa o token vencido leva à entrada na hora, e a tela não fica dizendo que não há nada', async () => {
    aberto()
    dormirAteVencer()

    await expect(listarGestos()).rejects.toThrow()

    await esperarAEntrada()
  })

  it('o token que ainda vale não encerra nada', () => {
    aberto()

    act(() => { vi.advanceTimersByTime(INTERVALO_DA_CONFERENCIA) })

    expect(screen.getByText('Ana em Cafeteria Aurora')).toBeInTheDocument()
    expect(sessaoVenceu()).toBe(false)
  })

  it('a conferência lê o token do momento: o renovado adia o fim', () => {
    aberto()
    renovarTokenDaSessao(sessaoAtual()!, tokenComExpiracao(new Date(Date.now() + 2 * UMA_HORA)))

    act(() => { vi.advanceTimersByTime(UMA_HORA + INTERVALO_DA_CONFERENCIA) })

    expect(screen.getByText('Ana em Cafeteria Aurora')).toBeInTheDocument()
    expect(sessaoVenceu()).toBe(false)
  })
})
