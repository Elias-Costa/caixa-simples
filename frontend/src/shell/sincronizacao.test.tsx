import 'fake-indexeddb/auto'
import { deleteDB } from 'idb'
import { renderHook, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ErroDaApi } from '../api/cliente'
import { sincronizacao, type OperacaoDoLote } from '../api/sincronizacao'
import { enfileirarGesto, listarGestos } from '../offline/fila'
import { gravarIdentidade, gravarToken } from '../sessao/armazenamento'
import type { Identidade } from '../sessao/Identidade'
import { tokenComExpiracao } from '../sessao/tokenDeTeste'
import { useEnvioAutomatico } from './sincronizacao'

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

afterEach(() => vi.restoreAllMocks())
beforeEach(async () => {
  await deleteDB('caixa-simples-offline')
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
    const { result } = renderHook(() => useEnvioAutomatico(ana, vi.fn(), '/vender'))

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
    renderHook(() => useEnvioAutomatico(ana, sair, '/vender'))
    await gestoNovo()

    await waitFor(() => expect(sair).toHaveBeenCalled())
    expect((await listarGestos()).map((gesto) => gesto.estado)).toEqual(['queued'])
  })
})
