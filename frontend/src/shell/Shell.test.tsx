import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router'
import type { RegisterSWOptions } from 'virtual:pwa-register/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cadastro } from '../api/cadastro'
import { caixa } from '../api/caixa'
import { vendas, type Comprovante, type Venda } from '../api/vendas'
import { SessaoContext } from '../sessao/contexto'
import type { Identidade } from '../sessao/Identidade'
import { TelaDeVenda } from '../telas/TelaDeVenda'
import { Shell } from './Shell'

// O registro do service worker só existe no build do Vite. O falso guarda as opções que o Shell
// passou, para o teste entregar o registro, e diz se há versão nova esperando.
const versao = vi.hoisted(() => ({
  esperando: false,
  opcoes: undefined as RegisterSWOptions | undefined,
  atualizar: vi.fn((_recarregar?: boolean) => Promise.resolve()),
}))
vi.mock('virtual:pwa-register/react', () => ({
  useRegisterSW: (opcoes?: RegisterSWOptions) => {
    versao.opcoes = opcoes
    return {
      needRefresh: [versao.esperando, () => undefined],
      offlineReady: [false, () => undefined],
      updateServiceWorker: versao.atualizar,
    }
  },
}))

const UMA_HORA = 60 * 60 * 1000
const AVISO_COM_VENDA = 'Nova versão disponível. Atualize quando a venda terminar.'

const ana: Identidade = {
  usuarioId: 'usuario-1', nome: 'Ana', perfil: 'OPERADOR', contaId: 'conta-1',
  nomeNegocio: 'Loja da Esquina', estoqueHabilitado: false,
}
const sessaoDeCaixa = {
  id: 'sessao-1', usuarioId: ana.usuarioId, valorAbertura: 0, valorFechamentoEsperado: 0,
  valorFechamentoContado: null, diferenca: null, abertaEm: '2026-10-18T12:00:00Z',
  fechadaEm: null, status: 'ABERTA' as const,
}
// Já paga e ainda aberta: falta só o toque de concluir.
const venda: Venda = {
  id: 'venda-1', sessaoCaixaId: sessaoDeCaixa.id, usuarioId: ana.usuarioId,
  clienteId: null, saldoDevedor: 0, recebimentos: [],
  status: 'ABERTA', criadoEm: sessaoDeCaixa.abertaEm, descontoDaVenda: 0, total: 12.5,
  pago: 12.5, faltaPagar: 0,
  itens: [{ id: 'item-1', produtoId: 'produto-1', nome: 'Café', quantidade: 1,
    precoUnitario: 12.5, desconto: 0, subtotal: 12.5 }],
  parcelas: [{ id: 'parcela-1', forma: 'DINHEIRO', valor: 12.5, status: 'CONFIRMADO', troco: 0 }],
}
const comprovante: Comprovante = {
  vendaId: venda.id, usuarioId: venda.usuarioId, concluidoEm: '2026-10-18T12:05:00Z',
  linhas: [{ produtoId: 'produto-1', nome: 'Café', unidade: 'un', quantidade: 1,
    precoUnitario: 12.5, valorBruto: 12.5, desconto: 0, subtotal: 12.5 }],
  somaDosItens: 12.5, descontoDaVenda: 0, valorTotal: 12.5,
  parcelas: [{ forma: 'DINHEIRO', valor: 12.5, troco: 0 }], troco: 0,
  valorFiado: 0, saldoDevedor: 0,
}

beforeEach(() => {
  versao.esperando = false
  versao.opcoes = undefined
  versao.atualizar.mockClear()
  // O que a tela de venda e o envio da fila pedissem fora dos falsos ficaria sem resposta.
  vi.stubGlobal('fetch', vi.fn(() => new Promise<Response>(() => undefined)))
})

afterEach(() => vi.restoreAllMocks())

function abrir(caminho: string) {
  return render(
    <MemoryRouter initialEntries={[caminho]}>
      <SessaoContext.Provider value={{
        identidade: ana, entrar: vi.fn(), sair: vi.fn(), atualizarIdentidade: vi.fn(),
      }}>
        <Routes>
          <Route element={<Shell />}>
            <Route path="vender" element={<TelaDeVenda />} />
            <Route path="caixa" element={<p>Tela do caixa</p>} />
          </Route>
        </Routes>
      </SessaoContext.Provider>
    </MemoryRouter>,
  )
}

