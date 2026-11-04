import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { afterEach, expect, it, vi } from 'vitest'
import { caixa } from '../api/caixa'
import { ErroDaApi, SemConexao } from '../api/cliente'
import { fiado } from '../api/fiado'
import { vendas } from '../api/vendas'
import { SessaoContext } from '../sessao/contexto'
import { TelaDeFiado } from './TelaDeFiado'

afterEach(() => { vi.restoreAllMocks(); sessionStorage.clear() })

it('operador registra recebimento parcial e vê comprovante com saldo restante', async () => {
  vi.spyOn(caixa, 'abertaDoOperadorAtual').mockResolvedValue({
    id: 'sessao-1', usuarioId: 'operador-1', valorAbertura: 0,
    valorFechamentoEsperado: 0, valorFechamentoContado: null, diferenca: null,
    abertaEm: '2026-09-23T12:00:00Z', fechadaEm: null, status: 'ABERTA',
  })
  vi.spyOn(fiado, 'dividas').mockResolvedValue([{ vendaId: 'venda-1', clienteId: 'cliente-1',
    nomeCliente: 'Lia', concluidoEm: '2026-09-23T12:00:00Z', saldoDevedor: 20 }])
  const receber = vi.spyOn(vendas, 'receber').mockRejectedValueOnce(new SemConexao())
    .mockResolvedValue({ id: 'recebimento-1', saldoDevedor: 13 })
  vi.spyOn(vendas, 'comprovanteDeRecebimento').mockResolvedValue({
    vendaId: 'venda-1', recebimentoId: 'recebimento-1', clienteId: 'cliente-1',
    nomeCliente: 'Lia', sessaoCaixaId: 'sessao-1', recebidoEm: '2026-09-23T12:05:00Z',
    forma: 'PIX', valor: 7, saldoApos: 13,
  })
  render(<MemoryRouter><SessaoContext.Provider value={{
    identidade: { usuarioId: 'operador-1', nome: 'Ana', perfil: 'OPERADOR',
      contaId: 'conta-1', nomeNegocio: 'Loja', estoqueHabilitado: false },
    entrar: vi.fn(), sair: vi.fn(), atualizarIdentidade: vi.fn(), relerIdentidade: vi.fn(),
  }}><TelaDeFiado /></SessaoContext.Provider></MemoryRouter>)

  fireEvent.click(await screen.findByRole('button', { name: 'Receber' }))
  // O formulário abre depois da lista; o foco vai ao valor, para o toque em Receber ter efeito à vista.
  await waitFor(() => expect(screen.getByLabelText('Valor recebido')).toHaveFocus())
  fireEvent.change(screen.getByLabelText('Valor recebido'), { target: { value: '7' } })
  fireEvent.change(screen.getByLabelText('Forma'), { target: { value: 'PIX' } })
  fireEvent.click(screen.getByRole('button', { name: 'Registrar recebimento' }))
  await waitFor(() => expect(receber).toHaveBeenCalledTimes(1))
  const formulario = screen.getByRole('heading', { name: 'Receber de Lia' }).closest('form')!
  expect(await within(formulario).findByRole('alert')).toHaveTextContent('Sem conexão')
  fireEvent.click(screen.getByRole('button', { name: 'Registrar recebimento' }))
  await waitFor(() => expect(receber).toHaveBeenCalledTimes(2))
  expect(receber.mock.calls[0][3]).toBe(receber.mock.calls[1][3])
  const papel = await screen.findByLabelText('Comprovante de recebimento não fiscal')
  expect(papel).toHaveTextContent('Saldo da venda após recebimento: R$ 13,00')
  expect(papel).toHaveTextContent('Forma: Pix')
})

