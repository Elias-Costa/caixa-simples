import 'fake-indexeddb/auto'
import { fireEvent, render, screen, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { App } from '../App'
import { gravarIdentidade, gravarToken } from '../sessao/armazenamento'
import type { Identidade } from '../sessao/Identidade'
import { SessaoProvider } from '../sessao/SessaoProvider'
import { tokenComExpiracao } from '../sessao/tokenDeTeste'

// O registro do service worker só existe no build do Vite; aqui não há versão nova a oferecer.
vi.mock('virtual:pwa-register/react', () => ({
  useRegisterSW: () => ({
    needRefresh: [false, () => undefined],
    offlineReady: [false, () => undefined],
    updateServiceWorker: () => Promise.resolve(),
  }),
}))

const ana: Identidade = {
  usuarioId: 'ana', nome: 'Ana', perfil: 'OPERADOR', contaId: 'conta',
  nomeNegocio: 'Cafeteria Aurora', estoqueHabilitado: false,
}

const bia: Identidade = { ...ana, usuarioId: 'bia', nome: 'Bia', perfil: 'ADMIN' }

/** As oito seções, uma por inciso do art. 9º da LGPD, mais o legítimo interesse e a versão. */
const SECOES = [
  'Quem trata os dados',
  'Contato',
  'Dados, para que servem e com que base',
  'Registros de segurança',
  'Onde ficam e por quanto tempo',
  'Com quem é compartilhado',
  'Seus direitos',
  'Versões e termos',
]

beforeEach(() => {
  // Sem rede e sem resposta do servidor: a identidade só muda pelo que o teste grava, e a fila do
  // aparelho não tenta enviar nada.
  vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(false)
  vi.stubGlobal('fetch', vi.fn(() => new Promise<Response>(() => undefined)))
})

afterEach(() => vi.restoreAllMocks())

function entrarComo(identidade: Identidade) {
  gravarToken(tokenComExpiracao(new Date(Date.now() + 60 * 60 * 1000)))
  gravarIdentidade(identidade)
}

function abrir(caminho: string) {
  render(
    <MemoryRouter initialEntries={[caminho]}>
      <SessaoProvider>
        <App />
      </SessaoProvider>
    </MemoryRouter>,
  )
}

describe('TelaDePrivacidade', () => {
  it('é pública, com a data da versão, uma seção por inciso e o link para os termos', () => {
    abrir('/privacidade')

    expect(screen.getByRole('heading', { level: 1, name: 'Aviso de privacidade' })).toBeInTheDocument()
    expect(screen.getByText(/Versão de 25\/10\/2026/)).toBeInTheDocument()
    expect(screen.getAllByRole('heading', { level: 2 }).map((titulo) => titulo.textContent))
      .toEqual(SECOES)
    expect(screen.getByRole('link', { name: 'termos de uso' })).toHaveAttribute('href', '/termos')
    expect(screen.getByRole('link', { name: 'Voltar para o acesso' })).toHaveAttribute('href', '/entrar')
  })

  it('publica o Gmail só para o pedido de encerramento, com a transferência informada', () => {
    abrir('/privacidade')

    const gmail = screen.getAllByRole('link', { name: 'ecrprofessional@gmail.com' })
    expect(gmail).toHaveLength(1)
    expect(gmail[0]).toHaveAttribute('href', 'mailto:ecrprofessional@gmail.com')
    const paragrafo = gmail[0].closest('p')
    expect(paragrafo).toHaveTextContent('encerramento da própria Conta')
    expect(paragrafo).toHaveTextContent('fora do Brasil')
    expect(paragrafo).toHaveTextContent('sem dados de Cliente')
  })

  it.each([
    ['o operador', ana],
    ['o administrador', bia],
  ])('%s chega ao aviso e aos termos pelo rodapé e volta ao aplicativo', (_, identidade) => {
    entrarComo(identidade)
    abrir('/')
    // A entrada leva ao PDV ou ao caixa; a barra de navegação é o que prova estar no aplicativo.
    expect(screen.getByRole('navigation', { name: 'Principal' })).toBeInTheDocument()

    fireEvent.click(within(screen.getByRole('contentinfo')).getByRole('link', { name: 'Aviso de privacidade' }))
    expect(screen.getByRole('heading', { level: 1, name: 'Aviso de privacidade' })).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Voltar para o acesso' })).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole('link', { name: 'Voltar para o aplicativo' }))
    expect(screen.getByRole('navigation', { name: 'Principal' })).toBeInTheDocument()

    fireEvent.click(within(screen.getByRole('contentinfo')).getByRole('link', { name: 'Termos de uso' }))
    expect(screen.getByRole('heading', { level: 1, name: 'Termos de uso' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Aceito os termos' })).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole('link', { name: 'Voltar para o aplicativo' }))
    expect(screen.getByRole('navigation', { name: 'Principal' })).toBeInTheDocument()
  })
})
