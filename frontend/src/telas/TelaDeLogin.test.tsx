import { fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router'
import { describe, expect, it, vi } from 'vitest'
import { lerIdentidade, lerToken, marcarSessaoEncerrada, sessaoFoiEncerrada } from '../sessao/armazenamento'
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
  it('mostra a senha digitada a quem toca em Mostrar, e a esconde de novo', () => {
    renderizarLogin()
    const senha = screen.getByLabelText('Senha')
    fireEvent.change(senha, { target: { value: 'abc' } })
    expect(senha).toHaveAttribute('type', 'password')

    fireEvent.click(screen.getByRole('button', { name: 'Mostrar' }))
    expect(senha).toHaveAttribute('type', 'text')
    expect(screen.getByRole('button', { name: 'Ocultar' })).toHaveAttribute('aria-pressed', 'true')
    fireEvent.click(screen.getByRole('button', { name: 'Ocultar' }))
    expect(senha).toHaveAttribute('type', 'password')
  })

  it('credencial recusada mostra a mensagem e não guarda nada', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(null, { status: 401 })))
    renderizarLogin()

    preencherEEnviar()

    expect(await screen.findByRole('alert')).toHaveTextContent('E-mail ou senha incorretos.')
    expect(lerToken()).toBeNull()
  })

  it('tentativas demais mostram a espera que o servidor informa', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        respostaJson(429, {
          title: 'Too Many Requests',
          status: 429,
          detail: 'Muitas tentativas de entrar. Tente de novo em 12 minutos.',
        }),
      ),
    )
    renderizarLogin()

    preencherEEnviar()

    expect(await screen.findByRole('alert')).toHaveTextContent('Tente de novo em 12 minutos.')
    expect(lerToken()).toBeNull()
    expect(screen.getByRole('button', { name: 'Entrar' })).toBeEnabled()
  })

  it('tentativas demais sem corpo ainda pedem para esperar', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(null, { status: 429 })))
    renderizarLogin()

    preencherEEnviar()

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Muitas tentativas de entrar. Aguarde alguns minutos e tente de novo.',
    )
  })

  it('a Conta suspensa mostra a mensagem do servidor, sem confundir com a senha errada', async () => {
    marcarSessaoEncerrada()
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        respostaJson(403, {
          title: 'Forbidden',
          status: 403,
          detail: 'O acesso desta Conta está suspenso. O administrador recebeu o aviso por e-mail.',
        }),
      ),
    )
    renderizarLogin()

    preencherEEnviar()

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'O acesso desta Conta está suspenso. O administrador recebeu o aviso por e-mail.')
    expect(screen.getByRole('alert')).not.toHaveTextContent('E-mail ou senha incorretos.')
    expect(lerToken()).toBeNull()
    // O aviso de que o registrado sem rede continua guardado fica na tela até o próximo login.
    expect(sessaoFoiEncerrada()).toBe(true)
  })

  it('sem conexão mostra a mensagem própria', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')))
    renderizarLogin()

    preencherEEnviar()

    expect(await screen.findByRole('alert')).toHaveTextContent('Sem conexão')
  })

  it('sem aviso quando nada encerrou a sessão', () => {
    vi.stubGlobal('fetch', vi.fn())
    renderizarLogin()

    expect(screen.queryByText(/sessão neste aparelho foi encerrada/)).not.toBeInTheDocument()
  })

  it('depois de o servidor recusar o token, avisa que a fila continua guardada até o próximo login', async () => {
    marcarSessaoEncerrada()
    vi.stubGlobal(
      'fetch',
      vi.fn()
        .mockResolvedValueOnce(respostaJson(200, { token: 'token-do-login' }))
        .mockResolvedValueOnce(respostaJson(200, {
          usuarioId: '1', nome: 'Ana', perfil: 'ADMIN', contaId: '2',
          nomeNegocio: 'Cafeteria Aurora', estoqueHabilitado: false,
        })),
    )
    renderizarLogin()

    expect(screen.getByRole('status')).toHaveTextContent(
      'o que foi registrado sem rede continua guardado aqui e é enviado depois que você entrar')

    preencherEEnviar()

    expect(await screen.findByText('dentro do aplicativo')).toBeInTheDocument()
    expect(sessaoFoiEncerrada()).toBe(false)
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
