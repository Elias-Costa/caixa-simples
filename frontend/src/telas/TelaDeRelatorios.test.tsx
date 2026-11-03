import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { cadastro } from '../api/cadastro'
import { ErroDaApi } from '../api/cliente'
import { contas, type UsuarioDaConta } from '../api/contas'
import { relatorios, type Faturamento, type LancamentoEmCartao, type LancamentoEmPix } from '../api/relatorios'
import { hojeNoBalcao } from '../dataDoBalcao'
import { TelaDeRelatorios } from './TelaDeRelatorios'

afterEach(() => {
  vi.useRealTimers()
  vi.restoreAllMocks()
})

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
    // Na ordem do servidor pelo valor: o queijo rendeu mais que o café, que saiu mais vezes.
    maisVendidos: vi.spyOn(relatorios, 'maisVendidos').mockImplementation(async (inicio, fim) => ({
      inicio, fim, posicoes: [
        { produtoId: 'queijo', nome: 'Queijo minas', unidade: 'kg', quantidade: 1.5, valor: 59.85 },
        { produtoId: 'cafe', nome: 'Café coado', unidade: 'un', quantidade: 5, valor: 22.5 },
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

/** O cartão cujo título é o texto dado. */
function cartao(titulo: string): HTMLElement {
  return screen.getByRole('heading', { name: titulo }).closest('section') as HTMLElement
}

function linha(dentro: HTMLElement, rotulo: string): HTMLElement {
  return within(dentro).getByText(rotulo).closest('li') as HTMLElement
}

/** As células de cada linha do corpo de uma tabela, o rótulo da linha primeiro. */
function celulas(tabela: HTMLElement): HTMLElement[][] {
  return within(tabela).getAllByRole('row').slice(1).map((linhaDaTabela) => [
    ...within(linhaDaTabela).queryAllByRole('rowheader'), ...within(linhaDaTabela).queryAllByRole('cell'),
  ])
}

/** O texto de cada célula, com o espaço inseparável do formato de moeda trocado pelo comum. */
function textos(tabela: HTMLElement): string[][] {
  return celulas(tabela).map((linhaDaTabela) =>
    linhaDaTabela.map((celula) => (celula.textContent ?? '').replace(/ /g, ' ')))
}

async function esperarConsulta() {
  await waitFor(() => expect(screen.getByRole('button', { name: 'Consultar período' })).toBeEnabled())
}

describe('relatórios na tela', () => {
  it('abre no dia do balcão com o período uma vez, o faturamento por forma, o ranking pelo valor e o fluxo', async () => {
    const api = prepararApi()
    render(<TelaDeRelatorios />)

    expect(await screen.findByText(`${mostrarData(hoje)} · todos os operadores`)).toBeInTheDocument()
    expect(api.faturamento).toHaveBeenCalledTimes(5)
    expect(api.faturamento).toHaveBeenCalledWith(hoje, hoje, { operadorId: undefined })
    for (const forma of ['DINHEIRO', 'PIX', 'CARTAO', 'FIADO'] as const) {
      expect(api.faturamento).toHaveBeenCalledWith(hoje, hoje, { forma, operadorId: undefined })
    }
    expect(api.maisVendidos).toHaveBeenCalledWith(hoje, hoje, 10, undefined, 'VALOR')
    expect(api.fluxo).toHaveBeenCalledWith(hoje, hoje)

    // Os títulos são curtos: o período está na linha de cima, uma vez só.
    const faturamento = cartao('Faturamento')
    expect(within(faturamento).getByText('Total').parentElement).toHaveTextContent(/^TotalR\$\s37,00$/)
    // O ticket médio é o total dividido pelas vendas concluídas: 37 em 2 vendas.
    expect(within(faturamento).getByText('Ticket médio').parentElement).toHaveTextContent(/^Ticket médioR\$\s18,50$/)
    expect(within(faturamento).getByText('2 vendas concluídas')).toBeInTheDocument()
    expect(textos(within(faturamento).getByRole('table', { name: 'Por forma de pagamento' }))).toEqual([
      ['Dinheiro', 'R$ 10,00', '1'],
      ['Pix', 'R$ 20,00', '1'],
      ['Cartão', 'R$ 7,00', '1'],
      ['Fiado', 'R$ 0,00', '0'],
    ])

    // A quantidade com a unidade na linha; o serviço sem unidade mostra só o número, sem vírgula solta.
    expect(textos(screen.getByRole('table', { name: 'Mais vendidos' }))).toEqual([
      ['Queijo minas', '1,5 kg', 'R$ 59,85'],
      ['Café coado', '5 un', 'R$ 22,50'],
      ['Taxa de entrega', '1', 'R$ 5,00'],
    ])
    expect(within(cartao('Mais vendidos')).getByText('Os dez que mais faturaram no período.')).toBeInTheDocument()

    const fluxo = cartao('Fluxo de caixa')
    expect(linha(fluxo, 'Entradas')).toHaveTextContent(/87,00/)
    expect(linha(fluxo, 'Vendas em dinheiro')).toHaveTextContent(/30,00/)
    expect(linha(fluxo, 'Fiado recebido em dinheiro')).toHaveTextContent(/7,00/)
    expect(linha(fluxo, 'Suprimentos')).toHaveTextContent(/50,00/)
    expect(linha(fluxo, 'Saídas')).toHaveTextContent(/24,00/)
    expect(linha(fluxo, 'Sangrias')).toHaveTextContent(/20,00/)
    expect(linha(fluxo, 'Estornos de vendas canceladas')).toHaveTextContent(/4,00/)
    expect(linha(fluxo, 'Saldo')).toHaveTextContent(/63,00/)
    expect(within(fluxo).queryByText(/filtro de\s+operador/)).not.toBeInTheDocument()

    // Com um usuário só, não há operador a escolher.
    expect(screen.queryByLabelText('Operador')).not.toBeInTheDocument()
  })

  it('a conferência do cartão soma o dia, diz por que difere do faturamento e filtra os sem NSU', async () => {
    const api = prepararApi()
    render(<TelaDeRelatorios />)

    const conferencia = await waitFor(() => cartao('Conferência do cartão'))
    expect(api.conferencia).toHaveBeenCalledWith(hoje, undefined)
    expect(within(conferencia).getByText(/em 2 pagamentos/)).toHaveTextContent(/^R\$\s19,00 em 2 pagamentos, 1 sem NSU\.$/)
    expect(within(conferencia).getByText(/^Este total não é o faturamento em cartão/)).toHaveTextContent(
      /o fiado\s+recebido em cartão e a venda cancelada ou ainda aberta, e a venda dividida aparece uma vez por\s+parcela/)

    const tabela = within(conferencia).getByRole('table', { name: 'Conferência do cartão' })
    expect(textos(tabela).map((linhaDaTabela) => linhaDaTabela.slice(0, 3))).toEqual([
      ['09:05', 'R$ 7,00', '004512'],
      ['10:30', 'R$ 12,00', 'Sem NSU'],
    ])
    const [comNsu, semNsu] = celulas(tabela)
    expect(comNsu[3]).toHaveTextContent(/^Ana$/)
    // Quem lançou e não está na lista sai pelo começo do id; a origem e a situação são marcas curtas.
    expect(semNsu[3]).toHaveTextContent(/^operador f00dbabe/)
    expect(within(semNsu[3]).getByText('Fiado')).toBeInTheDocument()
    expect(within(semNsu[3]).getByText('Cancelada')).toBeInTheDocument()
    expect(within(conferencia).getByText(/Na venda cancelada, confira o\s+estorno na maquininha\./)).toBeInTheDocument()
    // O id da venda não aparece na tela.
    expect(conferencia).not.toHaveTextContent('3f2b8c1a')

    const filtro = within(conferencia).getByRole('button', { name: 'Só sem NSU' })
    expect(filtro).toHaveAttribute('aria-pressed', 'false')
    fireEvent.click(filtro)
    expect(filtro).toHaveAttribute('aria-pressed', 'true')
    expect(textos(tabela).map((linhaDaTabela) => linhaDaTabela[0])).toEqual(['10:30'])
    // O total continua o do dia inteiro, que é o que se bate com a maquininha.
    expect(within(conferencia).getByText(/em 2 pagamentos/)).toHaveTextContent(/^R\$\s19,00/)
    fireEvent.click(filtro)
    expect(textos(tabela)).toHaveLength(2)
  })

  it('a conferência do Pix soma o que entrou, deixa a cobrança aguardando fora e marca cada linha', async () => {
    const api = prepararApi()
    render(<TelaDeRelatorios />)

    const conferencia = await waitFor(() => cartao('Conferência do Pix'))
    expect(api.pix).toHaveBeenCalledWith(hoje, undefined)
    expect(within(conferencia).getByText(/em 2 Pix/)).toHaveTextContent(/^R\$\s25,00 em 2 Pix\.$/)
    expect(within(conferencia).getByText(/aguardando confirmação/))
      .toHaveTextContent(/^1 aguardando confirmação, de R\$\s15,00, fora do total\.$/)

    const tabela = within(conferencia).getByRole('table', { name: 'Conferência do Pix' })
    expect(textos(tabela).map((linhaDaTabela) => linhaDaTabela.slice(0, 2))).toEqual([
      ['09:20', 'R$ 20,00'],
      ['11:00', 'R$ 15,00'],
      ['12:00', 'R$ 5,00'],
    ])
    const [maoDaLia, integrado, fiado] = celulas(tabela).map((linhaDaTabela) => linhaDaTabela[2])
    expect(maoDaLia).toHaveTextContent(/^AnaCliente: Lia$/)
    expect(within(integrado).getByText('Integrado')).toBeInTheDocument()
    expect(within(integrado).getByText('Aguardando')).toBeInTheDocument()
    expect(within(integrado).getByText('Aberta')).toBeInTheDocument()
    expect(within(fiado).getByText('Cliente: Cliente removido')).toBeInTheDocument()
    expect(within(fiado).getByText('Fiado')).toBeInTheDocument()
    expect(within(fiado).getByText('Cancelada')).toBeInTheDocument()
    expect(within(conferencia).getByText(/^O Pix lançado à mão não tem identificador/))
      .toHaveTextContent(/Na venda cancelada, confira a\s+devolução ao cliente\.$/)
  })

  it('num período de mais de um dia, uma linha só no lugar das conferências, e Hoje volta ao dia', async () => {
    const api = prepararApi()
    render(<TelaDeRelatorios />)
    await screen.findByText(`${mostrarData(hoje)} · todos os operadores`)

    fireEvent.change(screen.getByLabelText('Início'), { target: { value: '2026-09-01' } })
    fireEvent.change(screen.getByLabelText('Fim'), { target: { value: '2026-09-15' } })
    fireEvent.click(screen.getByRole('button', { name: 'Consultar período' }))

    expect(await screen.findByText('01/09/2026 a 15/09/2026 · todos os operadores')).toBeInTheDocument()
    expect(screen.getByText(/^As conferências do cartão e do Pix são de um dia só/)).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Conferência do cartão' })).not.toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Conferência do Pix' })).not.toBeInTheDocument()
    expect(api.conferencia).toHaveBeenCalledTimes(1)
    expect(api.faturamento).toHaveBeenCalledWith('2026-09-01', '2026-09-15', { forma: 'PIX', operadorId: undefined })
    expect(api.maisVendidos).toHaveBeenCalledWith('2026-09-01', '2026-09-15', 10, undefined, 'VALOR')
    expect(api.fluxo).toHaveBeenCalledWith('2026-09-01', '2026-09-15')

    fireEvent.click(screen.getByRole('button', { name: 'Hoje' }))
    await waitFor(() => expect(api.fluxo).toHaveBeenCalledTimes(3))
    expect(await screen.findByText(`${mostrarData(hoje)} · todos os operadores`)).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Conferência do cartão' })).toBeInTheDocument()
    expect(screen.getByLabelText('Início')).toHaveValue(hoje)
  })

  it('os atalhos preenchem as datas pelo dia do balcão e consultam, também na virada do mês', async () => {
    vi.useFakeTimers({ toFake: ['Date'] })
    // Meio-dia de 10/03/2026 no balcão, que está três horas atrás do UTC.
    vi.setSystemTime(new Date('2026-03-10T15:00:00Z'))
    const api = prepararApi()
    render(<TelaDeRelatorios />)
    await esperarConsulta()

    const conferir = async (atalho: string, inicio: string, fim: string) => {
      fireEvent.click(screen.getByRole('button', { name: atalho }))
      await esperarConsulta()
      expect(api.faturamento).toHaveBeenLastCalledWith(inicio, fim, { forma: 'FIADO', operadorId: undefined })
      expect(screen.getByLabelText('Início')).toHaveValue(inicio)
      expect(screen.getByLabelText('Fim')).toHaveValue(fim)
    }

    await conferir('Ontem', '2026-03-09', '2026-03-09')
    // Hoje e os seis dias anteriores.
    await conferir('7 dias', '2026-03-04', '2026-03-10')
    await conferir('Este mês', '2026-03-01', '2026-03-10')
    await conferir('Hoje', '2026-03-10', '2026-03-10')

    // No primeiro dia do mês, ontem é o último do mês anterior.
    vi.setSystemTime(new Date('2026-03-01T15:00:00Z'))
    await conferir('Ontem', '2026-02-28', '2026-02-28')
    await conferir('7 dias', '2026-02-23', '2026-03-01')
    await conferir('Este mês', '2026-03-01', '2026-03-01')
  })

  it('o operador escolhido vai à linha do período, filtra faturamento, ranking e conferências, e o fluxo avisa no alto', async () => {
    const api = prepararApi([
      titular,
      { id: 'usuario-beatriz', nome: 'Beatriz', perfil: 'OPERADOR', ativo: true },
      { id: 'usuario-caio', nome: 'Caio', perfil: 'OPERADOR', ativo: false },
    ])
    render(<TelaDeRelatorios />)

    const seletor = await screen.findByLabelText('Operador')
    expect(within(seletor).getAllByRole('option').map((opcao) => opcao.textContent))
      .toEqual(['Todos', 'Ana', 'Beatriz', 'Caio (inativo)'])
    await screen.findByText(`${mostrarData(hoje)} · todos os operadores`)

    fireEvent.change(seletor, { target: { value: 'usuario-beatriz' } })
    fireEvent.click(screen.getByRole('button', { name: 'Consultar período' }))

    expect(await screen.findByText(`${mostrarData(hoje)} · Beatriz`)).toBeInTheDocument()
    expect(api.faturamento).toHaveBeenCalledWith(hoje, hoje, { operadorId: 'usuario-beatriz' })
    expect(api.faturamento).toHaveBeenCalledWith(hoje, hoje, { forma: 'CARTAO', operadorId: 'usuario-beatriz' })
    expect(api.maisVendidos).toHaveBeenLastCalledWith(hoje, hoje, 10, 'usuario-beatriz', 'VALOR')
    expect(api.conferencia).toHaveBeenLastCalledWith(hoje, 'usuario-beatriz')
    expect(api.pix).toHaveBeenLastCalledWith(hoje, 'usuario-beatriz')
    expect(api.fluxo).toHaveBeenLastCalledWith(hoje, hoje)
    // O nome não se repete em cada cartão: a linha do período já diz quem foi consultado.
    expect(screen.queryByText(/^Vendas de/)).not.toBeInTheDocument()
    // O aviso do fluxo vem logo abaixo do título, e não no rodapé do cartão.
    expect(screen.getByRole('heading', { name: 'Fluxo de caixa' }).nextElementSibling)
      .toHaveTextContent(/^O fluxo de caixa é da gaveta da Conta inteira: o filtro de\s+operador não vale para ele\.$/)
  })

  it('a consulta nova deixa o resultado anterior à vista, esmaecido, até chegar o novo', async () => {
    const api = prepararApi()
    render(<TelaDeRelatorios />)
    await screen.findByText(`${mostrarData(hoje)} · todos os operadores`)

    let responder: (faturamento: Faturamento) => void = () => undefined
    api.faturamento.mockImplementationOnce(() => new Promise((resolver) => { responder = resolver }))
    fireEvent.change(screen.getByLabelText('Início'), { target: { value: '2026-09-01' } })
    fireEvent.change(screen.getByLabelText('Fim'), { target: { value: '2026-09-15' } })
    fireEvent.click(screen.getByRole('button', { name: 'Consultar período' }))

    expect(await screen.findByRole('button', { name: 'Consultando' })).toBeDisabled()
    const anterior = screen.getByRole('heading', { name: 'Faturamento' }).closest('[aria-busy]')
    expect(anterior).toHaveAttribute('aria-busy', 'true')
    expect(screen.getByText(`${mostrarData(hoje)} · todos os operadores`)).toBeInTheDocument()
    expect(screen.queryByText('Carregando relatórios...')).not.toBeInTheDocument()

    responder({ inicio: '2026-09-01', fim: '2026-09-15', total: 37, quantidadeDeVendas: 2 })
    expect(await screen.findByText('01/09/2026 a 15/09/2026 · todos os operadores')).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Faturamento' }).closest('[aria-busy]'))
      .toHaveAttribute('aria-busy', 'false')
  })

  it('mostra os vazios de um período sem movimento, sem ticket médio nem filtro', async () => {
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
    expect(screen.queryByText('Ticket médio')).not.toBeInTheDocument()
    expect(screen.getByText('Nenhum pagamento em cartão neste dia.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Só sem NSU' })).not.toBeInTheDocument()
    expect(screen.getByText('Nenhum Pix neste dia.')).toBeInTheDocument()
    expect(screen.getByText('Nenhum produto vendido neste período.')).toBeInTheDocument()
    expect(linha(cartao('Fluxo de caixa'), 'Saldo')).toHaveTextContent(/0,00/)
  })

  it('o erro de uma das chamadas vale para a tela inteira', async () => {
    prepararApi()
    vi.spyOn(relatorios, 'fluxoDeCaixa').mockRejectedValue(
      new ErroDaApi(409, 'Recurso fora do plano', 'Os relatórios fazem parte do plano Completo.', {}))
    render(<TelaDeRelatorios />)

    expect(await screen.findByRole('alert')).toHaveTextContent('Os relatórios fazem parte do plano Completo.')
    expect(screen.queryByRole('heading', { name: 'Faturamento' })).not.toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Mais vendidos' })).not.toBeInTheDocument()
  })
})
