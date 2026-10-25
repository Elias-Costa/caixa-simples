import 'fake-indexeddb/auto'
import { renderHook, waitFor } from '@testing-library/react'
import { deleteDB } from 'idb'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { caixa, type SessaoCaixa } from '../api/caixa'
import { SemConexao } from '../api/cliente'
import { gravarIdentidade, gravarToken, limparSessao } from '../sessao/armazenamento'
import type { Identidade } from '../sessao/Identidade'
import { tokenComExpiracao } from '../sessao/tokenDeTeste'
import { avisoDoCaixaDeOutroDia, useAvisoDoCaixaDeOutroDia } from './caixaDeOutroDia'

const ana: Identidade = {
  usuarioId: 'ana', nome: 'Ana', perfil: 'OPERADOR', contaId: 'conta-a',
  nomeNegocio: 'Cafeteria Aurora', estoqueHabilitado: false,
}

// 24/10/2026 às 9h no balcão; a sessão de ontem abriu às 18h02 do dia 23.
const AGORA = new Date('2026-10-24T12:00:00Z')
const deOntem: SessaoCaixa = {
  id: 'sessao-ontem', usuarioId: ana.usuarioId, valorAbertura: 50, valorFechamentoEsperado: 50,
  valorFechamentoContado: null, diferenca: null, abertaEm: '2026-10-23T21:02:00Z',
  fechadaEm: null, status: 'ABERTA', versao: 0,
}
const AVISO = 'Seu caixa está aberto desde 23/10 às 18:02. As vendas de hoje entram nele até você fechá-lo.'

function rede(online: boolean) {
  vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(online)
}

beforeEach(async () => {
  await deleteDB('fechadia-offline')
  vi.useFakeTimers({ toFake: ['Date'] })
  vi.setSystemTime(AGORA)
  gravarToken(tokenComExpiracao(new Date(AGORA.getTime() + 60 * 60 * 1000)))
  gravarIdentidade(ana)
})

afterEach(() => {
  vi.useRealTimers()
  vi.restoreAllMocks()
  limparSessao()
})

describe('o aviso do caixa aberto desde outro dia', () => {
  it('diz o dia e a hora da abertura de ontem, e nada para a de hoje, a fechada ou nenhuma', () => {
    expect(avisoDoCaixaDeOutroDia(deOntem)).toBe(AVISO)
    expect(avisoDoCaixaDeOutroDia({ ...deOntem, abertaEm: '2026-10-24T11:00:00Z' })).toBeNull()
    expect(avisoDoCaixaDeOutroDia({ ...deOntem, status: 'FECHADA' })).toBeNull()
    expect(avisoDoCaixaDeOutroDia(undefined)).toBeNull()
  })

  it('aparece com rede e continua sem rede, pelo caixa guardado no aparelho', async () => {
    rede(true)
    const remoto = vi.spyOn(caixa, 'abertaDoOperadorAtual').mockResolvedValue(deOntem)
    const comRede = renderHook(() => useAvisoDoCaixaDeOutroDia('conta-a:ana', 0, '/vender'))
    await waitFor(() => expect(comRede.result.current).toBe(AVISO))
    comRede.unmount()

    rede(false)
    remoto.mockRejectedValue(new SemConexao())
    const semRede = renderHook(() => useAvisoDoCaixaDeOutroDia('conta-a:ana', 0, '/caixa'))
    await waitFor(() => expect(semRede.result.current).toBe(AVISO))
  })

  it('com o caixa aberto hoje, não aparece', async () => {
    rede(true)
    vi.spyOn(caixa, 'abertaDoOperadorAtual')
      .mockResolvedValue({ ...deOntem, id: 'sessao-hoje', abertaEm: '2026-10-24T11:30:00Z' })
    const { result } = renderHook(() => useAvisoDoCaixaDeOutroDia('conta-a:ana', 0, '/vender'))
    await waitFor(() => expect(caixa.abertaDoOperadorAtual).toHaveBeenCalled())
    await Promise.resolve()
    expect(result.current).toBeNull()
  })
})
