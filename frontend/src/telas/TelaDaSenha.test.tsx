import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { gravarIdentidade, gravarToken, lerIdentidade, lerToken } from '../sessao/armazenamento'
import type { Identidade } from '../sessao/Identidade'
import { SessaoProvider } from '../sessao/SessaoProvider'
import { tokenComExpiracao } from '../sessao/tokenDeTeste'
import { ExigeSessao } from '../shell/ExigeSessao'
import { responderConfirmacao } from './confirmacaoDeTeste'
import { TelaDaSenha } from './TelaDaSenha'
import { TelaDeLogin } from './TelaDeLogin'

afterEach(() => vi.restoreAllMocks())

const ana: Identidade = {
  usuarioId: 'ana', nome: 'Ana', perfil: 'OPERADOR', contaId: 'conta',
  nomeNegocio: 'Cafeteria Aurora', estoqueHabilitado: false,
}

const FRASE_NOVA = 'uma frase nova e comprida de verdade'

function respostaJson(status: number, corpo: unknown, cabecalhos: Record<string, string> = {}) {
  return new Response(JSON.stringify(corpo), {
    status,
    headers: { 'Content-Type': 'application/json', ...cabecalhos },
  })
}

/**
 * O servidor de mentira: a pergunta da abertura fica sem resposta, para a identidade só mudar pela
 * tela, e as outras rotas respondem o que o teste mandar.
 */
function servidor(respostas: Record<string, () => Response>) {
  const chamadas: { caminho: string; corpo: unknown }[] = []
  vi.stubGlobal('fetch', vi.fn((caminho: string, opcoes?: RequestInit) => {
    chamadas.push({ caminho, corpo: opcoes?.body ? JSON.parse(String(opcoes.body)) : undefined })
    const resposta = respostas[caminho]
    return resposta ? Promise.resolve(resposta()) : new Promise<Response>(() => undefined)
  }))
  return chamadas
}

function entrarComo(identidade: Identidade) {
  gravarToken(tokenComExpiracao(new Date(Date.now() + 60 * 60 * 1000)))
  gravarIdentidade(identidade)
}

function abrir(caminho: string) {
  render(
    <MemoryRouter initialEntries={[caminho]}>
      <SessaoProvider>
        <Routes>
          <Route path="/entrar" element={<TelaDeLogin />} />
          <Route element={<ExigeSessao />}>
            <Route path="/" element={<p>dentro do aplicativo</p>} />
            <Route path="/senha" element={<TelaDaSenha />} />
          </Route>
        </Routes>
      </SessaoProvider>
    </MemoryRouter>,
  )
}

function preencher(atual: string, nova: string, repetida = nova) {
  fireEvent.change(screen.getByLabelText('Senha atual'), { target: { value: atual } })
  fireEvent.change(screen.getByLabelText('Senha nova'), { target: { value: nova } })
  fireEvent.change(screen.getByLabelText('Repita a senha nova'), { target: { value: repetida } })
  fireEvent.click(screen.getByRole('button', { name: 'Trocar a senha' }))
}

