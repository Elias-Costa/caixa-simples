import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { CHAVE_DA_SESSAO, gravarIdentidade, gravarToken, lerIdentidade, lerToken, marcarSessaoEncerrada,
  marcarSessaoVencida, sessaoAtual, sessaoFoiEncerrada, sessaoVenceu, voltaPorOutraAba } from '../sessao/armazenamento'
import { SessaoProvider } from '../sessao/SessaoProvider'
import { tokenComExpiracao } from '../sessao/tokenDeTeste'
import { ExigeSessao } from '../shell/ExigeSessao'
import { TelaDeLogin } from './TelaDeLogin'
import { TelaDoAparelho } from './TelaDoAparelho'

const FRASE_DO_VENCIMENTO = 'A sessão neste aparelho venceu. Entre de novo, com rede; o que foi registrado '
  + 'continua guardado aqui e é enviado depois que quem o registrou entrar.'
const FRASE_DO_401 = 'A sessão neste aparelho foi encerrada. Entre de novo; o que foi registrado '
  + 'continua guardado aqui e é enviado depois que quem o registrou entrar.'

const FRASE_DE_QUEM_SAIU = 'Alguém saiu em outra aba do aplicativo, e a sessão terminou aqui também. Entre de '
  + 'novo; o que foi registrado continua guardado aqui.'
const FRASE_DE_QUEM_ENTROU = 'Alguém entrou em outra aba do aplicativo, e só uma sessão fica aberta por vez. Se '
  + 'entrar aqui, a outra aba volta à entrada; o que foi registrado continua guardado aqui.'

function tokenDoLogin() {
  return tokenComExpiracao(new Date(Date.now() + 60 * 60 * 1000))
}