it('o Pix manual do recebimento mostra o QR do valor digitado com a chave da Conta', async () => {
  vi.spyOn(caixa, 'abertaDoOperadorAtual').mockResolvedValue({
    id: 'sessao-1', usuarioId: 'operador-1', valorAbertura: 0,
    valorFechamentoEsperado: 0, valorFechamentoContado: null, diferenca: null,
    abertaEm: '2026-09-23T12:00:00Z', fechadaEm: null, status: 'ABERTA',
  })
  vi.spyOn(fiado, 'dividas').mockResolvedValue([{ vendaId: 'venda-1', clienteId: 'cliente-1',
    nomeCliente: 'Lia', concluidoEm: '2026-09-23T12:00:00Z', saldoDevedor: 20 }])
  render(<MemoryRouter><SessaoContext.Provider value={{
    identidade: { usuarioId: 'operador-1', nome: 'Ana', perfil: 'OPERADOR',
      contaId: 'conta-1', nomeNegocio: 'Loja', estoqueHabilitado: false,
      chavePix: 'loja@exemplo.com', cidadePix: 'Salvador' },
    entrar: vi.fn(), sair: vi.fn(), atualizarIdentidade: vi.fn(), relerIdentidade: vi.fn(),
  }}><TelaDeFiado /></SessaoContext.Provider></MemoryRouter>)

  fireEvent.click(await screen.findByRole('button', { name: 'Receber' }))
  fireEvent.change(screen.getByLabelText('Forma'), { target: { value: 'PIX' } })
  // Sem o valor, não há QR: o cliente pagaria qualquer quantia.
  expect(screen.queryByRole('img', { name: 'QR Pix do valor' })).not.toBeInTheDocument()
  fireEvent.change(screen.getByLabelText('Valor recebido'), { target: { value: '7' } })
  expect(screen.getByRole('img', { name: 'QR Pix do valor' })).toBeInTheDocument()
  expect(screen.getByText(/br\.gov\.bcb\.pix/)).toHaveTextContent('0116loja@exemplo.com')
  expect(screen.getByText(/br\.gov\.bcb\.pix/)).toHaveTextContent('5404' + '7.00')
})

it('o recebimento em cartão leva o NSU, exigido quando a identidade traz a exigência', async () => {
  vi.spyOn(caixa, 'abertaDoOperadorAtual').mockResolvedValue({
    id: 'sessao-1', usuarioId: 'operador-1', valorAbertura: 0,
    valorFechamentoEsperado: 0, valorFechamentoContado: null, diferenca: null,
    abertaEm: '2026-09-23T12:00:00Z', fechadaEm: null, status: 'ABERTA',
  })
  vi.spyOn(fiado, 'dividas').mockResolvedValue([{ vendaId: 'venda-1', clienteId: 'cliente-1',
    nomeCliente: 'Lia', concluidoEm: '2026-09-23T12:00:00Z', saldoDevedor: 20 }])
  const receber = vi.spyOn(vendas, 'receber').mockResolvedValue({ id: 'recebimento-1', saldoDevedor: 15 })
  vi.spyOn(vendas, 'comprovanteDeRecebimento').mockResolvedValue({
    vendaId: 'venda-1', recebimentoId: 'recebimento-1', clienteId: 'cliente-1',
    nomeCliente: 'Lia', sessaoCaixaId: 'sessao-1', recebidoEm: '2026-09-23T12:05:00Z',
    forma: 'CARTAO', valor: 5, saldoApos: 15,
  })
  render(<MemoryRouter><SessaoContext.Provider value={{
    identidade: { usuarioId: 'operador-1', nome: 'Ana', perfil: 'OPERADOR',
      contaId: 'conta-1', nomeNegocio: 'Loja', estoqueHabilitado: false, nsuObrigatorio: true },
    entrar: vi.fn(), sair: vi.fn(), atualizarIdentidade: vi.fn(), relerIdentidade: vi.fn(),
  }}><TelaDeFiado /></SessaoContext.Provider></MemoryRouter>)

  fireEvent.click(await screen.findByRole('button', { name: 'Receber' }))
  expect(screen.queryByLabelText(/NSU do comprovante/)).not.toBeInTheDocument()
  fireEvent.change(screen.getByLabelText('Valor recebido'), { target: { value: '5' } })
  fireEvent.change(screen.getByLabelText('Forma'), { target: { value: 'CARTAO' } })
  const campo = screen.getByLabelText('NSU do comprovante')
  expect(campo).toBeRequired()
  fireEvent.change(campo, { target: { value: ' 778899 ' } })
  fireEvent.click(screen.getByRole('button', { name: 'Registrar recebimento' }))

  await waitFor(() => expect(receber).toHaveBeenCalledWith('venda-1', 5, 'CARTAO',
    expect.any(String), '778899'))
  // O comprovante do recebimento continua sem o NSU: o cliente já leva o da maquininha.
  expect(await screen.findByLabelText('Comprovante de recebimento não fiscal'))
    .not.toHaveTextContent('778899')
})

