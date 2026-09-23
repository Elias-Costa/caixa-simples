import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { relatorios, type Faturamento } from '../api/relatorios'
import { hojeNoBalcao } from '../dataDoBalcao'
import { TelaDeRelatorios } from './TelaDeRelatorios'

afterEach(() => vi.restoreAllMocks())

const doDia: Faturamento = {
  inicio: hojeNoBalcao(), fim: hojeNoBalcao(), total: 12.5, quantidadeDeVendas: 1,
}

describe('faturamento na tela', () => {
  it('abre no dia do balcão e permite consultar um período com os dois extremos', async () => {
    const dia = vi.spyOn(relatorios, 'faturamentoDoDia').mockResolvedValue(doDia)
    const periodo = vi.spyOn(relatorios, 'faturamentoDoPeriodo').mockResolvedValue({
      inicio: '2026-09-01', fim: '2026-09-15', total: 37.5, quantidadeDeVendas: 2,
    })
    render(<TelaDeRelatorios />)

    expect(await screen.findByText(/12,50/)).toBeInTheDocument()
    expect(dia).toHaveBeenCalledWith(hojeNoBalcao())
    expect(screen.getByText('1 venda concluída')).toBeInTheDocument()

    fireEvent.change(screen.getByLabelText('Início'), { target: { value: '2026-09-01' } })
    fireEvent.change(screen.getByLabelText('Fim'), { target: { value: '2026-09-15' } })
    fireEvent.click(screen.getByRole('button', { name: 'Consultar período' }))

    await waitFor(() => expect(periodo).toHaveBeenCalledWith('2026-09-01', '2026-09-15'))
    expect(await screen.findByText(/37,50/)).toBeInTheDocument()
    expect(screen.getByText('Faturamento de 01/09/2026 a 15/09/2026')).toBeInTheDocument()
    expect(screen.getByText('2 vendas concluídas')).toBeInTheDocument()
  })

  it('mostra zero para um dia sem venda e permite voltar para hoje', async () => {
    const dia = vi.spyOn(relatorios, 'faturamentoDoDia').mockResolvedValue({
      ...doDia, total: 0, quantidadeDeVendas: 0,
    })
    vi.spyOn(relatorios, 'faturamentoDoPeriodo').mockResolvedValue({
      inicio: '2026-09-01', fim: '2026-09-02', total: 10, quantidadeDeVendas: 1,
    })
    render(<TelaDeRelatorios />)

    expect(await screen.findByText('Nenhuma venda concluída neste período.')).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('Início'), { target: { value: '2026-09-01' } })
    fireEvent.change(screen.getByLabelText('Fim'), { target: { value: '2026-09-02' } })
    fireEvent.click(screen.getByRole('button', { name: 'Consultar período' }))
    expect(await screen.findByText('1 venda concluída')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Hoje' }))
    await waitFor(() => expect(dia).toHaveBeenCalledTimes(2))
    expect(await screen.findByText('Nenhuma venda concluída neste período.')).toBeInTheDocument()
  })
})
