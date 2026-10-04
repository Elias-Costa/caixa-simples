import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ErroDaApi } from '../api/cliente'
import { contas, type UsuarioDaConta } from '../api/contas'
import { relatorios } from '../api/relatorios'
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

function prepararApi(usuarios: UsuarioDaConta[] = [titular]) {
  return {
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
    vi.spyOn(contas, 'usuarios').mockResolvedValue([titular])
    render(<TelaDeRelatorios />)

    expect(await screen.findByText('Nenhuma venda concluída neste período.')).toBeInTheDocument()
    expect(screen.getByText('Nenhum produto vendido neste período.')).toBeInTheDocument()
    expect(linha(cartao(/^Fluxo de caixa/), 'Saldo')).toHaveTextContent(/0,00/)
  })

  it('o erro de uma das chamadas vale para a tela inteira', async () => {
    prepararApi()
    vi.spyOn(relatorios, 'fluxoDeCaixa').mockRejectedValue(
      new ErroDaApi(409, 'Recurso fora do plano', 'Relatórios fazem parte do plano Caixa Simples.', {}))
    render(<TelaDeRelatorios />)

    expect(await screen.findByRole('alert')).toHaveTextContent('Relatórios fazem parte do plano Caixa Simples.')
    expect(screen.queryByRole('heading', { name: /^Faturamento/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: /^Mais vendidos/ })).not.toBeInTheDocument()
  })
})
