import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { cadastro } from '../api/cadastro'
import { ErroDaApi } from '../api/cliente'
import { contas, type UsuarioDaConta } from '../api/contas'
import { relatorios, type LancamentoEmCartao, type LancamentoEmPix } from '../api/relatorios'
import { hojeNoBalcao } from '../dataDoBalcao'
import { TelaDeRelatorios } from './TelaDeRelatorios'

afterEach(() => vi.restoreAllMocks())

const hoje = hojeNoBalcao()
const titular: UsuarioDaConta = { id: 'usuario-ana', nome: 'Ana', perfil: 'ADMIN', ativo: true }

// O total e a quantidade de vendas por filtro de forma: as quatro formas somam o total sem filtro.
const porForma: Record<string, [number, number]> = {
  TODAS: [37, 2], DINHEIRO: [10, 1], PIX: [20, 1], CARTAO: [7, 1], FIADO: [0, 0],
}

function mostrarData(dia: string): string {
  const [ano, mes, data] = dia.split('-')
  return `${data}/${mes}/${ano}`
}

// Uma parcela com NSU, lançada pela titular às 9h05 do balcão, e um fiado recebido às 10h30 por
// alguém que não está na lista, numa venda cancelada depois e sem NSU.
const lancamentos = (dia: string): LancamentoEmCartao[] => [
  {
    id: 'parcela-1', origem: 'PARCELA', vendaId: '3f2b8c1a-0000-4000-8000-000000000001',
    lancadoEm: `${dia}T12:05:00Z`, valor: 7, operadorId: 'usuario-ana', situacaoDaVenda: 'CONCLUIDA',
    nsu: '004512',
  },
  {
    id: 'recebimento-1', origem: 'RECEBIMENTO', vendaId: '9a8b7c6d-0000-4000-8000-000000000002',
    lancadoEm: `${dia}T13:30:00Z`, valor: 12, operadorId: 'f00dbabe-0000-4000-8000-000000000003',
    situacaoDaVenda: 'CANCELADA',
  },
]

// Um Pix lançado à mão de uma cliente às 9h20, uma cobrança integrada ainda não paga às 11h numa venda
// aberta, e um fiado recebido em Pix às 12h de uma venda cancelada depois.
const pix = (dia: string): LancamentoEmPix[] => [
  {
    id: 'pix-1', origem: 'PARCELA', vendaId: '1a2b3c4d-0000-4000-8000-000000000011',
    lancadoEm: `${dia}T12:20:00Z`, valor: 20, operadorId: 'usuario-ana', situacaoDaVenda: 'CONCLUIDA',
    clienteId: 'cliente-lia', integrado: false, aguardandoConfirmacao: false,
  },
  {
    id: 'pix-2', origem: 'PARCELA', vendaId: '5e6f7a8b-0000-4000-8000-000000000012',
    lancadoEm: `${dia}T14:00:00Z`, valor: 15, operadorId: 'usuario-ana', situacaoDaVenda: 'ABERTA',
    integrado: true, aguardandoConfirmacao: true,
  },
  {
    id: 'pix-3', origem: 'RECEBIMENTO', vendaId: '9c0d1e2f-0000-4000-8000-000000000013',
    lancadoEm: `${dia}T15:00:00Z`, valor: 5, operadorId: 'usuario-ana', situacaoDaVenda: 'CANCELADA',
    clienteId: 'cliente-removido', integrado: false, aguardandoConfirmacao: false,
  },
]

function prepararApi(usuarios: UsuarioDaConta[] = [titular]) {
  vi.spyOn(cadastro, 'clientes').mockResolvedValue([{ id: 'cliente-lia', nome: 'Lia', contato: null }])
  vi.spyOn(cadastro, 'clientesInativos').mockResolvedValue([])
  return {
    pix: vi.spyOn(relatorios, 'conferenciaDoPix').mockImplementation(async (dia) => ({
      dia, lancamentos: pix(dia),
    })),
    conferencia: vi.spyOn(relatorios, 'conferenciaDoCartao').mockImplementation(async (dia) => ({
      dia, lancamentos: lancamentos(dia),
    })),
    faturamento: vi.spyOn(relatorios, 'faturamento').mockImplementation(async (inicio, fim, filtros = {}) => {
      const [total, quantidadeDeVendas] = porForma[filtros.forma ?? 'TODAS']
      return { inicio, fim, total, quantidadeDeVendas }
    }),
    maisVendidos: vi.spyOn(relatorios, 'maisVendidos').mockImplementation(async (inicio, fim) => ({
      inicio, fim, posicoes: [
        { produtoId: 'cafe', nome: 'Café coado', unidade: 'un', quantidade: 5, valor: 22.5 },
        { produtoId: 'queijo', nome: 'Queijo minas', unidade: 'kg', quantidade: 1.5, valor: 59.85 },
        { produtoId: 'entrega', nome: 'Taxa de entrega', quantidade: 1, valor: 5 },
      ],
    })),
    fluxo: vi.spyOn(relatorios, 'fluxoDeCaixa').mockImplementation(async (inicio, fim) => ({
      inicio, fim, vendas: 30, suprimentos: 50, sangrias: 20, estornos: 4, recebimentos: 7,
      entradas: 87, saidas: 24, saldo: 63,
    })),
    usuarios: vi.spyOn(contas, 'usuarios').mockResolvedValue(usuarios),
  }
}

