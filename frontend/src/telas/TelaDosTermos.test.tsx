import { fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { gravarIdentidade, gravarToken, lerIdentidade } from '../sessao/armazenamento'
import type { Identidade } from '../sessao/Identidade'
import { SessaoProvider } from '../sessao/SessaoProvider'
import { tokenComExpiracao } from '../sessao/tokenDeTeste'
import { ExigeSessao } from '../shell/ExigeSessao'
import { TelaDeLogin } from './TelaDeLogin'
import { TelaDosTermos } from './TelaDosTermos'
import { VERSAO_DOS_TERMOS } from './versaoDosTermos'

afterEach(() => vi.restoreAllMocks())

const bia: Identidade = {
  usuarioId: 'bia', nome: 'Bia', perfil: 'ADMIN', contaId: 'conta',
  nomeNegocio: 'Cafeteria Aurora', estoqueHabilitado: false,
}

const ana: Identidade = { ...bia, usuarioId: 'ana', nome: 'Ana', perfil: 'OPERADOR' }

function respostaJson(status: number, corpo: unknown) {
  return new Response(JSON.stringify(corpo), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

/**
 * O servidor de mentira: a pergunta da abertura fica sem resposta, para a identidade só mudar pela
 * tela, e as outras rotas respondem o que o teste mandar.
 */
function servidor(respostas: Record<string, () => Promise<Response>>) {
  const chamadas: { caminho: string; corpo: unknown }[] = []
  vi.stubGlobal('fetch', vi.fn((caminho: string, opcoes?: RequestInit) => {
    chamadas.push({ caminho, corpo: opcoes?.body ? JSON.parse(String(opcoes.body)) : undefined })
    const resposta = respostas[caminho]
    return resposta ? resposta() : new Promise<Response>(() => undefined)
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
          <Route path="/termos" element={<TelaDosTermos />} />
          <Route element={<ExigeSessao />}>
            <Route path="/" element={<p>dentro do aplicativo</p>} />
            <Route path="/vender" element={<p>no balcão</p>} />
          </Route>
        </Routes>
      </SessaoProvider>
    </MemoryRouter>,
  )
}

const aceite = () => screen.getByRole('button', { name: 'Aceito os termos' })

describe('TelaDosTermos', () => {
  it('é pública, mostra a data da versão e não pede aceite a quem só lê', () => {
    servidor({})
    abrir('/termos')

    expect(screen.getByRole('heading', { name: 'Termos de uso' })).toBeInTheDocument()
    const [ano, mes, dia] = VERSAO_DOS_TERMOS.split('-')
    expect(screen.getByText(new RegExp(`Versão de ${dia}/${mes}/${ano}`))).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Anexo: tratamento de dados pessoais' }))
      .toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Aceito os termos' })).not.toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Voltar para o acesso' })).toBeInTheDocument()
  })

  it('o login aponta para os termos, ao lado do aviso de privacidade', () => {
    servidor({})
    abrir('/entrar')

    expect(screen.getByRole('link', { name: 'Termos de uso' })).toHaveAttribute('href', '/termos')
    expect(screen.getByRole('link', { name: 'Aviso de privacidade' })).toBeInTheDocument()
  })

  it('com a pendência, o administrador só vê os termos, em qualquer rota, até aceitar a versão mostrada', async () => {
    entrarComo({ ...bia, termosPendentes: true })
    const chamadas = servidor({
      '/api/conta/termos': () => Promise.resolve(new Response(null, { status: 204 })),
    })
    abrir('/vender')

    expect(screen.getByRole('heading', { name: 'Termos de uso' })).toBeInTheDocument()
    expect(screen.queryByText('no balcão')).not.toBeInTheDocument()

    fireEvent.click(aceite())

    expect(await screen.findByText('no balcão')).toBeInTheDocument()
    expect(chamadas.find((chamada) => chamada.caminho === '/api/conta/termos')?.corpo)
      .toEqual({ versao: VERSAO_DOS_TERMOS })
    expect(lerIdentidade()?.termosPendentes).toBe(false)
  })

  it('o operador entra mesmo com a pendência: o aceite é do administrador', () => {
    entrarComo({ ...ana, termosPendentes: true })
    servidor({})
    abrir('/vender')

    expect(screen.getByText('no balcão')).toBeInTheDocument()
  })

  it('a senha provisória vem antes dos termos', () => {
    entrarComo({ ...bia, senhaProvisoria: true, termosPendentes: true })
    servidor({})
    abrir('/')

    expect(screen.getByRole('heading', { name: 'Escolha a sua senha' })).toBeInTheDocument()
  })

  it('sem a marca, que a identidade antiga não tem, o administrador entra direto', () => {
    entrarComo(bia)
    servidor({})
    abrir('/')

    expect(screen.getByText('dentro do aplicativo')).toBeInTheDocument()
  })

  it('a versão que o servidor já não aceita pede para recarregar, e a tela continua', async () => {
    entrarComo({ ...bia, termosPendentes: true })
    servidor({
      '/api/conta/termos': () => Promise.resolve(respostaJson(409, {
        title: 'Conflict', detail: 'os termos mudaram desde que o aplicativo foi aberto',
      })),
    })
    abrir('/')

    fireEvent.click(aceite())

    expect(await screen.findByRole('alert')).toHaveTextContent('Recarregue a página')
    expect(screen.queryByText('dentro do aplicativo')).not.toBeInTheDocument()
    expect(lerIdentidade()?.termosPendentes).toBe(true)
  })

  it('sem rede, avisa que o aceite precisa dela', async () => {
    entrarComo({ ...bia, termosPendentes: true })
    servidor({ '/api/conta/termos': () => Promise.reject(new TypeError('Failed to fetch')) })
    abrir('/')

    fireEvent.click(aceite())

    expect(await screen.findByRole('alert')).toHaveTextContent('Aceitar os termos precisa de rede')
    expect(lerIdentidade()?.termosPendentes).toBe(true)
  })
})
