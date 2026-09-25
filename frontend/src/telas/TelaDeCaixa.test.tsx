import 'fake-indexeddb/auto'
import { deleteDB } from 'idb'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { caixa, type SessaoCaixa } from '../api/caixa'
import { gravarIdentidade, gravarToken } from '../sessao/armazenamento'
import { tokenComExpiracao } from '../sessao/tokenDeTeste'
import { SessaoContext } from '../sessao/contexto'
import { TelaDeCaixa } from './TelaDeCaixa'

afterEach(() => vi.restoreAllMocks())
beforeEach(async () => { await deleteDB('caixa-simples-offline') })

const aberta: SessaoCaixa = {
  id: 's-1', usuarioId: 'u-1', valorAbertura: 20,
  valorFechamentoEsperado: 20, valorFechamentoContado: null, diferenca: null,
  abertaEm: '2026-09-22T12:00:00Z', fechadaEm: null, status: 'ABERTA',
}

function mostrar(perfil: 'ADMIN' | 'OPERADOR' = 'OPERADOR') {
  gravarToken(tokenComExpiracao(new Date(Date.now() + 60 * 60 * 1000)))
  gravarIdentidade({ usuarioId: 'u-1', nome: 'Ana', perfil, contaId: 'c-1',
    nomeNegocio: 'Loja da Esquina', estoqueHabilitado: false })
  render(<SessaoContext.Provider value={{
    identidade: {
      usuarioId: 'u-1', nome: 'Ana', perfil, contaId: 'c-1',
      nomeNegocio: 'Loja da Esquina', estoqueHabilitado: false,
    },
    entrar: vi.fn(), sair: vi.fn(), atualizarIdentidade: vi.fn(),
  }}><TelaDeCaixa /></SessaoContext.Provider>)
}

describe('caixa na tela', () => {
  it('abre o próprio caixa e recarrega a sessão ABERTA', async () => {
    vi.spyOn(caixa, 'abertaDoOperadorAtual').mockResolvedValueOnce(undefined).mockResolvedValue(aberta)
    vi.spyOn(caixa, 'historico').mockResolvedValueOnce([]).mockResolvedValue([aberta])
    vi.spyOn(caixa, 'consultar').mockResolvedValue({ ...aberta, movimentos: [] })
    const abrir = vi.spyOn(caixa, 'abrir').mockResolvedValue({ id: 's-1' })
    mostrar()

    expect(await screen.findByText('Nenhum caixa aberto para você.')).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('Valor inicial'), { target: { value: '20.00' } })
    fireEvent.click(screen.getByRole('button', { name: 'Abrir caixa' }))

    await waitFor(() => expect(abrir).toHaveBeenCalledWith(20))
    expect(await screen.findByRole('button', { name: 'Ver meu caixa' })).toBeInTheDocument()
  })

  it('lança sangria com motivo e valor informado', async () => {
    vi.spyOn(caixa, 'abertaDoOperadorAtual').mockResolvedValue(aberta)
    vi.spyOn(caixa, 'historico').mockResolvedValue([aberta])
    vi.spyOn(caixa, 'consultar').mockResolvedValue({ ...aberta, movimentos: [] })
    const sangrar = vi.spyOn(caixa, 'sangrar').mockResolvedValue(undefined)
    mostrar()

    expect(await screen.findByRole('button', { name: 'Registrar sangria' })).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('Valor'), { target: { value: '5.00' } })
    fireEvent.change(screen.getByLabelText('Motivo'), { target: { value: 'Retirada' } })
    fireEvent.click(screen.getByRole('button', { name: 'Registrar sangria' }))

    await waitFor(() => expect(sangrar).toHaveBeenCalledWith('s-1', 5, 'Retirada'))
  })

  it('mostra esperado antes de fechar, diferença depois e Venda no extrato', async () => {
    let fechada = false
    const sessaoFechada: SessaoCaixa = {
      ...aberta, status: 'FECHADA', valorFechamentoContado: 18, diferenca: 2,
      fechadaEm: '2026-09-22T15:00:00Z',
    }
    const movimentos = [{
      id: 'm-1', tipo: 'VENDA' as const, valor: 5, motivo: null,
      vendaId: 'v-1', recebimentoId: null, criadoEm: '2026-09-22T14:00:00Z',
    }]
    vi.spyOn(caixa, 'abertaDoOperadorAtual').mockImplementation(async () => fechada ? undefined : aberta)
    vi.spyOn(caixa, 'historico').mockImplementation(async () => [fechada ? sessaoFechada : aberta])
    vi.spyOn(caixa, 'consultar').mockImplementation(async () => ({
      ...(fechada ? sessaoFechada : aberta), movimentos,
    }))
    const fechar = vi.spyOn(caixa, 'fechar').mockImplementation(async () => {
      fechada = true
      return { diferenca: 2 }
    })
    mostrar()

    expect(await screen.findByText(/Venda v-1/)).toBeInTheDocument()
    expect(screen.getByText(/Saldo esperado:/)).toHaveTextContent('R$')
    fireEvent.change(screen.getByLabelText('Valor contado'), { target: { value: '18.00' } })
    fireEvent.click(screen.getByRole('button', { name: 'Fechar e registrar diferença' }))

    await waitFor(() => expect(fechar).toHaveBeenCalledWith('s-1', 18))
    expect(await screen.findByText(/Diferença:/)).toHaveTextContent('R$')
  })

  it('mostra a conferência e fecha a SessaoCaixa sem chamar a API quando está offline', async () => {
    vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(false)
    const remoto = vi.spyOn(caixa, 'abrir')
    const fecharRemoto = vi.spyOn(caixa, 'fechar')
    mostrar()

    expect(await screen.findByText('Nenhum caixa aberto para você.')).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('Valor inicial'), { target: { value: '10.00' } })
    fireEvent.click(screen.getByRole('button', { name: 'Abrir caixa' }))
    expect((await screen.findAllByText('Pendente de sincronização com o servidor.')).length).toBeGreaterThan(0)
    fireEvent.change(screen.getByLabelText('Valor contado'), { target: { value: '8.00' } })
    expect(screen.getByText(/Diferença prevista:/)).toHaveTextContent('R$ 2,00')
    fireEvent.click(screen.getByRole('button', { name: 'Fechar e registrar diferença' }))
    expect(await screen.findByText(/Diferença:/)).toHaveTextContent('R$ 2,00')
    expect(remoto).not.toHaveBeenCalled()
    expect(fecharRemoto).not.toHaveBeenCalled()
  })
})