/** O cartão cujo título começa pelo texto dado. */
function cartao(titulo: RegExp): HTMLElement {
  return screen.getByRole('heading', { name: titulo }).closest('section') as HTMLElement
}

function linha(dentro: HTMLElement, rotulo: string): HTMLElement {
  return within(dentro).getByText(rotulo).closest('li') as HTMLElement
}

describe('relatórios na tela', () => {
  it('abre no dia do balcão com o faturamento por forma, os mais vendidos e o fluxo de caixa', async () => {
    const api = prepararApi()
    render(<TelaDeRelatorios />)

    expect(await screen.findByText(`Faturamento de ${mostrarData(hoje)}`)).toBeInTheDocument()
    expect(api.faturamento).toHaveBeenCalledTimes(5)
    expect(api.faturamento).toHaveBeenCalledWith(hoje, hoje, { operadorId: undefined })
    for (const forma of ['DINHEIRO', 'PIX', 'CARTAO', 'FIADO'] as const) {
      expect(api.faturamento).toHaveBeenCalledWith(hoje, hoje, { forma, operadorId: undefined })
    }
    expect(api.maisVendidos).toHaveBeenCalledWith(hoje, hoje, 10, undefined)
    expect(api.fluxo).toHaveBeenCalledWith(hoje, hoje)

    const faturamento = cartao(/^Faturamento/)
    expect(within(faturamento).getByText(/37,00/)).toBeInTheDocument()
    expect(within(faturamento).getByText('2 vendas concluídas')).toBeInTheDocument()
    expect(linha(faturamento, 'Dinheiro')).toHaveTextContent(/10,00 em 1 venda$/)
    expect(linha(faturamento, 'Pix')).toHaveTextContent(/20,00 em 1 venda$/)
    expect(linha(faturamento, 'Cartão')).toHaveTextContent(/7,00 em 1 venda$/)
    expect(linha(faturamento, 'Fiado')).toHaveTextContent(/0,00 em 0 vendas$/)

    const ranking = within(cartao(/^Mais vendidos/)).getAllByRole('listitem')
    expect(ranking).toHaveLength(3)
    expect(ranking[0]).toHaveTextContent(/Café coado5 un, R\$\s22,50/)
    expect(ranking[1]).toHaveTextContent(/Queijo minas1,5 kg, R\$\s59,85/)
    // Sem unidade cadastrada, a quantidade sai sozinha.
    expect(ranking[2]).toHaveTextContent(/Taxa de entrega1, R\$\s5,00/)

    const fluxo = cartao(/^Fluxo de caixa/)
    expect(linha(fluxo, 'Entradas')).toHaveTextContent(/87,00/)
    expect(linha(fluxo, 'Vendas em dinheiro')).toHaveTextContent(/30,00/)
    expect(linha(fluxo, 'Fiado recebido em dinheiro')).toHaveTextContent(/7,00/)
    expect(linha(fluxo, 'Suprimentos')).toHaveTextContent(/50,00/)
    expect(linha(fluxo, 'Saídas')).toHaveTextContent(/24,00/)
    expect(linha(fluxo, 'Sangrias')).toHaveTextContent(/20,00/)
    expect(linha(fluxo, 'Estornos de vendas canceladas')).toHaveTextContent(/4,00/)
    expect(linha(fluxo, 'Saldo')).toHaveTextContent(/63,00/)

    // Com um usuário só, não há operador a escolher.
    expect(screen.queryByLabelText('Operador')).not.toBeInTheDocument()
  })

  it('a conferência do cartão lista o dia com a hora do balcão, o NSU ou a falta dele e a situação da venda', async () => {
    const api = prepararApi()
    render(<TelaDeRelatorios />)

    const conferencia = await waitFor(() => cartao(/^Conferência do cartão de/))
    expect(api.conferencia).toHaveBeenCalledWith(hoje, undefined)
    expect(within(conferencia).getByText(/2 pagamentos em cartão/)).toHaveTextContent(', 1 sem NSU')
    const linhas = within(conferencia).getAllByRole('listitem')
    expect(linhas).toHaveLength(2)
    expect(linhas[0]).toHaveTextContent(/^09:05 · R\$\s7,00 · NSU 004512/)
    expect(linhas[0]).toHaveTextContent(/Ana · venda 3f2b8c1a$/)
    expect(linhas[1]).toHaveTextContent(/^10:30 · R\$\s12,00 · sem NSU/)
    expect(linhas[1]).toHaveTextContent(
      /operador f00dbabe · fiado recebido 9a8b7c6d · venda cancelada: confira o estorno na maquininha$/)
  })

  it('a conferência do Pix lista à mão e integrado, a cobrança aguardando, o cliente e a situação da venda', async () => {
    const api = prepararApi()
    render(<TelaDeRelatorios />)

    const conferencia = await waitFor(() => cartao(/^Conferência do Pix de/))
    expect(api.pix).toHaveBeenCalledWith(hoje, undefined)
    expect(within(conferencia).getByText(/^3 Pix/)).toHaveTextContent(', 1 aguardando confirmação')
    const linhas = within(conferencia).getAllByRole('listitem')
    expect(linhas).toHaveLength(3)
    expect(linhas[0]).toHaveTextContent(/^09:20 · R\$\s20,00 · lançado à mão/)
    expect(linhas[0]).toHaveTextContent(/Ana · Lia · venda 1a2b3c4d$/)
    expect(linhas[1]).toHaveTextContent(/^11:00 · R\$\s15,00 · integrado · aguardando confirmação/)
    expect(linhas[1]).toHaveTextContent(/Ana · venda 5e6f7a8b · venda ainda aberta$/)
    expect(linhas[2]).toHaveTextContent(
      /Ana · Cliente removido · recebimento de fiado 9c0d1e2f · venda cancelada: confira a devolução ao cliente$/)
  })

  it('consulta o período escolhido nos três relatórios e volta para hoje', async () => {
    const api = prepararApi()
    render(<TelaDeRelatorios />)
    await screen.findByText(`Faturamento de ${mostrarData(hoje)}`)

    fireEvent.change(screen.getByLabelText('Início'), { target: { value: '2026-09-01' } })
    fireEvent.change(screen.getByLabelText('Fim'), { target: { value: '2026-09-15' } })
    fireEvent.click(screen.getByRole('button', { name: 'Consultar período' }))

    expect(await screen.findByText('Faturamento de 01/09/2026 a 15/09/2026')).toBeInTheDocument()
    expect(screen.getByText('Mais vendidos de 01/09/2026 a 15/09/2026')).toBeInTheDocument()
    expect(screen.getByText('Fluxo de caixa de 01/09/2026 a 15/09/2026')).toBeInTheDocument()
    // A conferência não é soma: com mais de um dia, o cartão pede um dia só, sem consultar.
    expect(within(cartao(/^Conferência do cartão$/)).getByText(/é de um dia só/)).toBeInTheDocument()
    expect(api.conferencia).toHaveBeenCalledTimes(1)
    expect(api.faturamento).toHaveBeenCalledWith('2026-09-01', '2026-09-15', { forma: 'PIX', operadorId: undefined })
    expect(api.maisVendidos).toHaveBeenCalledWith('2026-09-01', '2026-09-15', 10, undefined)
    expect(api.fluxo).toHaveBeenCalledWith('2026-09-01', '2026-09-15')

    fireEvent.click(screen.getByRole('button', { name: 'Hoje' }))
    await waitFor(() => expect(api.fluxo).toHaveBeenCalledTimes(3))
    expect(await screen.findByText(`Faturamento de ${mostrarData(hoje)}`)).toBeInTheDocument()
    expect(screen.getByLabelText('Início')).toHaveValue(hoje)
  })

  it('o operador escolhido filtra faturamento e ranking, não o fluxo, e o inativo aparece marcado', async () => {
    const api = prepararApi([
      titular,
      { id: 'usuario-beatriz', nome: 'Beatriz', perfil: 'OPERADOR', ativo: true },
      { id: 'usuario-caio', nome: 'Caio', perfil: 'OPERADOR', ativo: false },
    ])
    render(<TelaDeRelatorios />)

    const seletor = await screen.findByLabelText('Operador')
    expect(within(seletor).getAllByRole('option').map((opcao) => opcao.textContent))
      .toEqual(['Todos', 'Ana', 'Beatriz', 'Caio (inativo)'])
    await screen.findByText(`Faturamento de ${mostrarData(hoje)}`)
    expect(screen.queryByText(/^Vendas de/)).not.toBeInTheDocument()

    fireEvent.change(seletor, { target: { value: 'usuario-beatriz' } })
    fireEvent.click(screen.getByRole('button', { name: 'Consultar período' }))

    expect(await screen.findAllByText('Vendas de Beatriz')).toHaveLength(2)
    expect(api.faturamento).toHaveBeenCalledWith(hoje, hoje, { operadorId: 'usuario-beatriz' })
    expect(api.faturamento).toHaveBeenCalledWith(hoje, hoje, { forma: 'CARTAO', operadorId: 'usuario-beatriz' })
    expect(api.maisVendidos).toHaveBeenLastCalledWith(hoje, hoje, 10, 'usuario-beatriz')
    expect(api.conferencia).toHaveBeenLastCalledWith(hoje, 'usuario-beatriz')
    expect(api.pix).toHaveBeenLastCalledWith(hoje, 'usuario-beatriz')
    // Uma linha em cada conferência, a do cartão e a do Pix.
    expect(screen.getAllByText('Lançamentos de Beatriz')).toHaveLength(2)
    expect(api.fluxo).toHaveBeenLastCalledWith(hoje, hoje)
    expect(within(cartao(/^Fluxo de caixa/)).getByText(/o filtro de\s+operador não vale para ele/))
      .toBeInTheDocument()
  })

  it('mostra os vazios de um período sem movimento', async () => {
    vi.spyOn(relatorios, 'faturamento').mockImplementation(async (inicio, fim) => ({
      inicio, fim, total: 0, quantidadeDeVendas: 0,
    }))
    vi.spyOn(relatorios, 'maisVendidos').mockImplementation(async (inicio, fim) => ({ inicio, fim, posicoes: [] }))
    vi.spyOn(relatorios, 'fluxoDeCaixa').mockImplementation(async (inicio, fim) => ({
      inicio, fim, vendas: 0, suprimentos: 0, sangrias: 0, estornos: 0, recebimentos: 0,
      entradas: 0, saidas: 0, saldo: 0,
    }))
    vi.spyOn(relatorios, 'conferenciaDoCartao').mockImplementation(async (dia) => ({ dia, lancamentos: [] }))
    vi.spyOn(relatorios, 'conferenciaDoPix').mockImplementation(async (dia) => ({ dia, lancamentos: [] }))
    vi.spyOn(cadastro, 'clientes').mockResolvedValue([])
    vi.spyOn(cadastro, 'clientesInativos').mockResolvedValue([])
    vi.spyOn(contas, 'usuarios').mockResolvedValue([titular])
    render(<TelaDeRelatorios />)

    expect(await screen.findByText('Nenhuma venda concluída neste período.')).toBeInTheDocument()
    expect(screen.getByText('Nenhum pagamento em cartão neste dia.')).toBeInTheDocument()
    expect(screen.getByText('Nenhum Pix neste dia.')).toBeInTheDocument()
    expect(screen.getByText('Nenhum produto vendido neste período.')).toBeInTheDocument()
    expect(linha(cartao(/^Fluxo de caixa/), 'Saldo')).toHaveTextContent(/0,00/)
  })

  it('o erro de uma das chamadas vale para a tela inteira', async () => {
    prepararApi()
    vi.spyOn(relatorios, 'fluxoDeCaixa').mockRejectedValue(
      new ErroDaApi(409, 'Recurso fora do plano', 'Os relatórios fazem parte do plano Completo.', {}))
    render(<TelaDeRelatorios />)

    expect(await screen.findByRole('alert')).toHaveTextContent('Os relatórios fazem parte do plano Completo.')
    expect(screen.queryByRole('heading', { name: /^Faturamento/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: /^Mais vendidos/ })).not.toBeInTheDocument()
  })
})
