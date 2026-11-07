import 'fake-indexeddb/auto'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { deleteDB } from 'idb'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  conferirGestos, enfileirarGesto, gestosDeCadaUsuario, guardarRetrato, iniciarEnvio, lerRetrato, listarGestos,
  registrarDesfechos, type GestoNaFila,
} from '../offline/fila'
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
const bia: Identidade = { ...ana, usuarioId: 'bia', nome: 'Bia', perfil: 'OPERADOR' }
const clienteId = '00000000-0000-4000-8000-000000000031'
const vendaId = '00000000-0000-4000-8000-000000000011'
const outraVendaId = '00000000-0000-4000-8000-000000000012'
const sessaoId = '00000000-0000-4000-8000-000000000021'

function entrarComo(identidade: Identidade) {
  gravarToken(tokenComExpiracao(new Date(Date.now() + 60 * 60 * 1000)))
  gravarIdentidade(identidade)
}

function entrarComoAna() {
  entrarComo(ana)
}

/** Uma venda de um item em dinheiro, gesto a gesto, como o balcão grava sem rede: quatro gestos, um registro. */
async function vendaNaFila(id: string): Promise<GestoNaFila[]> {
  const agora = new Date().toISOString()
  const inicio = await enfileirarGesto({ tipo: 'venda.iniciar', registroId: id,
    payload: { sessaoCaixaId: sessaoId, criadoEm: agora } })
  const item = await enfileirarGesto({ tipo: 'venda.adicionarItem', registroId: id, dependeDe: [inicio.operacaoId],
    payload: { itemId: `item-${id}`, produtoId: 'produto-1', nome: 'Sonho', unidade: 'un', quantidade: 1,
      precoUnitario: 9.5, desconto: 0, versaoProduto: 1 } })
  const parcela = await enfileirarGesto({ tipo: 'venda.registrarPagamento', registroId: id,
    dependeDe: [item.operacaoId], payload: { pagamentoId: `parcela-${id}`, forma: 'DINHEIRO', valor: 9.5, valorRecebido: 10 } })
  const conclusao = await enfileirarGesto({ tipo: 'venda.concluir', registroId: id, dependeDe: [parcela.operacaoId],
    payload: { concluidoEm: agora } })
  return [inicio, item, parcela, conclusao]
}

/** O servidor aplicou a venda e pôs a conclusão em revisão, como faz com o relógio adiantado. */
async function vendaEmRevisao(id: string): Promise<GestoNaFila[]> {
  const gestos = await vendaNaFila(id)
  await iniciarEnvio(gestos.map((gesto) => gesto.operacaoId))
  await registrarDesfechos(gestos.map((gesto, indice) => indice < 3
    ? { operacaoId: gesto.operacaoId, estado: 'sent' as const, resultado: { aplicada: true } }
    : { operacaoId: gesto.operacaoId, estado: 'needs_review' as const,
      resultado: { aplicada: true, detalhe: 'O relógio do aparelho estava adiantado.' } }))
  return gestos
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
  await deleteDB('ecrx-caixa-offline')
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

    expect(await screen.findByText('1 registro ainda não chegou ao servidor e será perdido se você apagar agora. '
      + 'Para enviá-lo, entre com rede usando o usuário que o fez e espere o envio.')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('link', { name: 'Voltar à entrada' }))

    expect(await screen.findByRole('button', { name: 'Entrar' })).toBeInTheDocument()
    expect((await gestosDeCadaUsuario()).flat()).toHaveLength(1)
  })

  it('conta a venda como um registro, como o cabeçalho, e não pelos quatro gestos dela', async () => {
    entrarComoAna()
    await vendaNaFila(vendaId)
    await enfileirarGesto({ tipo: 'caixa.sangrar', registroId: sessaoId,
      payload: { valor: 20, motivo: 'Cofre', criadoEm: new Date().toISOString() } })
    limparSessao()
    abrir('/aparelho')

    expect(await screen.findByText('2 registros ainda não chegaram ao servidor e serão perdidos se você apagar agora. '
      + 'Para enviá-los, entre com rede usando o usuário que os fez e espere o envio.')).toBeInTheDocument()
  })

  it('com a fila de dois usuários, diz quantos registros de quantos usuários se perdem', async () => {
    entrarComoAna()
    await vendaNaFila(vendaId)
    entrarComo(bia)
    await vendaNaFila(outraVendaId)
    await enfileirarGesto({ tipo: 'cliente.criar', registroId: clienteId, payload: { nome: 'Joana Lima', contato: null } })
    limparSessao()
    abrir('/aparelho')

    expect(await screen.findByText('3 registros de 2 usuários ainda não chegaram ao servidor e serão perdidos se você '
      + 'apagar agora. Para enviá-los, entre com rede usando cada um desses usuários e espere o envio.'))
      .toBeInTheDocument()
  })

  it('diz que a revisão ainda não conferida sai do aparelho e continua na lista do administrador', async () => {
    entrarComoAna()
    await vendaEmRevisao(vendaId)
    const conferida = await vendaEmRevisao(outraVendaId)
    await conferirGestos(conferida.map((gesto) => gesto.operacaoId))
    limparSessao()
    abrir('/aparelho')

    // A conferida não entra: o que ela tinha a dizer já foi visto.
    expect(await screen.findByText('Nada do que foi registrado neste aparelho está esperando envio. 1 registro em '
      + 'revisão ainda não foi conferido. Ele sai deste aparelho e continua na lista do administrador.'))
      .toBeInTheDocument()
  })

  it('soma a revisão por conferir à frase do que se perde', async () => {
    entrarComoAna()
    await vendaEmRevisao(vendaId)
    await vendaEmRevisao(outraVendaId)
    await enfileirarGesto({ tipo: 'cliente.criar', registroId: clienteId, payload: { nome: 'Joana Lima', contato: null } })
    limparSessao()
    abrir('/aparelho')

    expect(await screen.findByText('1 registro ainda não chegou ao servidor e será perdido se você apagar agora. Para '
      + 'enviá-lo, entre com rede usando o usuário que o fez e espere o envio. 2 registros em revisão ainda não foram '
      + 'conferidos. Eles saem deste aparelho e continuam na lista do administrador.')).toBeInTheDocument()
  })

  it('apagar tira a fila e o cadastro guardado e diz que apagou', async () => {
    await clienteRegistradoSemRede()
    abrir('/aparelho')
    // O botão espera a contagem: sem ela, não se sabe o que se perderia.
    expect(await screen.findByText(/será perdido/)).toBeInTheDocument()

    const regioesAntes = screen.getAllByRole('status')

    fireEvent.click(screen.getByRole('button', { name: 'Apagar os dados' }))

    const aviso = await screen.findByText('Os dados deste aparelho foram apagados.')
    // A região já estava na página antes do toque: a que nasce junto com o texto pode não ser anunciada.
    expect(regioesAntes).toContain(aviso.closest('[role="status"]'))
    // O botão sumiu sob o dedo; o foco vai à única ação que resta, e não ao corpo da página.
    await waitFor(() => expect(screen.getByRole('link', { name: 'Voltar à entrada' })).toHaveFocus())
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