it('quem deve em duas vendas aparece numa linha só, com o total, e as vendas abrem ao tocar', async () => {
  vi.spyOn(caixa, 'abertaDoOperadorAtual').mockResolvedValue({
    id: 'sessao-1', usuarioId: 'operador-1', valorAbertura: 0,
    valorFechamentoEsperado: 0, valorFechamentoContado: null, diferenca: null,
    abertaEm: '2026-09-23T12:00:00Z', fechadaEm: null, status: 'ABERTA',
  })
  vi.spyOn(fiado, 'dividas').mockResolvedValue([
    { vendaId: 'venda-1', clienteId: 'cliente-1', nomeCliente: 'Lia',
      concluidoEm: '2026-09-23T12:00:00Z', saldoDevedor: 20.1 },
    { vendaId: 'venda-2', clienteId: 'cliente-2', nomeCliente: 'Dona Marta',
      concluidoEm: '2026-09-23T13:00:00Z', saldoDevedor: 8 },
    { vendaId: 'venda-3', clienteId: 'cliente-1', nomeCliente: 'Lia',
      concluidoEm: '2026-09-24T12:00:00Z', saldoDevedor: 0.2 },
  ])
  render(<MemoryRouter><SessaoContext.Provider value={{
    identidade: { usuarioId: 'operador-1', nome: 'Ana', perfil: 'OPERADOR',
      contaId: 'conta-1', nomeNegocio: 'Loja', estoqueHabilitado: false },
    entrar: vi.fn(), sair: vi.fn(), atualizarIdentidade: vi.fn(), relerIdentidade: vi.fn(),
  }}><TelaDeFiado /></SessaoContext.Provider></MemoryRouter>)

  expect(await screen.findAllByText('Lia')).toHaveLength(1)
  // A soma em centavos dá 20,30 certo, onde 20.1 + 0.2 em ponto flutuante daria 20,299999.
  expect(screen.getByText(/Saldo: R\$\s20,30 em 2 vendas/)).toBeInTheDocument()
  // Dona Marta deve numa venda só, e o Receber fica na própria linha.
  expect(screen.getAllByRole('button', { name: 'Receber' })).toHaveLength(1)

  const ver = screen.getByRole('button', { name: 'Ver vendas' })
  expect(ver).toHaveAttribute('aria-expanded', 'false')
  fireEvent.click(ver)
  expect(screen.getByRole('button', { name: 'Esconder vendas' })).toHaveAttribute('aria-expanded', 'true')
  expect(screen.getAllByRole('button', { name: 'Receber' })).toHaveLength(3)
})

