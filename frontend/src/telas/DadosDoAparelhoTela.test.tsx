import 'fake-indexeddb/auto'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { deleteDB } from 'idb'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { contarGestosSemResultado, enfileirarGesto, guardarRetrato, lerRetrato, listarGestos } from '../offline/fila'
import { gravarIdentidade, gravarToken, limparSessao } from '../sessao/armazenamento'
import type { Identidade } from '../sessao/Identidade'
import { SessaoProvider } from '../sessao/SessaoProvider'
import { tokenComExpiracao } from '../sessao/tokenDeTeste'
import { TelaDeLogin } from './TelaDeLogin'

const ana: Identidade = {
  contaId: 'conta-a', usuarioId: 'ana', nome: 'Ana', nomeNegocio: 'Café A',
  perfil: 'ADMIN', estoqueHabilitado: false,
}
const clienteId = '00000000-0000-4000-8000-000000000031'

function entrarComoAna() {
  gravarToken(tokenComExpiracao(new Date(Date.now() + 60 * 60 * 1000)))
  gravarIdentidade(ana)
}

/** A Ana guardou o cadastro e cadastrou um Cliente sem rede; depois saiu sem enviar. */
async function clienteRegistradoSemRede() {
  entrarComoAna()
  await guardarRetrato('clientesAtivos', [{ id: clienteId, nome: 'Joana Lima', contato: 'joana@exemplo.test' }], 0)
  await enfileirarGesto({ tipo: 'cliente.criar', registroId: clienteId,
    payload: { nome: 'Joana Lima', contato: 'joana@exemplo.test' } })
  limparSessao()
}

function renderizarLogin() {
  render(
    <MemoryRouter initialEntries={['/entrar']}>
      <SessaoProvider>
        <Routes>
          <Route path="/entrar" element={<TelaDeLogin />} />
        </Routes>
      </SessaoProvider>
    </MemoryRouter>,
  )
}

function apagar() {
  fireEvent.click(screen.getByRole('button', { name: 'Apagar os dados deste aparelho' }))
}

beforeEach(async () => {
  await deleteDB('caixa-simples-offline')
})

afterEach(() => vi.restoreAllMocks())

describe('apagar os dados do aparelho na tela de entrada', () => {
  it('a pergunta diz que o registro sem resultado se perde, e cancelar não apaga nada', async () => {
    await clienteRegistradoSemRede()
    const confirmar = vi.spyOn(window, 'confirm').mockReturnValue(false)
    renderizarLogin()

    apagar()

    await waitFor(() => expect(confirmar).toHaveBeenCalledTimes(1))
    expect(confirmar.mock.calls[0][0]).toContain(
      'Um registro feito sem rede ainda não chegou ao servidor e será perdido.')
    await waitFor(() => expect(screen.getByRole('button', { name: 'Apagar os dados deste aparelho' })).toBeEnabled())
    expect(screen.queryByRole('status')).not.toBeInTheDocument()
    expect(await contarGestosSemResultado()).toBe(1)
  })

  it('confirmado, apaga a fila e o cadastro guardado e diz que apagou', async () => {
    await clienteRegistradoSemRede()
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    renderizarLogin()

    apagar()

    expect(await screen.findByRole('status')).toHaveTextContent('Os dados deste aparelho foram apagados.')
    entrarComoAna()
    expect(await listarGestos()).toEqual([])
    expect(await lerRetrato('clientesAtivos')).toBeNull()
  })

  it('sem nada pendente, a pergunta avisa que trabalhar sem rede de novo pede entrar com rede', async () => {
    const confirmar = vi.spyOn(window, 'confirm').mockReturnValue(false)
    renderizarLogin()

    apagar()

    await waitFor(() => expect(confirmar).toHaveBeenCalledTimes(1))
    expect(confirmar.mock.calls[0][0]).toContain('será preciso entrar com rede')
  })
})