function renderizarLogin() {
  render(
    <MemoryRouter initialEntries={['/entrar']}>
      <SessaoProvider>
        <Routes>
          <Route path="/entrar" element={<TelaDeLogin />} />
          <Route path="/aparelho" element={<TelaDoAparelho />} />
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

afterEach(() => vi.restoreAllMocks())

describe('TelaDeLogin', () => {
  it('mostra o nome do produto com a categoria logo abaixo', () => {
    vi.stubGlobal('fetch', vi.fn())
    renderizarLogin()

    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('ECRX Caixa')
    expect(screen.getByText('Sistema de caixa e vendas')).toBeInTheDocument()
  })

  it('o link da senha esquecida fica à vista antes de qualquer erro e abre a instrução no próprio cartão', () => {
    vi.stubGlobal('fetch', vi.fn())
    renderizarLogin()
    const instrucao = screen.getByText('Peça ao administrador da Conta uma senha provisória. Se você é o '
      + 'administrador, fale com o atendimento do ECRX Caixa.')
    expect(instrucao).not.toBeVisible()
    // Logo depois da senha e antes de Entrar, na ordem da leitura e do Tab.
    expect(screen.getByLabelText('Senha').compareDocumentPosition(screen.getByText('Esqueceu sua senha?')))
      .toBe(Node.DOCUMENT_POSITION_FOLLOWING)
    expect(screen.getByText('Esqueceu sua senha?').compareDocumentPosition(
      screen.getByRole('button', { name: 'Entrar' }))).toBe(Node.DOCUMENT_POSITION_FOLLOWING)

    fireEvent.click(screen.getByText('Esqueceu sua senha?'))

    expect(instrucao).toBeVisible()
    expect(screen.getByLabelText('E-mail')).toBeInTheDocument()
  })

  it('o olho mostra a senha digitada e a esconde de novo, com o mesmo nome e o estado no aria-pressed', () => {
    renderizarLogin()
    const senha = screen.getByLabelText('Senha')
    const olho = screen.getByRole('button', { name: 'Mostrar a senha' })
    fireEvent.change(senha, { target: { value: 'abc' } })
    expect(senha).toHaveAttribute('type', 'password')
    expect(olho).toHaveAttribute('aria-pressed', 'false')

    fireEvent.click(olho)
    expect(senha).toHaveAttribute('type', 'text')
    expect(screen.getByRole('button', { name: 'Mostrar a senha' })).toHaveAttribute('aria-pressed', 'true')

    fireEvent.click(olho)
    expect(senha).toHaveAttribute('type', 'password')
    expect(olho).toHaveAttribute('aria-pressed', 'false')
  })

  it('os links de apoio ficam no pé do cartão, fora do formulário de entrar', () => {
    vi.stubGlobal('fetch', vi.fn())
    renderizarLogin()
    const formulario = screen.getByRole('button', { name: 'Entrar' }).closest('form')

    for (const nome of ['Aviso de privacidade', 'Termos de uso', 'Apagar os dados deste aparelho']) {
      const link = screen.getByRole('link', { name: nome })
      expect(formulario).not.toContainElement(link)
      expect(formulario?.compareDocumentPosition(link)).toBe(Node.DOCUMENT_POSITION_FOLLOWING)
    }
  })

  describe('campo em branco', () => {
    it('não vai ao servidor: diz o que falta embaixo de cada campo e põe o foco no primeiro', () => {
      vi.stubGlobal('fetch', vi.fn())
      renderizarLogin()

      fireEvent.click(screen.getByRole('button', { name: 'Entrar' }))

      const email = screen.getByLabelText('E-mail')
      const senha = screen.getByLabelText('Senha')
      expect(fetch).not.toHaveBeenCalled()
      expect(email).toHaveAttribute('aria-invalid', 'true')
      expect(email).toHaveAccessibleDescription('Informe o e-mail.')
      expect(senha).toHaveAttribute('aria-invalid', 'true')
      expect(senha).toHaveAccessibleDescription('Informe a senha.')
      expect(email).toHaveFocus()
      expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    })

    it('só espaços também é em branco, como o servidor considera, e o foco vai à senha que falta', () => {
      vi.stubGlobal('fetch', vi.fn())
      renderizarLogin()
      fireEvent.change(screen.getByLabelText('E-mail'), { target: { value: 'ana@exemplo.test' } })
      fireEvent.change(screen.getByLabelText('Senha'), { target: { value: '   ' } })

      fireEvent.click(screen.getByRole('button', { name: 'Entrar' }))

      expect(fetch).not.toHaveBeenCalled()
      expect(screen.getByLabelText('E-mail')).not.toHaveAttribute('aria-invalid')
      expect(screen.getByText('Informe a senha.')).toBeInTheDocument()
      expect(screen.getByLabelText('Senha')).toHaveFocus()
    })

    it('a mensagem do campo sai quando a pessoa digita nele, e a do outro continua', () => {
      vi.stubGlobal('fetch', vi.fn())
      renderizarLogin()
      fireEvent.click(screen.getByRole('button', { name: 'Entrar' }))

      fireEvent.change(screen.getByLabelText('Senha'), { target: { value: 'a' } })

      expect(screen.queryByText('Informe a senha.')).not.toBeInTheDocument()
      expect(screen.getByLabelText('Senha')).not.toHaveAttribute('aria-invalid')
      expect(screen.getByText('Informe o e-mail.')).toBeInTheDocument()
    })

    it('o formato do e-mail fica com o servidor, que não o confere no login', async () => {
      vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(null, { status: 401 })))
      renderizarLogin()
      fireEvent.change(screen.getByLabelText('E-mail'), { target: { value: 'ana' } })
      fireEvent.change(screen.getByLabelText('Senha'), { target: { value: 'abc' } })

      fireEvent.click(screen.getByRole('button', { name: 'Entrar' }))

      expect(await screen.findByRole('alert')).toHaveTextContent('E-mail ou senha incorretos.')
      expect(fetch).toHaveBeenCalledTimes(1)
    })
  })

  it('avisa o Caps Lock ligado embaixo da senha, e o aviso some quando a tecla desliga ou o campo perde o foco', () => {
    vi.stubGlobal('fetch', vi.fn())
    renderizarLogin()
    const senha = screen.getByLabelText('Senha')
    expect(senha).toHaveAccessibleDescription('')

    fireEvent.keyDown(senha, { key: 'A', modifierCapsLock: true })
    expect(senha).toHaveAccessibleDescription('Caps Lock ligado.')
    // Já montada antes do texto, para o leitor de tela anunciar; sem o papel status, que é o da mensagem da tela.
    expect(screen.getByText('Caps Lock ligado.')).toHaveAttribute('aria-live', 'polite')
    expect(screen.queryByRole('status')).not.toBeInTheDocument()

    fireEvent.keyUp(senha, { key: 'CapsLock', modifierCapsLock: false })
    expect(screen.queryByText('Caps Lock ligado.')).not.toBeInTheDocument()

    fireEvent.keyDown(senha, { key: 'A', modifierCapsLock: true })
    fireEvent.blur(senha)
    expect(screen.queryByText('Caps Lock ligado.')).not.toBeInTheDocument()
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
    // O aviso de que o registrado continua guardado fica na tela até o próximo login.
    expect(sessaoFoiEncerrada()).toBe(true)
  })

  describe('sem resposta na entrada', () => {
    const SEM_REDE = 'Sem conexão. Entrar precisa de rede.'
    const SEM_SERVIDOR = 'Sem resposta do servidor. A rede deste aparelho pode estar funcionando; tente de novo em alguns minutos.'
    const GUARDADO_DEPOIS_DA_REDE = 'Sem conexão. Entrar precisa de rede; o que foi registrado continua guardado neste aparelho.'
    const GUARDADO = 'O que foi registrado continua guardado neste aparelho.'

    beforeEach(() => {
      vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')))
    })

    function semRede() {
      vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(false)
    }

    it('sem rede, diz que entrar precisa dela e que o registrado continua guardado', async () => {
      semRede()
      renderizarLogin()

      preencherEEnviar()

      expect(await screen.findByRole('alert')).toHaveTextContent(new RegExp(`^${GUARDADO_DEPOIS_DA_REDE}$`))
    })

    it('com o Wi-Fi ligado e o servidor fora, não manda verificar a rede', async () => {
      renderizarLogin()

      preencherEEnviar()

      expect(await screen.findByRole('alert')).toHaveTextContent(`${SEM_SERVIDOR} ${GUARDADO}`)
      expect(screen.getByRole('alert')).not.toHaveTextContent('Sem conexão')
    })

    it('o proxy sem a aplicação também é sem resposta do servidor', async () => {
      vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(null, { status: 502 })))
      renderizarLogin()

      preencherEEnviar()

      expect(await screen.findByRole('alert')).toHaveTextContent(SEM_SERVIDOR)
    })

    it('com a frase do vencimento em cima, a de baixo não repete o que fica guardado', async () => {
      marcarSessaoVencida()
      semRede()
      renderizarLogin()

      preencherEEnviar()

      expect(await screen.findByRole('alert')).toHaveTextContent(new RegExp(`^${SEM_REDE}$`))
      expect(screen.getByRole('status')).toHaveTextContent(FRASE_DO_VENCIMENTO)
    })

    it('com a frase do 401 em cima, também não repete, sem resposta do servidor', async () => {
      marcarSessaoEncerrada()
      renderizarLogin()

      preencherEEnviar()

      expect(await screen.findByRole('alert')).toHaveTextContent(new RegExp(`^${SEM_SERVIDOR}$`))
      expect(screen.getByRole('status')).toHaveTextContent(FRASE_DO_401)
    })
  })

  describe('o login responde e a identidade não', () => {
    const FRASE_SEM_SERVIDOR = 'Sem resposta do servidor. A rede deste aparelho pode estar funcionando; tente de novo em alguns minutos.'

    function loginSemIdentidade(respostaDaIdentidade: () => Promise<Response> = () => Promise.reject(new TypeError('Failed to fetch'))) {
      vi.stubGlobal('fetch', vi.fn((caminho: string) => caminho === '/api/auth/login'
        ? Promise.resolve(respostaJson(200, { token: tokenDoLogin() }))
        : respostaDaIdentidade()))
    }

    it('não fica token sem identidade, e a página Apagar deixa de dizer que há sessão aberta', async () => {
      loginSemIdentidade()
      renderizarLogin()

      preencherEEnviar()

      expect(await screen.findByRole('alert')).toHaveTextContent(FRASE_SEM_SERVIDOR)
      expect(lerToken()).toBeNull()
      expect(sessaoAtual()).toBeNull()

      fireEvent.click(screen.getByRole('link', { name: 'Apagar os dados deste aparelho' }))

      expect(await screen.findByRole('button', { name: 'Apagar os dados' })).toBeEnabled()
      expect(screen.queryByText(/Há uma sessão aberta/)).not.toBeInTheDocument()
    })

    it('a identidade que responde com erro também não deixa token', async () => {
      loginSemIdentidade(() => Promise.resolve(respostaJson(500, { title: 'Erro', status: 500 })))
      renderizarLogin()

      preencherEEnviar()

      expect(await screen.findByRole('alert')).toHaveTextContent('Não foi possível entrar. Tente de novo.')
      expect(lerToken()).toBeNull()
    })

    it('a frase do vencimento continua na tela e no aparelho até a entrada dar certo', async () => {
      marcarSessaoVencida()
      loginSemIdentidade()
      renderizarLogin()

      preencherEEnviar()

      expect(await screen.findByRole('alert')).toHaveTextContent(new RegExp(`^${FRASE_SEM_SERVIDOR}$`))
      expect(screen.getByRole('status')).toHaveTextContent(FRASE_DO_VENCIMENTO)
      expect(sessaoVenceu()).toBe(true)
      expect(lerToken()).toBeNull()
    })

    it('a frase do 401 também continua', async () => {
      marcarSessaoEncerrada()
      loginSemIdentidade()
      renderizarLogin()

      preencherEEnviar()

      expect(await screen.findByRole('alert')).toBeInTheDocument()
      expect(screen.getByRole('status')).toHaveTextContent(FRASE_DO_401)
      expect(sessaoFoiEncerrada()).toBe(true)
    })

    it('repetir o login com a identidade de volta entra, e só aí a marca sai', async () => {
      marcarSessaoVencida()
      let identidadeResponde = false
      const identidade = { usuarioId: '1', nome: 'Ana', perfil: 'ADMIN', contaId: '2',
        nomeNegocio: 'Cafeteria Aurora', estoqueHabilitado: false }
      loginSemIdentidade(() => identidadeResponde
        ? Promise.resolve(respostaJson(200, identidade))
        : Promise.reject(new TypeError('Failed to fetch')))
      renderizarLogin()

      preencherEEnviar()
      expect(await screen.findByRole('alert')).toBeInTheDocument()

      identidadeResponde = true
      fireEvent.click(screen.getByRole('button', { name: 'Entrar' }))

      expect(await screen.findByText('dentro do aplicativo')).toBeInTheDocument()
      expect(lerIdentidade()).toEqual(identidade)
      expect(sessaoVenceu()).toBe(false)
    })
  })

  describe('o foco depois do erro', () => {
    /**
     * O jsdom não tira o foco do botão desligado, como o navegador faz: com o foco na senha, o teste só
     * passa se a tela o levar ao botão de verdade. O foco vem num efeito, que roda depois do desenho que
     * mostrou o erro, e por isso a afirmação espera.
     */
    function enviarComOFocoNaSenha() {
      screen.getByLabelText('Senha').focus()
      preencherEEnviar()
    }

    it('volta ao botão religado, sem resposta do servidor', async () => {
      vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')))
      renderizarLogin()

      enviarComOFocoNaSenha()

      await screen.findByRole('alert')
      const botao = screen.getByRole('button', { name: 'Entrar' })
      expect(botao).toBeEnabled()
      await waitFor(() => expect(botao).toHaveFocus())
    })

    it('volta ao botão religado também na senha recusada', async () => {
      vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(null, { status: 401 })))
      renderizarLogin()

      enviarComOFocoNaSenha()

      expect(await screen.findByRole('alert')).toHaveTextContent('E-mail ou senha incorretos.')
      await waitFor(() => expect(screen.getByRole('button', { name: 'Entrar' })).toHaveFocus())
    })
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

