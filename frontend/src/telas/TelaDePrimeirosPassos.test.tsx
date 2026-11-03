import { fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router'
import { describe, expect, it } from 'vitest'
import { TelaDePrimeirosPassos } from './TelaDePrimeirosPassos'

describe('primeiros passos pelo menu', () => {
  it('abrem no primeiro cartão, passam pelos três sem marca de feito e voltam à entrada', () => {
    render(<MemoryRouter initialEntries={['/primeiros-passos']}>
      <Routes>
        <Route path="/primeiros-passos" element={<TelaDePrimeirosPassos />} />
        <Route path="/" element={<p>Entrada</p>} />
      </Routes>
    </MemoryRouter>)

    expect(screen.getByText('Primeiros passos (1 de 3)')).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Abra o caixa' })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Próximo' }))
    expect(screen.getByRole('heading', { name: 'Venda' })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Anterior' }))
    expect(screen.getByRole('heading', { name: 'Abra o caixa' })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Próximo' }))
    fireEvent.click(screen.getByRole('button', { name: 'Próximo' }))
    expect(screen.getByRole('heading', { name: 'Feche o caixa' })).toBeInTheDocument()
    expect(screen.queryByText('Feito')).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Começar a usar' }))
    expect(screen.getByText('Entrada')).toBeInTheDocument()
  })
})