it('o total da Conta no topo, quem deve mais primeiro e cada venda pela data, com os dias em aberto', async () => {
  // Só o relógio é falso: as esperas da tela continuam reais.
  vi.useFakeTimers({ toFake: ['Date'] })
  vi.setSystemTime(new Date('2026-11-03T15:00:00Z'))
  vi.spyOn(caixa, 'abertaDoOperadorAtual').mockResolvedValue(undefined)
  vi.spyOn(fiado, 'dividas').mockResolvedValue([
    { vendaId: 'venda-3', clienteId: 'cliente-1', nomeCliente: 'Lia',
      concluidoEm: '2026-11-03T13:00:00Z', saldoDevedor: 0.2 },
    { vendaId: 'venda-1', clienteId: 'cliente-1', nomeCliente: 'Lia',
      concluidoEm: '2026-10-23T12:01:00Z', saldoDevedor: 20.1 },
    { vendaId: 'venda-2', clienteId: 'cliente-2', nomeCliente: 'Dona Marta',
      concluidoEm: '2026-11-02T16:00:00Z', saldoDevedor: 8 },
    { vendaId: 'venda-4', clienteId: 'cliente-3', nomeCliente: 'Bruno',
      concluidoEm: '2026-11-03T02:30:00Z', saldoDevedor: 30 },
  ])
  try {
    render(<MemoryRouter><SessaoContext.Provider value={{
      identidade: { usuarioId: 'operador-1', nome: 'Ana', perfil: 'OPERADOR',
        contaId: 'conta-1', nomeNegocio: 'Loja', estoqueHabilitado: false },
      entrar: vi.fn(), sair: vi.fn(), atualizarIdentidade: vi.fn(), relerIdentidade: vi.fn(),
    }}><TelaDeFiado /></SessaoContext.Provider></MemoryRouter>)

    expect(await screen.findByText(/Total em aberto/)).toHaveTextContent('Total em aberto: R$ 58,30, de 3 clientes.')
    expect(screen.getAllByRole('listitem').map((item) => item.querySelector('strong')?.textContent))
      .toEqual(['Bruno', 'Lia', 'Dona Marta'])
    // 23h30 de ontem no fuso do balcão: pela folha do calendário, já é de ontem, não de hoje.
    expect(screen.getByText('Venda de 02/11/2026, 23:30, em aberto há 1 dia.')).toBeInTheDocument()
    expect(screen.getByText('Venda de 02/11/2026, 13:00, em aberto há 1 dia.')).toBeInTheDocument()
    expect(screen.getByText(/Saldo: R\$\s20,30 em 2 vendas, a mais antiga em aberto há 11 dias\./)).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Ver vendas' }))
    const vendasDaLia = screen.getByRole('button', { name: 'Esconder vendas' }).nextElementSibling as HTMLElement
    expect([...vendasDaLia.querySelectorAll('li')].map((venda) => venda.querySelector('p')?.textContent)).toEqual([
      'Venda de 23/10/2026, 09:01, em aberto há 11 dias.',
      'Venda de 03/11/2026, 10:00, em aberto desde hoje.',
    ])
  } finally {
    vi.useRealTimers()
  }
})

it('a busca filtra as Vendas com saldo pelo nome do cliente e recusa o valor acima do saldo', async () => {
  vi.spyOn(caixa, 'abertaDoOperadorAtual').mockResolvedValue({
    id: 'sessao-1', usuarioId: 'operador-1', valorAbertura: 0,
    valorFechamentoEsperado: 0, valorFechamentoContado: null, diferenca: null,
    abertaEm: '2026-09-23T12:00:00Z', fechadaEm: null, status: 'ABERTA',
  })
  vi.spyOn(fiado, 'dividas').mockResolvedValue([
    { vendaId: 'venda-1', clienteId: 'cliente-1', nomeCliente: 'Lia',
      concluidoEm: '2026-09-23T12:00:00Z', saldoDevedor: 20 },
    { vendaId: 'venda-2', clienteId: 'cliente-2', nomeCliente: 'Dona Marta',
      concluidoEm: '2026-09-23T13:00:00Z', saldoDevedor: 8 },
  ])
  const receber = vi.spyOn(vendas, 'receber')
  render(<MemoryRouter><SessaoContext.Provider value={{
    identidade: { usuarioId: 'operador-1', nome: 'Ana', perfil: 'OPERADOR',
      contaId: 'conta-1', nomeNegocio: 'Loja', estoqueHabilitado: false },
    entrar: vi.fn(), sair: vi.fn(), atualizarIdentidade: vi.fn(), relerIdentidade: vi.fn(),
  }}><TelaDeFiado /></SessaoContext.Provider></MemoryRouter>)

  fireEvent.change(await screen.findByLabelText('Buscar pelo nome do cliente'), { target: { value: 'marta' } })
  expect(screen.queryByText('Lia')).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: 'Receber' }))
  fireEvent.change(screen.getByLabelText('Valor recebido'), { target: { value: '8,01' } })
  fireEvent.click(screen.getByRole('button', { name: 'Registrar recebimento' }))
  const formulario = screen.getByRole('heading', { name: 'Receber de Dona Marta' }).closest('form')!
  expect(await within(formulario).findByRole('alert')).toHaveTextContent('O valor recebido passa do saldo desta venda.')
  expect(receber).not.toHaveBeenCalled()
})

