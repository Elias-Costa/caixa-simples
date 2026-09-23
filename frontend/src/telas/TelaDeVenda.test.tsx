import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { cadastro } from '../api/cadastro'
import { caixa } from '../api/caixa'
import { fiado } from '../api/fiado'
import { vendas, type Comprovante, type Venda } from '../api/vendas'
import { SessaoContext } from '../sessao/contexto'
import { TelaDeVenda } from './TelaDeVenda'

afterEach(() => vi.restoreAllMocks())

const aberta = {
  id: 'sessao-1', usuarioId: 'usuario-1', valorAbertura: 0, valorFechamentoEsperado: 0,
  valorFechamentoContado: null, diferenca: null, abertaEm: '2026-09-22T12:00:00Z',
  fechadaEm: null, status: 'ABERTA' as const,
}
const comanda: Venda = {
  id: 'venda-1', sessaoCaixaId: aberta.id, usuarioId: aberta.usuarioId,
  clienteId: null, saldoDevedor: 0, recebimentos: [],
  status: 'ABERTA', criadoEm: aberta.abertaEm, descontoDaVenda: 0, total: 12.5,
  pago: 0, faltaPagar: 12.5,
  itens: [{ id: 'item-1', produtoId: 'produto-1', nome: 'Café', quantidade: 1,
    precoUnitario: 12.5, desconto: 0, subtotal: 12.5 }], parcelas: [],
}
const comprovante: Comprovante = {
  vendaId: comanda.id, usuarioId: comanda.usuarioId,
  concluidoEm: '2026-09-22T12:05:00Z',
  linhas: [{ produtoId: 'produto-1', nome: 'Café', unidade: 'un', quantidade: 1,
    precoUnitario: 12.5, valorBruto: 12.5, desconto: 0, subtotal: 12.5 }],
  somaDosItens: 12.5, descontoDaVenda: 0, valorTotal: 12.5,
  parcelas: [{ forma: 'DINHEIRO', valor: 12.5, troco: 2.5 }], troco: 2.5,
  valorFiado: 0, saldoDevedor: 0,
}

function mostrar(perfil: 'ADMIN' | 'OPERADOR' = 'OPERADOR') {
  render(<MemoryRouter><SessaoContext.Provider value={{
    identidade: { usuarioId: 'usuario-1', nome: 'Ana', perfil,
      contaId: 'conta-1', nomeNegocio: 'Loja da Esquina', estoqueHabilitado: false },
    entrar: vi.fn(), sair: vi.fn(), atualizarIdentidade: vi.fn(),
  }}><TelaDeVenda /></SessaoContext.Provider></MemoryRouter>)
}

