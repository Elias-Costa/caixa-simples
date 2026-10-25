import 'fake-indexeddb/auto'
import { fireEvent, render, screen } from '@testing-library/react'
import { deleteDB } from 'idb'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { contarGestosSemResultado, enfileirarGesto, guardarRetrato, lerRetrato, listarGestos } from '../offline/fila'
import { gravarIdentidade, gravarToken, limparSessao } from '../sessao/armazenamento'
import type { Identidade } from '../sessao/Identidade'
import { SessaoProvider } from '../sessao/SessaoProvider'
import { tokenComExpiracao } from '../sessao/tokenDeTeste'
import { TelaDeLogin } from './TelaDeLogin'
import { TelaDoAparelho } from './TelaDoAparelho'

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

function abrir(caminho: string) {
  render(
    <MemoryRouter initialEntries={[caminho]}>
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

beforeEach(async () => {
  await deleteDB('fechadia-offline')
})

afterEach(() => vi.restoreAllMocks())

describe('apagar os dados do aparelho', () => {
  it('a entrada só tem o link, e ele leva à página que explica o que sai e o que fica', async () => {
    abrir('/entrar')

    expect(screen.queryByRole('button', { name: /Apagar/ })).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('link', { name: 'Apagar os dados deste aparelho' }))

    expect(await screen.findByRole('heading', { name: 'Apagar os dados deste aparelho' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Sai deste aparelho' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Continua no servidor' })).toBeInTheDocument()
  })

  it('diz antes do toque que o registro sem resultado se perde, e voltar não apaga nada', async () => {
    await clienteRegistradoSemRede()
    abrir('/aparelho')

    expect(await screen.findByText(/Um registro feito sem rede ainda não chegou ao servidor e será perdido/))
      .toBeInTheDocument()
    fireEvent.click(screen.getByRole('link', { name: 'Voltar à entrada' }))

    expect(await screen.findByRole('button', { name: 'Entrar' })).toBeInTheDocument()
    expect(await contarGestosSemResultado()).toBe(1)
  })

  it('apagar tira a fila e o cadastro guardado e diz que apagou', async () => {
    await clienteRegistradoSemRede()
    abrir('/aparelho')
    // O botão espera a contagem: sem ela, não se sabe o que se perderia.
    expect(await screen.findByText(/será perdido/)).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Apagar os dados' }))

    expect(await screen.findByText('Os dados deste aparelho foram apagados.')).toBeInTheDocument()
    entrarComoAna()
    expect(await listarGestos()).toEqual([])
    expect(await lerRetrato('clientesAtivos')).toBeNull()
  })

  it('sem nada pendente, diz que nada espera envio', async () => {
    abrir('/aparelho')

    expect(await screen.findByText('Nada do que foi registrado neste aparelho está esperando envio.'))
      .toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Apagar os dados' })).toBeEnabled()
  })

  it('com uma sessão aberta no aparelho, pede para sair antes e não oferece o botão', async () => {
    // A identidade guardada faz o aplicativo perguntar ao servidor quem entrou; a pergunta fica sem
    // resposta, porque a página não depende dela.
    vi.stubGlobal('fetch', vi.fn(() => new Promise(() => undefined)))
    entrarComoAna()
    abrir('/aparelho')

    expect(await screen.findByText(/Saia dela antes de apagar os dados/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Apagar os dados' })).not.toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Voltar ao aplicativo' })).toBeInTheDocument()
  })
})