/** A venda aberta do caixa, que a lista oferece para retomar e que um toque conclui. */
function vendaParaRetomar() {
  let concluida = false
  vi.spyOn(caixa, 'abertaDoOperadorAtual').mockResolvedValue(sessaoDeCaixa)
  vi.spyOn(cadastro, 'clientes').mockResolvedValue([])
  vi.spyOn(vendas, 'daSessao').mockImplementation(async () => [{ id: venda.id,
    sessaoCaixaId: venda.sessaoCaixaId, usuarioId: venda.usuarioId,
    status: concluida ? 'CONCLUIDA' : 'ABERTA', total: venda.total, criadoEm: venda.criadoEm }])
  vi.spyOn(vendas, 'consultar').mockImplementation(async () =>
    ({ ...venda, status: concluida ? 'CONCLUIDA' : 'ABERTA' }))
  vi.spyOn(vendas, 'concluir').mockImplementation(async () => { concluida = true })
  vi.spyOn(vendas, 'comprovante').mockResolvedValue(comprovante)
}

async function retomar() {
  fireEvent.click(await screen.findByRole('button', { name: /Aberta.*retomar/ }))
  expect(await screen.findByText(AVISO_COM_VENDA)).toBeInTheDocument()
  expect(screen.queryByRole('button', { name: 'Atualizar' })).not.toBeInTheDocument()
}

function registrar(update: () => Promise<void>) {
  const registro = { update } as unknown as ServiceWorkerRegistration
  act(() => versao.opcoes?.onRegisteredSW?.('/sw.js', registro))
}

describe('versão nova no Shell', () => {
  it('fora da venda, a faixa oferece Atualizar, que ativa a versão nova e recarrega', () => {
    versao.esperando = true
    abrir('/caixa')

    expect(screen.getByText('Nova versão disponível.')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Atualizar' }))
    expect(versao.atualizar).toHaveBeenCalledWith(true)
  })

  it('com a venda aberta na tela, a faixa avisa sem o botão, que volta quando a venda conclui', async () => {
    versao.esperando = true
    vendaParaRetomar()
    abrir('/vender')

    // Antes de haver venda na comanda, ainda não há o que perder.
    expect(await screen.findByRole('button', { name: 'Atualizar' })).toBeInTheDocument()
    await retomar()

    fireEvent.click(screen.getByRole('button', { name: 'Concluir venda' }))
    expect(await screen.findByRole('button', { name: 'Atualizar' })).toBeInTheDocument()
    expect(screen.queryByText(AVISO_COM_VENDA)).not.toBeInTheDocument()
  })

  it('o botão volta com a venda deixada pela próxima e com a tela de venda fechada', async () => {
    versao.esperando = true
    vendaParaRetomar()
    abrir('/vender')

    await retomar()
    fireEvent.click(screen.getByRole('button', { name: 'Nova venda' }))
    expect(await screen.findByRole('button', { name: 'Atualizar' })).toBeInTheDocument()

    await retomar()
    fireEvent.click(within(screen.getByRole('navigation', { name: 'Principal' }))
      .getByRole('link', { name: 'Caixa' }))
    expect(await screen.findByText('Tela do caixa')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Atualizar' })).toBeInTheDocument()
  })

  it('sem versão nova, a venda aberta não mostra faixa nenhuma', async () => {
    vendaParaRetomar()
    abrir('/vender')

    fireEvent.click(await screen.findByRole('button', { name: /Aberta.*retomar/ }))
    expect(await screen.findByRole('button', { name: 'Concluir venda' })).toBeInTheDocument()
    expect(screen.queryByText(/Nova versão disponível/)).not.toBeInTheDocument()
  })

  it('procura a versão nova quando o aplicativo volta à tela, e a falha sem rede passa calada', () => {
    // Uma função simples, e não vi.fn: o falso do Vitest observa a promessa que devolve, e a
    // rejeição que o Shell deixasse sem tratamento passaria despercebida.
    let procuras = 0
    const update = () => {
      procuras += 1
      return procuras === 1 ? Promise.reject(new TypeError('Failed to fetch')) : Promise.resolve()
    }
    abrir('/caixa')
    registrar(update)
    const visibilidade = vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('hidden')

    // Ir para o fundo não procura nada.
    act(() => { document.dispatchEvent(new Event('visibilitychange')) })
    expect(procuras).toBe(0)

    visibilidade.mockReturnValue('visible')
    act(() => { document.dispatchEvent(new Event('visibilitychange')) })
    expect(procuras).toBe(1)

    // A primeira procura falhou sem rede; a volta seguinte tenta de novo.
    act(() => { document.dispatchEvent(new Event('visibilitychange')) })
    expect(procuras).toBe(2)
  })

  it('procura de hora em hora com o aplicativo aberto, e para quando o Shell sai da tela', () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] })
    try {
      const update = vi.fn<() => Promise<void>>().mockResolvedValue(undefined)
      const { unmount } = abrir('/caixa')
      registrar(update)

      act(() => { vi.advanceTimersByTime(UMA_HORA - 1) })
      expect(update).not.toHaveBeenCalled()
      act(() => { vi.advanceTimersByTime(1) })
      expect(update).toHaveBeenCalledTimes(1)
      act(() => { vi.advanceTimersByTime(UMA_HORA) })
      expect(update).toHaveBeenCalledTimes(2)

      // Sem o Shell, nada mais procura: a próxima montagem registra a sua própria procura.
      unmount()
      vi.advanceTimersByTime(UMA_HORA)
      expect(update).toHaveBeenCalledTimes(2)
    } finally {
      vi.useRealTimers()
    }
  })
})

