import 'fake-indexeddb/auto'
import { deleteDB } from 'idb'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { registrarResposta } from '../api/conexao'
import { contas } from '../api/contas'
import { sincronizacao, type RevisaoDaConta } from '../api/sincronizacao'
import { enfileirarGesto, iniciarEnvio, listarGestos, registrarDesfechos, type GestoNaFila } from '../offline/fila'
import { gravarIdentidade, gravarToken } from '../sessao/armazenamento'
import { SessaoContext } from '../sessao/contexto'
import type { Identidade } from '../sessao/Identidade'
import { tokenComExpiracao } from '../sessao/tokenDeTeste'
import { TelaDeSincronizacao } from '../telas/TelaDeSincronizacao'
import { TituloComAjuda } from '../telas/TituloComAjuda'
import { Shell } from './Shell'

vi.mock('virtual:pwa-register/react', () => ({
  useRegisterSW: () => ({
    needRefresh: [false, () => undefined],
    offlineReady: [false, () => undefined],
    updateServiceWorker: () => Promise.resolve(),
  }),
}))

const ana: Identidade = {
  usuarioId: 'ana', nome: 'Ana', perfil: 'OPERADOR', contaId: 'conta-a',
  nomeNegocio: 'Cafeteria Aurora', estoqueHabilitado: false,
}
const SESSAO = '00000000-0000-4000-8000-000000000021'
const espera = { timeout: 5000 }

/** Uma venda em dinheiro que o servidor recusou: o início valeu, a parcela não, e a conclusão parou junto. */
async function vendaRecusada(vendaId: string, valor: number): Promise<GestoNaFila[]> {
  const agora = new Date().toISOString()
  const inicio = await enfileirarGesto({ tipo: 'venda.iniciar', registroId: vendaId,
    payload: { sessaoCaixaId: SESSAO, criadoEm: agora } })
  const item = await enfileirarGesto({ tipo: 'venda.adicionarItem', registroId: vendaId,
    payload: { itemId: `item-${vendaId}`, produtoId: 'produto-1', nome: 'Sonho', quantidade: 1,
      precoUnitario: valor, desconto: 0, versaoProduto: 1 }, dependeDe: [inicio.operacaoId] })
  const parcela = await enfileirarGesto({ tipo: 'venda.registrarPagamento', registroId: vendaId,
    payload: { pagamentoId: `parcela-${vendaId}`, forma: 'DINHEIRO', valor, valorRecebido: valor },
    dependeDe: [item.operacaoId] })
  const conclusao = await enfileirarGesto({ tipo: 'venda.concluir', registroId: vendaId,
    payload: { concluidoEm: agora }, dependeDe: [parcela.operacaoId] })
  const gestos = [inicio, item, parcela, conclusao]
  await iniciarEnvio(gestos.map((gesto) => gesto.operacaoId))
  await registrarDesfechos([
    { operacaoId: inicio.operacaoId, estado: 'sent', resultado: { aplicada: true } },
    { operacaoId: item.operacaoId, estado: 'sent', resultado: { aplicada: true } },
    { operacaoId: parcela.operacaoId, estado: 'needs_review',
      resultado: { aplicada: false, detalhe: 'Esta venda já está cancelada e não aceita mais alteração.' } },
    { operacaoId: conclusao.operacaoId, estado: 'needs_review',
      resultado: { aplicada: false, detalhe: 'A venda parou no passo anterior, que foi recusado.' } },
  ])
  return gestos
}

function abrir(caminho: string, identidade: Identidade = ana) {
  return render(
    <MemoryRouter initialEntries={[caminho]}>
      <SessaoContext.Provider value={{
        identidade, entrar: vi.fn(), sair: vi.fn(), atualizarIdentidade: vi.fn(), relerIdentidade: vi.fn(),
      }}>
        <Routes>
          <Route element={<Shell />}>
            <Route path="vender" element={<TituloComAjuda titulo="Venda" ajuda={[]} />} />
            <Route path="caixa" element={<TituloComAjuda titulo="Caixa" ajuda={[]} />} />
            <Route path="produtos" element={<TituloComAjuda titulo="Produtos" ajuda={[]} />} />
            <Route path="sincronizacao" element={<TelaDeSincronizacao />} />
          </Route>
        </Routes>
      </SessaoContext.Provider>
    </MemoryRouter>,
  )
}

const faixa = () => screen.findByText(/feita sem rede/, undefined, espera)

