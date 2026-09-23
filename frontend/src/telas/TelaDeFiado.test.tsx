import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { afterEach, expect, it, vi } from 'vitest'
import { caixa } from '../api/caixa'
import { fiado } from '../api/fiado'
import { vendas } from '../api/vendas'
import { SessaoContext } from '../sessao/contexto'
import { TelaDeFiado } from './TelaDeFiado'

afterEach(() => vi.restoreAllMocks())

it('operador registra recebimento parcial e vê comprovante com saldo restante', async () => {
  vi.spyOn(caixa, 'abertaDoOperadorAtual').mockResolvedValue({
    id: 'sessao-1', usuarioId: 'operador-1', valorAbertura: 0,
    valorFechamentoEsperado: 0, valorFechamentoContado: null, diferenca: null,
    abertaEm: '2026-09-23T12:00:00Z', fechadaEm: null, status: 'ABERTA',
  })
  vi.spyOn(fiado, 'dividas').mockResolvedValue([{ vendaId: 'venda-1', clienteId: 'cliente-1',
    nomeCliente: 'Lia', concluidoEm: '2026-09-23T12:00:00Z', saldoDevedor: 20 }])
  const receber = vi.spyOn(vendas, 'receber').mockResolvedValue({ id: 'recebimento-1', saldoDevedor: 13 })
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

  await waitFor(() => expect(receber).toHaveBeenCalledWith('venda-1', 7, 'PIX'))
  expect(await screen.findByLabelText('Comprovante de recebimento não fiscal'))
    .toHaveTextContent('Saldo da Venda após recebimento: R$ 13,00')
})