describe('a entrada trazida por outra aba do aplicativo', () => {
  const ana = { usuarioId: '1', nome: 'Ana', perfil: 'OPERADOR' as const, contaId: '2',
    nomeNegocio: 'Cafeteria Aurora', estoqueHabilitado: false }
  const abertos: BroadcastChannel[] = []

  afterEach(() => {
    for (const canal of abertos.splice(0)) canal.close()
  })

  function renderizarAplicativo() {
    return render(
      <MemoryRouter initialEntries={['/caixa']}>
        <SessaoProvider>
          <Routes>
            <Route element={<ExigeSessao />}>
              <Route path="/caixa" element={<p>tela do caixa</p>} />
            </Route>
            <Route path="/entrar" element={<TelaDeLogin />} />
            <Route path="/" element={<p>dentro do aplicativo</p>} />
          </Routes>
        </SessaoProvider>
      </MemoryRouter>,
    )
  }

  /** Esta aba dentro do aplicativo, com a pergunta da abertura sem resposta. */
  function dentroDoAplicativo(): string {
    gravarToken(tokenDoLogin())
    gravarIdentidade(ana)
    vi.stubGlobal('fetch', vi.fn(() => new Promise<Response>(() => undefined)))
    return sessaoAtual() as string
  }

  // O que a outra aba faz no armazenamento compartilhado, e o evento que o navegador entrega a esta.
  function outraAbaSaiu(sessao: string) {
    localStorage.removeItem(`ecrx-caixa.token.${sessao}`)
    localStorage.removeItem(CHAVE_DA_SESSAO)
    fireEvent(window, new StorageEvent('storage', { key: CHAVE_DA_SESSAO, oldValue: sessao, newValue: null }))
  }

  function outraAbaEntrou(anterior: string | null): string {
    const nova = crypto.randomUUID()
    localStorage.setItem(`ecrx-caixa.token.${nova}`, tokenDoLogin())
    localStorage.setItem(CHAVE_DA_SESSAO, nova)
    if (anterior) localStorage.removeItem(`ecrx-caixa.token.${anterior}`)
    fireEvent(window, new StorageEvent('storage', { key: CHAVE_DA_SESSAO, oldValue: anterior, newValue: nova }))
    return nova
  }

  it('alguém sai em outra aba: esta volta à entrada dizendo por quê, também depois de recarregar', async () => {
    const sessao = dentroDoAplicativo()
    const { unmount } = renderizarAplicativo()
    expect(screen.getByText('tela do caixa')).toBeInTheDocument()

    outraAbaSaiu(sessao)

    expect(await screen.findByRole('status')).toHaveTextContent(new RegExp(`^${FRASE_DE_QUEM_SAIU}$`))
    unmount()
    renderizarAplicativo()
    expect(screen.getByRole('status')).toHaveTextContent(FRASE_DE_QUEM_SAIU)
  })

  it('alguém entra em outra aba: a frase diz que entrar aqui tira a outra, também depois de recarregar', async () => {
    const sessao = dentroDoAplicativo()
    const { unmount } = renderizarAplicativo()

    outraAbaEntrou(sessao)

    expect(await screen.findByRole('status')).toHaveTextContent(new RegExp(`^${FRASE_DE_QUEM_ENTROU}$`))
    // A recarga continua na entrada, com a sessão da outra aba valendo: a guarda das abas fica.
    unmount()
    renderizarAplicativo()
    expect(screen.getByRole('status')).toHaveTextContent(FRASE_DE_QUEM_ENTROU)
    expect(screen.queryByText('tela do caixa')).not.toBeInTheDocument()
  })

  it('a frase troca com a entrada já à vista, quando alguém entra depois de outro sair', async () => {
    const sessao = dentroDoAplicativo()
    renderizarAplicativo()
    outraAbaSaiu(sessao)
    expect(await screen.findByRole('status')).toHaveTextContent(FRASE_DE_QUEM_SAIU)

    outraAbaEntrou(null)

    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent(FRASE_DE_QUEM_ENTROU))
  })

  it('o 401 em outra aba é dito aqui com a frase do 401, que ganha da de quem saiu', async () => {
    const sessao = dentroDoAplicativo()
    renderizarAplicativo()
    const outra = new BroadcastChannel('ecrx-caixa-abas')
    abertos.push(outra)

    outraAbaSaiu(sessao)
    outra.postMessage({ tipo: 'sessao-encerrada' })

    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent(FRASE_DO_401))
    expect(sessaoFoiEncerrada()).toBe(true)
  })

  it('quem entra em outra aba tira a frase do 401 desta, que falava da sessão trocada', async () => {
    marcarSessaoEncerrada()
    vi.stubGlobal('fetch', vi.fn())
    renderizarLogin()
    expect(screen.getByRole('status')).toHaveTextContent(FRASE_DO_401)

    outraAbaEntrou(null)

    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent(FRASE_DE_QUEM_ENTROU))
    expect(sessaoFoiEncerrada()).toBe(false)
  })

  it('a sessão de quem entrou na outra aba termina: entrar aqui já não tira ninguém, e a frase sai', async () => {
    const sessao = dentroDoAplicativo()
    renderizarAplicativo()
    const nova = outraAbaEntrou(sessao)
    expect(await screen.findByRole('status')).toHaveTextContent(FRASE_DE_QUEM_ENTROU)

    outraAbaSaiu(nova)

    await waitFor(() => expect(screen.queryByRole('status')).not.toBeInTheDocument())
  })

  it('o vencimento ganha da frase de quem saiu, porque também faz a outra aba sair', async () => {
    const sessao = dentroDoAplicativo()
    renderizarAplicativo()

    marcarSessaoVencida()
    outraAbaSaiu(sessao)

    expect(await screen.findByRole('status')).toHaveTextContent(FRASE_DO_VENCIMENTO)
  })

  it('com a frase de outra aba à vista, a de baixo não repete o que fica guardado', async () => {
    const sessao = dentroDoAplicativo()
    renderizarAplicativo()
    outraAbaSaiu(sessao)
    expect(await screen.findByRole('status')).toHaveTextContent(FRASE_DE_QUEM_SAIU)
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')))

    preencherEEnviar()

    expect(await screen.findByRole('alert')).toHaveTextContent(new RegExp('^Sem resposta do servidor. A rede deste '
      + 'aparelho pode estar funcionando; tente de novo em alguns minutos.$'))
  })

  it('a entrada completa nesta aba tira a marca de outra aba', async () => {
    const sessao = dentroDoAplicativo()
    renderizarAplicativo()
    outraAbaEntrou(sessao)
    expect(await screen.findByRole('status')).toHaveTextContent(FRASE_DE_QUEM_ENTROU)
    vi.stubGlobal('fetch', vi.fn()
      .mockResolvedValueOnce(respostaJson(200, { token: tokenDoLogin() }))
      .mockResolvedValueOnce(respostaJson(200, ana)))

    preencherEEnviar()

    expect(await screen.findByText('dentro do aplicativo')).toBeInTheDocument()
    expect(voltaPorOutraAba()).toBeNull()
  })
})

