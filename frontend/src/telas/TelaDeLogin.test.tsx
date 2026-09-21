import { fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router'
import { describe, expect, it, vi } from 'vitest'
import { lerIdentidade, lerToken } from '../sessao/armazenamento'
import { SessaoProvider } from '../sessao/SessaoProvider'
import { TelaDeLogin } from './TelaDeLogin'

function renderizarLogin() {
  render(
    <MemoryRouter initialEntries={['/entrar']}>
      <SessaoProvider>
        <Routes>
          <Route path="/entrar" element={<TelaDeLogin />} />
          <Route path="/" element={<p>dentro do aplicativo</p>} />
        </Routes>
      </SessaoProvider>
    </MemoryRouter>,
  )
}

function preencherEEnviar() {
  fireEvent.change(screen.getByLabelText('E-mail'), { target: { value: 'ana@exemplo.test' } })
  fireEvent.change(screen.getByLabelText('Senha'), { target: { value: 'abc' } })
  fireEvent.click(screen.getByRole('button', { name: 'Entrar' }))
}

function respostaJson(status: number, corpo: unknown) {
  return new Response(JSON.stringify(corpo), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

describe('TelaDeLogin', () => {
  it('credencial recusada mostra a mensagem e não guarda nada', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(null, { status: 401 })))
    renderizarLogin()

    preencherEEnviar()

    expect(await screen.findByRole('alert')).toHaveTextContent('E-mail ou senha incorretos.')
    expect(lerToken()).toBeNull()
  })

  it('sem conexão mostra a mensagem própria', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')))
    renderizarLogin()

    preencherEEnviar()

    expect(await screen.findByRole('alert')).toHaveTextContent('Sem conexão')
  })

  it('login certo guarda o token e a identidade e entra no aplicativo', async () => {
    const identidade = {
      usuarioId: '1',
      nome: 'Ana',
      perfil: 'ADMIN',
      contaId: '2',
      nomeNegocio: 'Cafeteria Aurora',
      estoqueHabilitado: false,
    }
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValueOnce(respostaJson(200, { token: 'token-do-login' }))
        .mockResolvedValueOnce(respostaJson(200, identidade)),
    )
    renderizarLogin()

    preencherEEnviar()

    expect(await screen.findByText('dentro do aplicativo')).toBeInTheDocument()
    expect(lerToken()).toBe('token-do-login')
    expect(lerIdentidade()).toEqual(identidade)
  })
})
