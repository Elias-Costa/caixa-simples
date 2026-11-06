import 'fake-indexeddb/auto'
import { deleteDB } from 'idb'
import { fireEvent, render, screen, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { registrarResposta, registrarSemResposta } from '../api/conexao'
import { enfileirarGesto } from '../offline/fila'
import { gravarIdentidade, gravarToken } from '../sessao/armazenamento'
import { SessaoContext } from '../sessao/contexto'
import type { Identidade } from '../sessao/Identidade'
import { tokenComExpiracao } from '../sessao/tokenDeTeste'
import { confirmacaoAberta, responderConfirmacao } from '../telas/confirmacaoDeTeste'
import { Shell } from './Shell'

// O registro do service worker só existe no build do Vite.
vi.mock('virtual:pwa-register/react', () => ({
  useRegisterSW: () => ({
    needRefresh: [false, () => undefined],
    offlineReady: [false, () => undefined],
    updateServiceWorker: () => Promise.resolve(),
  }),
}))

const ana: Identidade = {
  usuarioId: 'usuario-1', nome: 'Ana', perfil: 'OPERADOR', contaId: 'conta-1',
  nomeNegocio: 'Loja da Esquina', estoqueHabilitado: false,
}

function clienteNaFila() {
  return enfileirarGesto({ tipo: 'cliente.criar', registroId: crypto.randomUUID(),
    payload: { nome: 'Maria', contato: null } })
}

function semRede() {
  vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(false)
}

function abrir() {
  const sair = vi.fn()
  render(
    <MemoryRouter initialEntries={['/caixa']}>
      <SessaoContext.Provider value={{
        identidade: ana, entrar: vi.fn(), sair, atualizarIdentidade: vi.fn(), relerIdentidade: vi.fn(),
      }}>
        <Routes>
          <Route element={<Shell />}>
            <Route path="caixa" element={<p>Tela do caixa</p>} />
          </Route>
          <Route path="entrar" element={<p>Tela de entrada</p>} />
        </Routes>
      </SessaoContext.Provider>
    </MemoryRouter>,
  )
  return sair
}

function tocarEmSair() {
  fireEvent.click(within(screen.getByRole('banner')).getByRole('button', { name: 'Sair' }))
}

beforeEach(async () => {
  await deleteDB('ecrx-caixa-offline')
  vi.stubGlobal('fetch', vi.fn(() => new Promise<Response>(() => undefined)))
  gravarToken(tokenComExpiracao(new Date(Date.now() + 60 * 60 * 1000)))
  gravarIdentidade(ana)
  registrarResposta()
})

afterEach(() => {
  registrarResposta()
  vi.restoreAllMocks()
})

describe('Sair', () => {
  it('com servidor, sai sem perguntar', () => {
    const sair = abrir()

    tocarEmSair()

    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
    expect(sair).toHaveBeenCalledTimes(1)
  })

  it('sem rede, pergunta com o foco em Voltar, e Voltar não sai', async () => {
    semRede()
    await clienteNaFila()
    const sair = abrir()
    await screen.findByRole('link', { name: '1 por enviar' })

    tocarEmSair()

    const dialogo = await confirmacaoAberta()
    expect(within(dialogo).getByRole('heading', { name: 'Sair sem rede?' })).toBeInTheDocument()
    expect(dialogo).toHaveTextContent(
      'Você só entra de novo quando a rede voltar. 1 registro seu continua guardado neste aparelho.')
    expect(within(dialogo).getByRole('button', { name: 'Voltar' })).toHaveFocus()
    expect(sair).not.toHaveBeenCalled()

    await responderConfirmacao('Voltar')

    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
    expect(sair).not.toHaveBeenCalled()
    expect(screen.getByText('Tela do caixa')).toBeInTheDocument()
  })

  it('sem rede, confirmar sai e leva à entrada', async () => {
    semRede()
    const sair = abrir()

    tocarEmSair()
    await responderConfirmacao('Sair')

    expect(await screen.findByText('Tela de entrada')).toBeInTheDocument()
    expect(sair).toHaveBeenCalledTimes(1)
  })

  it('sem resposta do servidor, a frase fala do servidor e não da rede, com o plural', async () => {
    registrarSemResposta()
    await clienteNaFila()
    await clienteNaFila()
    await clienteNaFila()
    abrir()
    await screen.findByRole('link', { name: '3 por enviar' })

    tocarEmSair()

    const dialogo = await confirmacaoAberta()
    expect(within(dialogo).getByRole('heading', { name: 'Sair sem resposta do servidor?' })).toBeInTheDocument()
    expect(dialogo).toHaveTextContent(
      'Você só entra de novo quando o servidor responder. 3 registros seus continuam guardados neste aparelho.')
  })

  it('sem nada na fila, não fala de registro nenhum', async () => {
    semRede()
    abrir()

    tocarEmSair()

    const dialogo = await confirmacaoAberta()
    expect(dialogo).toHaveTextContent('Você só entra de novo quando a rede voltar.')
    expect(dialogo).not.toHaveTextContent('registro')
  })

  it('o número conta só os registros de quem está saindo, não os de outra pessoa no aparelho', async () => {
    gravarIdentidade({ ...ana, usuarioId: 'usuario-2', nome: 'Bruno' })
    await clienteNaFila()
    await clienteNaFila()
    gravarIdentidade(ana)
    await clienteNaFila()
    semRede()
    abrir()
    await screen.findByRole('link', { name: '1 por enviar' })

    tocarEmSair()

    expect(await confirmacaoAberta()).toHaveTextContent('1 registro seu continua guardado neste aparelho.')
  })
})
