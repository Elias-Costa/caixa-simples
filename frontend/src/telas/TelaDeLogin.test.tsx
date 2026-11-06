import { act, fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { gravarIdentidade, gravarToken, lerIdentidade, lerToken, marcarSessaoEncerrada,
  sessaoFoiEncerrada } from '../sessao/armazenamento'
import { SessaoProvider } from '../sessao/SessaoProvider'
import { tokenComExpiracao } from '../sessao/tokenDeTeste'
import { ExigeSessao } from '../shell/ExigeSessao'
import { TelaDeLogin } from './TelaDeLogin'

const FRASE_DO_VENCIMENTO = 'A sessão neste aparelho venceu. Entre de novo, com rede; o que foi registrado '
  + 'continua guardado aqui e é enviado depois que você entrar.'
const FRASE_DO_401 = 'A sessão neste aparelho foi encerrada. Entre de novo; o que foi registrado sem rede '
  + 'continua guardado aqui e é enviado depois que você entrar.'

function tokenDoLogin() {
  return tokenComExpiracao(new Date(Date.now() + 60 * 60 * 1000))
}

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
  it('mostra o nome do produto com a categoria logo abaixo', () => {
    vi.stubGlobal('fetch', vi.fn())
    renderizarLogin()

    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('ECRX Caixa')
    expect(screen.getByText('Sistema de caixa e vendas')).toBeInTheDocument()
  })

  it('diz a quem pedir a senha esquecida antes de qualquer erro', () => {
    vi.stubGlobal('fetch', vi.fn())
    renderizarLogin()

    expect(screen.getByText('Esqueceu a senha? Peça ao administrador da Conta uma senha provisória. '
      + 'Se você é o administrador, fale com o atendimento do ECRX Caixa.')).toBeInTheDocument()
  })

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

    expect(screen.queryByRole('status')).not.toBeInTheDocument()
  })

  it('depois de o servidor recusar o token, avisa que a fila continua guardada até o próximo login', async () => {
    marcarSessaoEncerrada()
    vi.stubGlobal(
      'fetch',
      vi.fn()
        .mockResolvedValueOnce(respostaJson(200, { token: tokenDoLogin() }))
        .mockResolvedValueOnce(respostaJson(200, {
          usuarioId: '1', nome: 'Ana', perfil: 'ADMIN', contaId: '2',
          nomeNegocio: 'Cafeteria Aurora', estoqueHabilitado: false,
        })),
    )
    renderizarLogin()

    expect(screen.getByRole('status')).toHaveTextContent(FRASE_DO_401)

    preencherEEnviar()

    expect(await screen.findByText('dentro do aplicativo')).toBeInTheDocument()
    expect(sessaoFoiEncerrada()).toBe(false)
  })

  it('abrir o aplicativo com o token vencido diz que a sessão venceu, e não que alguém a encerrou', () => {
    gravarToken(tokenComExpiracao(new Date(Date.now() - 1000)))
    vi.stubGlobal('fetch', vi.fn())

    renderizarLogin()

    expect(screen.getByRole('status')).toHaveTextContent(FRASE_DO_VENCIMENTO)
  })

  describe('com o aplicativo aberto', () => {
    afterEach(() => vi.useRealTimers())

    it('o token que vence leva da tela à entrada, com a frase, quando a rede volta', async () => {
      gravarToken(tokenComExpiracao(new Date(Date.now() + 60 * 60 * 1000)))
      gravarIdentidade({ usuarioId: '1', nome: 'Ana', perfil: 'OPERADOR', contaId: '2',
        nomeNegocio: 'Cafeteria Aurora', estoqueHabilitado: false })
      vi.stubGlobal('fetch', vi.fn(() => new Promise<Response>(() => undefined)))
      vi.useFakeTimers({ toFake: ['Date'], shouldAdvanceTime: true })
      render(
        <MemoryRouter initialEntries={['/caixa']}>
          <SessaoProvider>
            <Routes>
              <Route element={<ExigeSessao />}>
                <Route path="/caixa" element={<p>tela do caixa</p>} />
              </Route>
              <Route path="/entrar" element={<TelaDeLogin />} />
            </Routes>
          </SessaoProvider>
        </MemoryRouter>,
      )
      expect(screen.getByText('tela do caixa')).toBeInTheDocument()

      vi.setSystemTime(Date.now() + 60 * 60 * 1000 + 1000)
      act(() => { window.dispatchEvent(new Event('online')) })

      expect(await screen.findByRole('status')).toHaveTextContent(FRASE_DO_VENCIMENTO)
      expect(screen.queryByText('tela do caixa')).not.toBeInTheDocument()
    })
  })

  it('o relógio adiantado, que faz o token novo nascer vencido, é dito na entrada, sem entrar', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(
      respostaJson(200, { token: tokenComExpiracao(new Date(Date.now() - 1000)) })))
    renderizarLogin()

    preencherEEnviar()

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'O relógio deste aparelho está adiantado. Acerte a data e a hora e entre de novo; o que foi registrado '
      + 'continua guardado aqui.')
    expect(lerToken()).toBeNull()
    expect(fetch).toHaveBeenCalledTimes(1)
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
    const token = tokenDoLogin()
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValueOnce(respostaJson(200, { token }))
        .mockResolvedValueOnce(respostaJson(200, identidade)),
    )
    renderizarLogin()

    preencherEEnviar()

    expect(await screen.findByText('dentro do aplicativo')).toBeInTheDocument()
    expect(lerToken()).toBe(token)
    expect(lerIdentidade()).toEqual(identidade)
  })
})
