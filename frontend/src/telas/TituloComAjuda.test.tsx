import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { AJUDA } from './textosDeAjuda'
import { TituloComAjuda } from './TituloComAjuda'

describe('ajuda por tela', () => {
  it('cada tela tem de uma a três linhas, e nenhuma linha vazia', () => {
    expect(Object.keys(AJUDA)).toHaveLength(12)
    for (const [tela, linhas] of Object.entries(AJUDA)) {
      expect(linhas.length, tela).toBeGreaterThan(0)
      expect(linhas.length, tela).toBeLessThanOrEqual(3)
      for (const linha of linhas) expect(linha.trim(), tela).not.toBe('')
    }
  })

  it('o botão ao lado do título abre e fecha as linhas da tela', () => {
    render(<TituloComAjuda titulo="Caixa" ajuda={AJUDA.caixa} />)
    const botao = screen.getByRole('button', { name: 'Ajuda desta tela' })

    expect(screen.getByRole('heading', { name: 'Caixa' })).toBeInTheDocument()
    expect(botao).toHaveAttribute('aria-expanded', 'false')
    expect(screen.queryByText(AJUDA.caixa[0])).not.toBeInTheDocument()

    fireEvent.click(botao)
    expect(botao).toHaveAttribute('aria-expanded', 'true')
    for (const linha of AJUDA.caixa) expect(screen.getByText(linha)).toBeInTheDocument()

    fireEvent.click(botao)
    expect(screen.queryByText(AJUDA.caixa[0])).not.toBeInTheDocument()
  })
})