describe('TelaDaSenha', () => {
  it('com a senha provisória, só a troca aparece, em qualquer rota, até a pessoa escolher a dela', async () => {
    entrarComo({ ...ana, senhaProvisoria: true })
    const chamadas = servidor({
      '/api/auth/senha': () => respostaJson(200, { token: 'token-novo' },
        { 'X-Caixa-Simples-Token': 'token-da-renovacao-anterior' }),
    })
    abrir('/')

    expect(screen.getByRole('heading', { name: 'Escolha a sua senha' })).toBeInTheDocument()
    expect(screen.queryByText('dentro do aplicativo')).not.toBeInTheDocument()

    preencher('abc', FRASE_NOVA)

    expect(await screen.findByText('dentro do aplicativo')).toBeInTheDocument()
    expect(chamadas.find((chamada) => chamada.caminho === '/api/auth/senha')?.corpo)
      .toEqual({ senhaAtual: 'abc', senhaNova: FRASE_NOVA })
    // O token do corpo vence o do cabeçalho, emitido antes da troca com a versão anterior.
    expect(lerToken()).toBe('token-novo')
    expect(lerIdentidade()?.senhaProvisoria).toBe(false)
  })

  it('sem a marca, a pessoa entra direto, como antes dela', () => {
    entrarComo(ana)
    servidor({})
    abrir('/')

    expect(screen.getByText('dentro do aplicativo')).toBeInTheDocument()
  })

  it('recusa no aparelho a senha curta, a longa demais e a repetição diferente, sem enviar', () => {
    entrarComo(ana)
    const chamadas = servidor({})
    abrir('/senha')

    preencher('abc', 'curta')
    expect(screen.getByRole('alert')).toHaveTextContent('ao menos 15 caracteres')

    // 71 letras e um á somam 73 bytes em 72 caracteres.
    preencher('abc', 'a'.repeat(71) + 'á')
    expect(screen.getByRole('alert')).toHaveTextContent('no máximo 72 letras sem acento')

    preencher('abc', FRASE_NOVA, FRASE_NOVA + ' com outra ponta')
    expect(screen.getByRole('alert')).toHaveTextContent('não são iguais')

    expect(chamadas.map((chamada) => chamada.caminho)).not.toContain('/api/auth/senha')
  })

  it('a senha atual errada mostra a recusa do servidor e mantém a sessão', async () => {
    entrarComo(ana)
    servidor({
      '/api/auth/senha': () => respostaJson(400, { title: 'Bad Request', detail: 'A senha atual não confere.' }),
    })
    abrir('/senha')

    preencher('abc', FRASE_NOVA)

    expect(await screen.findByRole('alert')).toHaveTextContent('A senha atual não confere.')
    expect(lerToken()).not.toBeNull()
  })

  it('a troca fora da obrigatória avisa que os outros aparelhos saíram', async () => {
    entrarComo(ana)
    servidor({ '/api/auth/senha': () => respostaJson(200, { token: 'token-novo' }) })
    abrir('/senha')

    preencher('abc', FRASE_NOVA)

    expect(await screen.findByRole('status')).toHaveTextContent('Os outros aparelhos saíram')
    expect(lerToken()).toBe('token-novo')
  })

  it('mostra o nome, o e-mail e o perfil lidos do servidor, sem guardar o e-mail no aparelho', async () => {
    entrarComo(ana)
    servidor({
      '/api/auth/meus-dados': () => respostaJson(200,
        { nome: 'Ana', email: 'ana@exemplo.test', perfil: 'OPERADOR' }),
    })
    abrir('/senha')

    expect(await screen.findByText('ana@exemplo.test')).toBeInTheDocument()
    expect(screen.getByText('Operador')).toBeInTheDocument()
    const guardado: string[] = []
    for (const armazenamento of [localStorage, sessionStorage]) {
      for (let i = 0; i < armazenamento.length; i++) {
        guardado.push(armazenamento.getItem(armazenamento.key(i) ?? '') ?? '')
      }
    }
    expect(guardado.join('\n')).toContain('Cafeteria Aurora')
    expect(guardado.join('\n')).not.toContain('ana@exemplo.test')
  })

  it('troca o e-mail com a senha atual e mostra o novo; a repetição diferente nem é enviada', async () => {
    entrarComo(ana)
    const chamadas = servidor({
      '/api/auth/meus-dados': () => respostaJson(200,
        { nome: 'Ana', email: 'ana@exemplo.test', perfil: 'OPERADOR' }),
      '/api/auth/email': () => new Response(null, { status: 204 }),
    })
    abrir('/senha')
    expect(await screen.findByText('ana@exemplo.test')).toBeInTheDocument()

    fireEvent.change(screen.getByLabelText('E-mail novo'), { target: { value: 'Ana.Lima@Exemplo.Test' } })
    fireEvent.change(screen.getByLabelText('Repita o e-mail novo'), { target: { value: 'ana.lima@exemplo.tst' } })
    fireEvent.change(screen.getByLabelText('Senha atual, para confirmar'), { target: { value: 'abc' } })
    fireEvent.click(screen.getByRole('button', { name: 'Trocar o e-mail' }))
    expect(screen.getByRole('alert')).toHaveTextContent('não são iguais')
    expect(chamadas.map((chamada) => chamada.caminho)).not.toContain('/api/auth/email')

    fireEvent.change(screen.getByLabelText('Repita o e-mail novo'), { target: { value: 'ana.lima@exemplo.test' } })
    fireEvent.click(screen.getByRole('button', { name: 'Trocar o e-mail' }))

    expect(await screen.findByRole('status')).toHaveTextContent('entre com o e-mail novo')
    expect(screen.getByText('ana.lima@exemplo.test')).toBeInTheDocument()
    expect(chamadas.find((chamada) => chamada.caminho === '/api/auth/email')?.corpo)
      .toEqual({ senhaAtual: 'abc', emailNovo: 'Ana.Lima@Exemplo.Test' })
    // Os aparelhos seguem dentro: a troca do e-mail não mexe no token.
    expect(lerToken()).not.toBeNull()
  })

  it('a senha atual errada na troca do e-mail mostra a recusa do servidor e mantém o e-mail', async () => {
    entrarComo(ana)
    servidor({
      '/api/auth/meus-dados': () => respostaJson(200,
        { nome: 'Ana', email: 'ana@exemplo.test', perfil: 'OPERADOR' }),
      '/api/auth/email': () => respostaJson(400, { title: 'Bad Request', detail: 'A senha atual não confere.' }),
    })
    abrir('/senha')
    expect(await screen.findByText('ana@exemplo.test')).toBeInTheDocument()

    fireEvent.change(screen.getByLabelText('E-mail novo'), { target: { value: 'outro@exemplo.test' } })
    fireEvent.change(screen.getByLabelText('Repita o e-mail novo'), { target: { value: 'outro@exemplo.test' } })
    fireEvent.change(screen.getByLabelText('Senha atual, para confirmar'), { target: { value: 'errada' } })
    fireEvent.click(screen.getByRole('button', { name: 'Trocar o e-mail' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('A senha atual não confere.')
    expect(screen.getByText('ana@exemplo.test')).toBeInTheDocument()
    expect(lerToken()).not.toBeNull()
  })

  it('sair de todos os aparelhos encerra também este e o login avisa que a fila continua guardada', async () => {
    entrarComo(ana)
    const chamadas = servidor({ '/api/auth/sair-de-todos': () => new Response(null, { status: 204 }) })
    abrir('/senha')

    fireEvent.click(screen.getByRole('button', { name: 'Sair de todos os aparelhos' }))
    await responderConfirmacao('Sair de todos')

    expect(await screen.findByRole('status')).toHaveTextContent('sessão neste aparelho foi encerrada')
    await waitFor(() => expect(lerToken()).toBeNull())
    expect(chamadas.map((chamada) => chamada.caminho)).toContain('/api/auth/sair-de-todos')
  })
})
