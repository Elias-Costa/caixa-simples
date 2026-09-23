import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { useState } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { CHAVE_DA_SESSAO, fixarSessaoDaAba, gravarIdentidade, gravarToken, lerIdentidade,
  lerToken, limparSessao, renovarTokenDaSessao, sessaoAtual } from './armazenamento'
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

afterEach(() => vi.restoreAllMocks())

function QuemEsta() {
  const { identidade } = useSessao()
  return <p>{identidade ? `${identidade.nome} em ${identidade.nomeNegocio}` : 'ninguém'}</p>
}

function TrocarDeConta() {
  const { entrar, sair } = useSessao()
  return <>
    <QuemEsta />
    <button onClick={sair}>Sair</button>
    <button onClick={() => void entrar('b@exemplo.test', 'senha de teste')}>Entrar na Conta B</button>
  </>
}

function EntradaPendente() {
  const { entrar } = useSessao()
  const [erro, setErro] = useState('')
  return <>
    <button onClick={() => void entrar('a@exemplo.test', 'senha de teste')
      .catch((falha: Error) => setErro(falha.name))}>Entrar na Conta A</button>
    <p>{erro}</p>
  </>
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

  it('preserva a entrada offline de quem guardou token antes das chaves por sessão', async () => {
    localStorage.setItem('caixa-simples.token', tokenComExpiracao(daquiAUmDia()))
    localStorage.setItem('caixa-simples.identidade', JSON.stringify(guardada))
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')))

    render(<SessaoProvider><QuemEsta /></SessaoProvider>)

    expect(screen.getByText('Ana em Cafeteria Aurora')).toBeInTheDocument()
    await waitFor(() => expect(fetch).toHaveBeenCalled())
    expect(lerToken()).not.toBeNull()
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

  it('revalidação antiga da Conta A não restaura identidade nem token após entrar na Conta B', async () => {
    const tokenA = tokenComExpiracao(daquiAUmDia())
    const tokenB = tokenComExpiracao(new Date(Date.now() + 23 * 60 * 60 * 1000))
    const identidadeB = { ...guardada, usuarioId: '3', nome: 'Bia', contaId: '4',
      nomeNegocio: 'Mercado B' }
    gravarToken(tokenA)
    gravarIdentidade(guardada)
    let responderA!: (resposta: Response) => void
    let consultasEu = 0
    vi.stubGlobal('fetch', vi.fn((caminho: string) => {
      if (caminho === '/api/auth/login') {
        return Promise.resolve(new Response(JSON.stringify({ token: tokenB }), { status: 200 }))
      }
      if (consultasEu++ === 0) {
        return new Promise<Response>((resolve) => { responderA = resolve })
      }
      return Promise.resolve(new Response(JSON.stringify(identidadeB), { status: 200 }))
    }))

    render(<SessaoProvider><TrocarDeConta /></SessaoProvider>)
    fireEvent.click(screen.getByRole('button', { name: 'Sair' }))
    fireEvent.click(screen.getByRole('button', { name: 'Entrar na Conta B' }))
    expect(await screen.findByText('Bia em Mercado B')).toBeInTheDocument()

    responderA(new Response(JSON.stringify(guardada), {
      status: 200, headers: { 'X-Caixa-Simples-Token': tokenA },
    }))
    await waitFor(() => expect(lerToken()).toBe(tokenB))
    expect(screen.getByText('Bia em Mercado B')).toBeInTheDocument()
  })

  it('troca de Conta em outra aba deixa de exibir a identidade anterior', async () => {
    gravarToken(tokenComExpiracao(daquiAUmDia()))
    gravarIdentidade(guardada)
    vi.stubGlobal('fetch', vi.fn().mockReturnValue(new Promise<Response>(() => undefined)))
    render(<SessaoProvider><QuemEsta /></SessaoProvider>)
    expect(screen.getByText('Ana em Cafeteria Aurora')).toBeInTheDocument()

    gravarToken(tokenComExpiracao(new Date(Date.now() + 23 * 60 * 60 * 1000)))
    fireEvent(window, new StorageEvent('storage', { key: CHAVE_DA_SESSAO }))

    expect(await screen.findByText('ninguém')).toBeInTheDocument()
    expect(lerIdentidade()).toBeNull()
    expect(lerToken()).not.toBeNull()
  })

  it('login antigo não substitui a sessão que outra aba acabou de abrir', async () => {
    let responderA!: (resposta: Response) => void
    vi.stubGlobal('fetch', vi.fn().mockReturnValue(new Promise<Response>((resolve) => { responderA = resolve })))
    render(<SessaoProvider><EntradaPendente /></SessaoProvider>)
    fireEvent.click(screen.getByRole('button', { name: 'Entrar na Conta A' }))

    gravarToken('token-conta-b')
    responderA(new Response(JSON.stringify({ token: 'token-conta-a' }), { status: 200 }))

    expect(await screen.findByText('SessaoAlterada')).toBeInTheDocument()
    expect(lerToken()).toBe('token-conta-b')
  })

  it('login concorrente entre abas não adota a sessão criada pela outra aba', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ token: 'token-conta-a' }), { status: 200 }),
    ))
    const gravarOriginal = Storage.prototype.setItem
    let alternou = false
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(function (this: Storage, chave, valor) {
      gravarOriginal.call(this, chave, valor)
      if (this === localStorage && chave === CHAVE_DA_SESSAO && !alternou) {
        alternou = true
        gravarToken('token-conta-b')
      }
    })
    render(<SessaoProvider><EntradaPendente /></SessaoProvider>)
    fireEvent.click(screen.getByRole('button', { name: 'Entrar na Conta A' }))

    expect(await screen.findByText('SessaoAlterada')).toBeInTheDocument()
    expect(lerToken()).toBe('token-conta-b')
  })

  it('identidade e renovação antigas não alteram a sessão ativa de outra Conta', () => {
    gravarToken('token-conta-a')
    const sessaoA = sessaoAtual()!
    gravarToken('token-conta-b')
    const sessaoB = sessaoAtual()!
    const identidadeB = { ...guardada, contaId: '4', nome: 'Bia', nomeNegocio: 'Mercado B' }
    gravarIdentidade(identidadeB, sessaoB)

    renovarTokenDaSessao(sessaoA, 'token-a-renovado')
    gravarIdentidade(guardada, sessaoA)

    expect(lerToken()).toBe('token-conta-b')
    expect(lerIdentidade()).toEqual(identidadeB)
  })

  it('sair na aba antiga preserva a sessão aberta pela outra Conta', () => {
    gravarToken('token-conta-a')
    const sessaoA = sessaoAtual()!
    gravarToken('token-conta-b')
    fixarSessaoDaAba(sessaoA)

    limparSessao()

    expect(lerToken()).toBe('token-conta-b')
  })
})