describe('o foco ao abrir a entrada', () => {
  /** O ponteiro principal do aparelho: fino no computador, de toque no celular e no tablet. */
  function ponteiro(fino: boolean) {
    vi.stubGlobal('matchMedia', (consulta: string) => ({ matches: consulta === '(pointer: fine)' && fino,
      addEventListener: vi.fn(), removeEventListener: vi.fn() }))
  }

  it('com mouse ou trackpad, o foco já está no e-mail', () => {
    vi.stubGlobal('fetch', vi.fn())
    ponteiro(true)
    renderizarLogin()

    expect(screen.getByLabelText('E-mail')).toHaveFocus()
  })

  it('com ponteiro de toque, nenhum campo recebe o foco, para o teclado virtual não abrir sozinho', () => {
    vi.stubGlobal('fetch', vi.fn())
    ponteiro(false)
    renderizarLogin()

    expect(screen.getByLabelText('E-mail')).not.toHaveFocus()
    expect(document.body).toHaveFocus()
  })

  it('com a frase de por que voltou à vista, o foco também vai ao e-mail', () => {
    vi.stubGlobal('fetch', vi.fn())
    marcarSessaoVencida()
    ponteiro(true)
    renderizarLogin()

    expect(screen.getByRole('status')).toHaveTextContent(FRASE_DO_VENCIMENTO)
    expect(screen.getByLabelText('E-mail')).toHaveFocus()
  })
})