describe('PDV no tablet', () => {
  it('conclui a venda simples em dinheiro em menos de seis toques e exibe o comprovante', async () => {
    let concluida = false
    vi.spyOn(caixa, 'abertaDoOperadorAtual').mockResolvedValue(aberta)
    vi.spyOn(vendas, 'daSessao').mockResolvedValue([])
    vi.spyOn(cadastro, 'buscarProdutos').mockResolvedValue([{ id: 'produto-1', tipo: 'PRODUTO',
      nome: 'Café', preco: 12.5, codigo: 'CA-1', categoria: null, unidade: 'un', atributos: {} }])
    const iniciar = vi.spyOn(vendas, 'iniciar').mockResolvedValue({ id: 'venda-1' })
    const adicionar = vi.spyOn(vendas, 'adicionarItem').mockResolvedValue({ id: 'item-1' })
    vi.spyOn(vendas, 'consultar').mockImplementation(async () => concluida
      ? { ...comanda, status: 'CONCLUIDA', pago: 12.5, faltaPagar: 0 } : comanda)
    const pagar = vi.spyOn(vendas, 'pagar').mockResolvedValue({ troco: 2.5 })
    const concluir = vi.spyOn(vendas, 'concluir').mockImplementation(async () => { concluida = true })
    vi.spyOn(vendas, 'comprovante').mockResolvedValue(comprovante)
    mostrar()

    const busca = await screen.findByLabelText('Produto por nome ou código')
    let toques = 0
    const contar = () => { toques++ }
    document.addEventListener('click', contar)
    fireEvent.change(busca, { target: { value: 'CA-1' } })
    fireEvent.click(await screen.findByRole('button', { name: /Café · R\$\s*12,50/ }))
    await waitFor(() => expect(adicionar).toHaveBeenCalledWith('venda-1', 'produto-1', 1, 0))
    expect(iniciar).toHaveBeenCalledWith('sessao-1')
    fireEvent.click(screen.getByLabelText('Valor recebido em dinheiro'))
    fireEvent.change(screen.getByLabelText('Valor recebido em dinheiro'), { target: { value: '15.00' } })
    fireEvent.click(screen.getByRole('button', { name: 'Receber e concluir' }))

    await waitFor(() => expect(pagar).toHaveBeenCalledWith('venda-1', 'DINHEIRO', 12.5, 15))
    await waitFor(() => expect(concluir).toHaveBeenCalledWith('venda-1'))
    expect(await screen.findByLabelText('Comprovante não fiscal')).toHaveTextContent('Troco: R$ 2,50')
    expect(toques).toBeLessThanOrEqual(6)
    document.removeEventListener('click', contar)
  })

  it('mostra comanda ABERTA da sessão para retomar ou cancelar após recarga', async () => {
    vi.spyOn(caixa, 'abertaDoOperadorAtual').mockResolvedValue(aberta)
    vi.spyOn(vendas, 'daSessao').mockResolvedValue([{ id: comanda.id,
      sessaoCaixaId: comanda.sessaoCaixaId, usuarioId: comanda.usuarioId,
      status: 'ABERTA', total: comanda.total, criadoEm: comanda.criadoEm }])
    vi.spyOn(vendas, 'consultar').mockResolvedValue(comanda)
    const cancelar = vi.spyOn(vendas, 'cancelar').mockResolvedValue(undefined)
    mostrar()

    fireEvent.click(await screen.findByRole('button', { name: /ABERTA.*retomar ou cancelar/ }))
    expect(await screen.findByText(/1 × Café/)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Cancelar venda' }))
    await waitFor(() => expect(cancelar).toHaveBeenCalledWith('venda-1'))
  })

  it('administrador vincula Cliente e conclui fiado com valor pendente no comprovante', async () => {
    let vinculada = false
    let paga = false
    let concluida = false
    vi.spyOn(caixa, 'abertaDoOperadorAtual').mockResolvedValue(aberta)
    vi.spyOn(cadastro, 'clientes').mockResolvedValue([{ id: 'cliente-1', nome: 'Lia', contato: null }])
    vi.spyOn(fiado, 'saldo').mockResolvedValue({ clienteId: 'cliente-1', saldoDevedor: 0 })
    vi.spyOn(vendas, 'daSessao').mockResolvedValue([{ id: comanda.id,
      sessaoCaixaId: comanda.sessaoCaixaId, usuarioId: comanda.usuarioId,
      status: 'ABERTA', total: comanda.total, criadoEm: comanda.criadoEm }])
    vi.spyOn(vendas, 'consultar').mockImplementation(async () => ({ ...comanda,
      clienteId: vinculada ? 'cliente-1' : null,
      status: concluida ? 'CONCLUIDA' : 'ABERTA',
      pago: paga ? 12.5 : 0, faltaPagar: paga ? 0 : 12.5,
      saldoDevedor: concluida ? 12.5 : 0,
      parcelas: paga ? [{ id: 'parcela-1', forma: 'FIADO', valor: 12.5,
        status: 'PENDENTE', troco: 0 }] : [],
    }))
    const vincular = vi.spyOn(vendas, 'vincularCliente').mockImplementation(async () => { vinculada = true })
    const pagar = vi.spyOn(vendas, 'pagar').mockImplementation(async () => {
      paga = true; return { troco: 0 }
    })
    vi.spyOn(vendas, 'concluir').mockImplementation(async () => { concluida = true })
    vi.spyOn(vendas, 'comprovante').mockResolvedValue({ ...comprovante,
      parcelas: [{ forma: 'FIADO', valor: 12.5, troco: 0 }], troco: 0,
      valorFiado: 12.5, saldoDevedor: 12.5,
    })
    mostrar('ADMIN')

    fireEvent.click(await screen.findByRole('button', { name: /ABERTA.*retomar ou cancelar/ }))
    fireEvent.change(await screen.findByLabelText('Cliente (para fiado)'), { target: { value: 'cliente-1' } })
    await waitFor(() => expect(vincular).toHaveBeenCalledWith('venda-1', 'cliente-1'))
    fireEvent.change(await screen.findByLabelText('Forma'), { target: { value: 'FIADO' } })
    fireEvent.click(screen.getByRole('button', { name: 'Registrar fiado e concluir' }))
    await waitFor(() => expect(pagar).toHaveBeenCalledWith('venda-1', 'FIADO', 12.5, undefined))
    expect(await screen.findByLabelText('Comprovante não fiscal'))
      .toHaveTextContent('Valor pendente: R$ 12,50')
  })
})
