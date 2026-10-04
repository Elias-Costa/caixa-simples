import { fireEvent, render, screen, waitFor } from '@testing-library/react'
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
  fireEvent.change(screen.getByLabelText('Valor recebido'), { target: { value: '7' } })
  fireEvent.change(screen.getByLabelText('Forma'), { target: { value: 'PIX' } })
  fireEvent.click(screen.getByRole('button', { name: 'Registrar recebimento' }))
  await waitFor(() => expect(receber).toHaveBeenCalledTimes(1))
  expect(await screen.findByRole('alert')).toHaveTextContent('Sem conexão')
  fireEvent.click(screen.getByRole('button', { name: 'Registrar recebimento' }))
  await waitFor(() => expect(receber).toHaveBeenCalledTimes(2))
  expect(receber.mock.calls[0][3]).toBe(receber.mock.calls[1][3])
  expect(await screen.findByLabelText('Comprovante de recebimento não fiscal'))
    .toHaveTextContent('Saldo da Venda após recebimento: R$ 13,00')
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