function mostrarFiado() {
  render(<MemoryRouter><SessaoContext.Provider value={{
    identidade: { usuarioId: 'operador-1', nome: 'Ana', perfil: 'OPERADOR',
      contaId: 'conta-1', nomeNegocio: 'Loja', estoqueHabilitado: false },
    entrar: vi.fn(), sair: vi.fn(), atualizarIdentidade: vi.fn(), relerIdentidade: vi.fn(),
  }}><TelaDeFiado /></SessaoContext.Provider></MemoryRouter>)
}

it('com a lista lida, sem caixa e sem dívida, a tela diz que ninguém deve e leva ao caixa', async () => {
  vi.spyOn(caixa, 'abertaDoOperadorAtual').mockResolvedValue(undefined)
  vi.spyOn(fiado, 'dividas').mockResolvedValue([])
  mostrarFiado()

  expect(await screen.findByText('Nenhuma venda com saldo devedor.')).toBeInTheDocument()
  expect(screen.getByText('Abra o seu caixa para registrar recebimentos.')).toBeInTheDocument()
  expect(screen.getByRole('link', { name: 'Ir ao caixa' })).toBeInTheDocument()
})

it('sem conexão, a tela diz que o fiado precisa de rede e não diz que ninguém deve nem que falta o caixa', async () => {
  vi.spyOn(caixa, 'abertaDoOperadorAtual').mockRejectedValue(new SemConexao())
  vi.spyOn(fiado, 'dividas').mockRejectedValue(new SemConexao())
  mostrarFiado()

  const aviso = await screen.findByRole('status')
  expect(aviso).toHaveTextContent('Sem conexão. O fiado precisa de rede para mostrar quem deve e registrar '
    + 'recebimentos. Quando a rede voltar, abra o Fiado de novo.')
  expect(aviso).toHaveClass('mensagem-info')
  // A frase da escrita sem resposta não cabe numa leitura, e o vazio seria falso.
  expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  expect(screen.queryByText(/Reabra a lista/)).not.toBeInTheDocument()
  expect(screen.queryByText('Nenhuma venda com saldo devedor.')).not.toBeInTheDocument()
  expect(screen.queryByText('Abra o seu caixa para registrar recebimentos.')).not.toBeInTheDocument()
  expect(screen.queryByRole('link', { name: 'Ir ao caixa' })).not.toBeInTheDocument()
  expect(screen.queryByText('Carregando...')).not.toBeInTheDocument()
})

it('a leitura recusada pelo servidor mostra a recusa, sem as frases de vazio', async () => {
  vi.spyOn(caixa, 'abertaDoOperadorAtual').mockResolvedValue(undefined)
  vi.spyOn(fiado, 'dividas').mockRejectedValue(new ErroDaApi(403, 'Forbidden', undefined, {}))
  mostrarFiado()

  expect(await screen.findByRole('alert')).toHaveTextContent('Seu perfil não permite esta operação.')
  expect(screen.queryByRole('status')).not.toBeInTheDocument()
  expect(screen.queryByText('Nenhuma venda com saldo devedor.')).not.toBeInTheDocument()
  expect(screen.queryByText('Abra o seu caixa para registrar recebimentos.')).not.toBeInTheDocument()
})
