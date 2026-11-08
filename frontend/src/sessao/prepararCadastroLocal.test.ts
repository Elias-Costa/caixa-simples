import 'fake-indexeddb/auto'
import { act, renderHook, waitFor } from '@testing-library/react'
import { deleteDB } from 'idb'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cadastro } from '../api/cadastro'
import { estadoDaConexao } from '../api/conexao'
import { lerRetrato } from '../offline/fila'
import { gravarIdentidade, gravarToken } from './armazenamento'
import type { Identidade } from './Identidade'
import { prepararCadastroLocal } from './prepararCadastroLocal'
import { SessaoProvider } from './SessaoProvider'
import { tokenComExpiracao } from './tokenDeTeste'
import { useSessao } from './useSessao'

const ana: Identidade = {
  usuarioId: 'ana', nome: 'Ana', perfil: 'OPERADOR', contaId: 'conta-a',
  nomeNegocio: 'Cafeteria Aurora', estoqueHabilitado: false,
}

beforeEach(async () => { await deleteDB('ecrx-caixa-offline') })
afterEach(() => vi.restoreAllMocks())

function espionarCadastro() {
  return [
    vi.spyOn(cadastro, 'produtos').mockResolvedValue([]),
    vi.spyOn(cadastro, 'clientes').mockResolvedValue([]),
    vi.spyOn(cadastro, 'clientesInativos').mockResolvedValue([]),
  ]
}

it('com a senha provisória não pede nada: o servidor recusaria cada pedido', async () => {
  const espioes = espionarCadastro()

  await prepararCadastroLocal({ ...ana, senhaProvisoria: true })

  for (const espiao of espioes) expect(espiao).not.toHaveBeenCalled()
})

it('com a senha escolhida, guarda produtos e clientes no aparelho', async () => {
  const espioes = espionarCadastro()

  await prepararCadastroLocal({ ...ana, senhaProvisoria: false })

  for (const espiao of espioes) expect(espiao).toHaveBeenCalledOnce()
})

describe('a cópia que a entrada não conseguiu fazer', () => {
  function respostaComJson(corpo: unknown): Response {
    return new Response(JSON.stringify(corpo), { status: 200, headers: { 'Content-Type': 'application/json' } })
  }

  it('é feita na volta do servidor, pela pergunta de quem opera, e não se repete com a cópia guardada', async () => {
    gravarToken(tokenComExpiracao(new Date(Date.now() + 60 * 60 * 1000)))
    gravarIdentidade(ana)
    // A identidade responde; o cadastro fica sem resposta até o servidor voltar.
    let cadastroResponde = false
    const fetchFalso = vi.fn(async (caminho: string) => {
      if (caminho === '/api/auth/eu') return respostaComJson(ana)
      if (!cadastroResponde) throw new TypeError('Failed to fetch')
      return respostaComJson([])
    })
    vi.stubGlobal('fetch', fetchFalso)
    const pedidosDoCadastro = () => fetchFalso.mock.calls
      .filter(([caminho]) => caminho.startsWith('/api/produtos') || caminho.startsWith('/api/clientes')).length

    const { result } = renderHook(() => useSessao(), { wrapper: SessaoProvider })
    await waitFor(() => expect(estadoDaConexao()).toBe('sem-servidor'))
    await new Promise((pronto) => setTimeout(pronto, 50))
    expect(await lerRetrato('produtos')).toBeNull()

    cadastroResponde = true
    await act(async () => { await result.current.relerIdentidade() })
    await waitFor(async () => expect(await lerRetrato('produtos')).not.toBeNull())
    await waitFor(async () => expect(await lerRetrato('clientesAtivos')).not.toBeNull())

    // Com a cópia guardada, a próxima pergunta não pede o cadastro de novo.
    const antes = pedidosDoCadastro()
    await act(async () => { await result.current.relerIdentidade() })
    await new Promise((pronto) => setTimeout(pronto, 50))
    expect(pedidosDoCadastro()).toBe(antes)
  })
})
