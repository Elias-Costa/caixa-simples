import { render, screen, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { gravarIdentidade, gravarToken, lerToken } from './armazenamento'
import type { Identidade } from './Identidade'
import { SessaoProvider } from './SessaoProvider'
import { tokenComExpiracao } from './tokenDeTeste'
import { useSessao } from './useSessao'

const guardada: Identidade = {
  usuarioId: '1',
  nome: 'Ana',
  perfil: 'ADMIN',
  contaId: '2',
  nomeNegocio: 'Cafeteria Aurora',
  estoqueHabilitado: false,
}

function QuemEsta() {
  const { identidade } = useSessao()
  return <p>{identidade ? `${identidade.nome} em ${identidade.nomeNegocio}` : 'ninguém'}</p>
}

function daquiAUmDia() {
  return new Date(Date.now() + 24 * 60 * 60 * 1000)
}

describe('SessaoProvider', () => {
  it('abre com a identidade guardada quando o token guardado ainda vale, mesmo sem rede', async () => {
    gravarToken(tokenComExpiracao(daquiAUmDia()))
    gravarIdentidade(guardada)
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')))

    render(
      <SessaoProvider>
        <QuemEsta />
      </SessaoProvider>,
    )

    expect(screen.getByText('Ana em Cafeteria Aurora')).toBeInTheDocument()
    await waitFor(() => expect(fetch).toHaveBeenCalled())
    expect(screen.getByText('Ana em Cafeteria Aurora')).toBeInTheDocument()
  })

  it('abre sem ninguém quando não há token, e não pergunta ao servidor', () => {
    const fetchFalso = vi.fn()
    vi.stubGlobal('fetch', fetchFalso)

    render(
      <SessaoProvider>
        <QuemEsta />
      </SessaoProvider>,
    )

    expect(screen.getByText('ninguém')).toBeInTheDocument()
    expect(fetchFalso).not.toHaveBeenCalled()
  })

  it('token expirado é descartado e o aplicativo abre sem ninguém', () => {
    gravarToken(tokenComExpiracao(new Date(Date.now() - 1000)))
    gravarIdentidade(guardada)
    vi.stubGlobal('fetch', vi.fn())

    render(
      <SessaoProvider>
        <QuemEsta />
      </SessaoProvider>,
    )

    expect(screen.getByText('ninguém')).toBeInTheDocument()
    expect(lerToken()).toBeNull()
  })

  it('a revalidação na abertura atualiza a identidade com a resposta do servidor', async () => {
    gravarToken(tokenComExpiracao(daquiAUmDia()))
    gravarIdentidade(guardada)
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response(JSON.stringify({ ...guardada, nome: 'Ana Maria' }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        }),
      ),
    )

    render(
      <SessaoProvider>
        <QuemEsta />
      </SessaoProvider>,
    )

    expect(await screen.findByText('Ana Maria em Cafeteria Aurora')).toBeInTheDocument()
  })

  it('o servidor respondendo 401 na abertura derruba a sessão guardada', async () => {
    gravarToken(tokenComExpiracao(daquiAUmDia()))
    gravarIdentidade(guardada)
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(null, { status: 401 })))

    render(
      <SessaoProvider>
        <QuemEsta />
      </SessaoProvider>,
    )

    expect(await screen.findByText('ninguém')).toBeInTheDocument()
    expect(lerToken()).toBeNull()
  })
})
