import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { afterEach, expect, it, vi } from 'vitest'
import { caixa } from '../api/caixa'
import { SemConexao } from '../api/cliente'
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
    entrar: vi.fn(), sair: vi.fn(), atualizarIdentidade: vi.fn(),
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
  expect(papel).toHaveTextContent('Saldo da Venda após recebimento: R$ 13,00')
  expect(papel).toHaveTextContent('Forma: Pix')
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
    entrar: vi.fn(), sair: vi.fn(), atualizarIdentidade: vi.fn(),
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
    entrar: vi.fn(), sair: vi.fn(), atualizarIdentidade: vi.fn(),
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
    entrar: vi.fn(), sair: vi.fn(), atualizarIdentidade: vi.fn(),
  }}><TelaDeFiado /></SessaoContext.Provider></MemoryRouter>)

  fireEvent.change(await screen.findByLabelText('Buscar pelo nome do cliente'), { target: { value: 'marta' } })
  expect(screen.queryByText('Lia')).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: 'Receber' }))
  fireEvent.change(screen.getByLabelText('Valor recebido'), { target: { value: '8,01' } })
  fireEvent.click(screen.getByRole('button', { name: 'Registrar recebimento' }))
  const formulario = screen.getByRole('heading', { name: 'Receber de Dona Marta' }).closest('form')!
  expect(await within(formulario).findByRole('alert')).toHaveTextContent('O valor recebido passa do saldo desta Venda.')
  expect(receber).not.toHaveBeenCalled()
})