beforeEach(async () => {
  await deleteDB('ecrx-caixa-offline')
  gravarToken(tokenComExpiracao(new Date(Date.now() + 60 * 60 * 1000)))
  gravarIdentidade(ana)
  // O que a tela e o envio da fila pedissem ficaria sem resposta, sem mudar o sinal de conexão.
  vi.stubGlobal('fetch', vi.fn(() => new Promise<Response>(() => undefined)))
  // Uma chamada atrasada do teste anterior, já sem o fetch falso, pode ter marcado o aparelho sem servidor.
  registrarResposta()
})

afterEach(() => {
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

describe('a faixa da venda recusada', () => {
  it('aparece no Vender e no Caixa, com o valor, o motivo e o dinheiro fora do saldo', async () => {
    await vendaRecusada('00000000-0000-4000-8000-000000000011', 12.4)

    const noPdv = abrir('/vender')
    const texto = (await faixa()).textContent?.replaceAll('\u00a0', ' ')
    expect(texto).toMatch(/^A venda de R\$ 12,40 das \d\d:\d\d, feita sem rede, não foi concluída no servidor\. /)
    expect(texto).toContain('Esta venda já está cancelada e não aceita mais alteração.')
    expect(texto).not.toContain('parou no passo anterior')
    expect(texto).toContain('O dinheiro dela, R$ 12,40, está na gaveta e fora do saldo esperado: avise o administrador.')
    noPdv.unmount()

    abrir('/caixa')
    expect(await faixa()).toBeInTheDocument()
  })

  it('não aparece nas outras telas, onde o número do cabeçalho continua avisando', async () => {
    await vendaRecusada('00000000-0000-4000-8000-000000000011', 12.4)

    abrir('/produtos')

    expect(await within(screen.getByRole('banner')).findByText('1 em revisão', undefined, espera)).toBeInTheDocument()
    expect(screen.queryByText(/feita sem rede/)).not.toBeInTheDocument()
  })

  it('Conferir tira a faixa e baixa o número do cabeçalho junto, e o gesto continua em revisão', async () => {
    const gestos = await vendaRecusada('00000000-0000-4000-8000-000000000011', 12.4)
    abrir('/caixa')
    await faixa()
    const cabecalho = within(screen.getByRole('banner'))
    expect(await cabecalho.findByText('1 em revisão', undefined, espera)).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Conferir' }))

    await waitFor(() => expect(screen.queryByText(/feita sem rede/)).not.toBeInTheDocument(), espera)
    expect(cabecalho.queryByText(/em revisão/)).not.toBeInTheDocument()
    expect(document.activeElement).toBe(screen.getByRole('heading', { name: 'Caixa' }))
    const depois = new Map((await listarGestos()).map((gesto) => [gesto.operacaoId, gesto]))
    expect(depois.get(gestos[2].operacaoId)).toMatchObject({ estado: 'needs_review', conferidoEm: expect.any(String) })
    expect(depois.get(gestos[0].operacaoId)?.conferidoEm).toBeUndefined()
  })

  it('mostra uma venda por vez, a mais antiga, e o foco passa ao Conferir da seguinte', async () => {
    await vendaRecusada('00000000-0000-4000-8000-000000000011', 12.4)
    await vendaRecusada('00000000-0000-4000-8000-000000000012', 7)
    abrir('/vender')

    expect((await faixa()).textContent?.replaceAll('\u00a0', ' ')).toContain('R$ 12,40')
    expect(screen.getAllByRole('button', { name: 'Conferir' })).toHaveLength(1)
    expect(screen.getByText(/Há mais uma venda recusada para conferir\./)).toBeInTheDocument()

    const botao = screen.getByRole('button', { name: 'Conferir' })
    botao.focus()
    fireEvent.click(botao)
    // O navegador tira o foco do botão desligado durante a conferência; o jsdom não tira, nem deixa
    // chamar blur num botão desligado. O foco em outro lugar faz o papel.
    screen.getByRole('heading', { name: 'Venda' }).focus()

    await waitFor(() => expect(screen.getByText(/feita sem rede/).textContent?.replaceAll('\u00a0', ' '))
      .toContain('R$ 7,00'), espera)
    expect(screen.queryByText(/Há mais/)).not.toBeInTheDocument()
    await waitFor(() => expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Conferir' })), espera)
  })

  it('sai quando a venda é conferida na tela Sincronização, e o número baixa junto', async () => {
    await vendaRecusada('00000000-0000-4000-8000-000000000011', 12.4)
    abrir('/sincronizacao')

    fireEvent.click(await screen.findByRole('button', { name: /^Conferir Venda/ }, espera))
    await waitFor(() => expect(within(screen.getByRole('banner')).queryByText(/em revisão/)).not.toBeInTheDocument(), espera)

    fireEvent.click(screen.getByRole('link', { name: 'Vender' }))
    expect(await screen.findByRole('heading', { name: 'Venda' })).toBeInTheDocument()
    expect(screen.queryByText(/feita sem rede/)).not.toBeInTheDocument()
  })

  describe('no administrador, que confere na lista da Conta', () => {
    const dona: Identidade = { ...ana, perfil: 'ADMIN' }

    function naLista(gesto: GestoNaFila): RevisaoDaConta {
      return { operacaoId: gesto.operacaoId, usuarioId: dona.usuarioId, tipo: gesto.tipo, registroId: gesto.registroId,
        payload: gesto.payload, criadaEm: gesto.criadoEm, recebidaEm: gesto.criadoEm, resultado: 'NAO_APLICADA' }
    }

    beforeEach(() => gravarIdentidade(dona))

    it('a faixa leva à lista em vez de conferir, sem mandar avisar quem já é o administrador', async () => {
      const gestos = await vendaRecusada('00000000-0000-4000-8000-000000000011', 12.4)
      vi.spyOn(sincronizacao, 'revisoes').mockResolvedValue({ pendentes: gestos.slice(2).map(naLista), conferidas: [] })

      abrir('/vender', dona)

      const texto = (await faixa()).textContent?.replaceAll(' ', ' ')
      expect(texto).toContain('está na gaveta e fora do saldo esperado.')
      expect(texto).not.toContain('avise o administrador')
      expect(screen.queryByRole('button', { name: 'Conferir' })).not.toBeInTheDocument()
      // O número é o da lista: a venda inteira conta um, como a linha dela lá.
      expect(await within(screen.getByRole('banner')).findByText('1 em revisão', undefined, espera)).toBeInTheDocument()

      vi.spyOn(contas, 'usuarios').mockResolvedValue([{ id: dona.usuarioId, nome: 'Ana', perfil: 'ADMIN', ativo: true }])
      const conferir = vi.spyOn(sincronizacao, 'conferir').mockResolvedValue(undefined)
      fireEvent.click(screen.getByRole('link', { name: 'Ir à Sincronização' }))
      expect(await screen.findByRole('heading', { name: 'Sincronização' })).toBeInTheDocument()
      expect(screen.queryByText(/feita sem rede/)).not.toBeInTheDocument()

      // Conferida na lista, a venda sai dela: o número baixa, e a faixa não volta ao balcão.
      vi.spyOn(sincronizacao, 'revisoes').mockResolvedValue({ pendentes: [], conferidas: [] })
      fireEvent.click(await screen.findByRole('button', { name: /^Conferir Venda/ }, espera))
      await waitFor(() => expect(conferir).toHaveBeenCalledTimes(2), espera)
      await waitFor(() => expect(within(screen.getByRole('banner')).queryByText(/em revisão/)).not.toBeInTheDocument(), espera)
      fireEvent.click(screen.getByRole('link', { name: 'Vender' }))
      expect(await screen.findByRole('heading', { name: 'Venda' })).toBeInTheDocument()
      expect(screen.queryByText(/feita sem rede/)).not.toBeInTheDocument()
      expect((await listarGestos()).filter((gesto) => gesto.estado === 'needs_review')
        .every((gesto) => !!gesto.conferidoEm)).toBe(true)
    })

    it('a faixa sai quando a venda deixa a lista, mesmo conferida em outro aparelho, e o número baixa junto', async () => {
      await vendaRecusada('00000000-0000-4000-8000-000000000011', 12.4)
      vi.spyOn(sincronizacao, 'revisoes').mockResolvedValue({ pendentes: [], conferidas: [] })

      abrir('/vender', dona)

      await waitFor(() => expect(sincronizacao.revisoes).toHaveBeenCalled(), espera)
      await waitFor(() => expect(screen.queryByText(/feita sem rede/)).not.toBeInTheDocument(), espera)
      expect(within(screen.getByRole('banner')).queryByText(/em revisão/)).not.toBeInTheDocument()
    })

    it('sem a lista lida, a faixa e o número ficam com o que este aparelho guarda', async () => {
      await vendaRecusada('00000000-0000-4000-8000-000000000011', 12.4)
      vi.spyOn(sincronizacao, 'revisoes').mockImplementation(() => new Promise(() => undefined))

      abrir('/vender', dona)

      expect(await faixa()).toBeInTheDocument()
      expect(await within(screen.getByRole('banner')).findByText('1 em revisão', undefined, espera)).toBeInTheDocument()
      expect(screen.getByRole('link', { name: 'Ir à Sincronização' })).toBeInTheDocument()
    })
  })
})