describe('primeiros passos no Shell', () => {
  beforeEach(() => localStorage.clear())

  it('aparecem no primeiro acesso, passam pelos três cartões e não voltam depois de começar', () => {
    const primeiro = abrir('/caixa')
    expect(screen.getByText('Primeiros passos (1 de 3)')).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Abra o caixa' })).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Próximo' }))
    expect(screen.getByRole('heading', { name: 'Venda' })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Anterior' }))
    expect(screen.getByRole('heading', { name: 'Abra o caixa' })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Próximo' }))
    fireEvent.click(screen.getByRole('button', { name: 'Próximo' }))
    expect(screen.getByRole('heading', { name: 'Feche o caixa' })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Começar a usar' }))
    expect(screen.queryByText(/Primeiros passos \(/)).not.toBeInTheDocument()

    primeiro.unmount()
    abrir('/caixa')
    expect(screen.queryByText(/Primeiros passos \(/)).not.toBeInTheDocument()
    expect(localStorage.getItem('fechadia.tour-visto.conta-1.usuario-1')).toBe('1')
  })

  it('pular também marca, e outra pessoa no mesmo aparelho vê o tour na primeira vez', () => {
    localStorage.setItem('fechadia.tour-visto.conta-1.outra-pessoa', '1')
    abrir('/caixa')
    fireEvent.click(screen.getByRole('button', { name: 'Pular' }))
    expect(screen.queryByText(/Primeiros passos \(/)).not.toBeInTheDocument()
    expect(localStorage.getItem('fechadia.tour-visto.conta-1.usuario-1')).toBe('1')
  })
})

describe('caixa aberto desde outro dia no Shell', () => {
  it('a faixa diz desde quando e leva ao caixa; com o caixa de hoje, nada', async () => {
    vi.spyOn(caixa, 'abertaDoOperadorAtual').mockResolvedValue(
      { ...sessaoDeCaixa, abertaEm: '2020-03-02T21:02:00Z' })
    const comOntem = abrir('/vender')
    expect(await screen.findByText(
      'Seu caixa está aberto desde 02/03 às 18:02. As vendas de hoje entram nele até você fechá-lo.'))
      .toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Ir ao caixa' })).toHaveAttribute('href', '/caixa')
    comOntem.unmount()

    vi.spyOn(caixa, 'abertaDoOperadorAtual').mockResolvedValue(
      { ...sessaoDeCaixa, abertaEm: new Date().toISOString() })
    abrir('/caixa')
    await waitFor(() => expect(caixa.abertaDoOperadorAtual).toHaveBeenCalled())
    expect(screen.queryByText(/Seu caixa está aberto desde/)).not.toBeInTheDocument()
  })
})

