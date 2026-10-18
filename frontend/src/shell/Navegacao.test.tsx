import { fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router'
import { describe, expect, it } from 'vitest'
import type { Identidade } from '../sessao/Identidade'
import { itensDoMenu } from './menu'
import { Navegacao } from './Navegacao'

const administradora: Identidade = {
  usuarioId: '1', nome: 'Ana', perfil: 'ADMIN', contaId: '2', nomeNegocio: 'Cafeteria Aurora',
  estoqueHabilitado: false, recursos: ['RELATORIOS'],
}

function mostrar(caminho: string) {
  render(<MemoryRouter initialEntries={[caminho]}>
    <Navegacao itens={itensDoMenu(administradora)} />
    <Routes><Route path="*" element={null} /></Routes>
  </MemoryRouter>)
}

describe('barra de navegação', () => {
  it('o Mais abre a lista dos outros itens e fecha quando um deles é escolhido', () => {
    mostrar('/vender')
    const mais = screen.getByRole('button', { name: 'Mais' })
    expect(mais).toHaveAttribute('aria-expanded', 'false')
    expect(document.getElementById(mais.getAttribute('aria-controls')!))
      .toHaveTextContent('Usuários')

    fireEvent.click(mais)
    expect(mais).toHaveAttribute('aria-expanded', 'true')
    fireEvent.click(screen.getByRole('link', { name: 'Usuários' }))
    expect(screen.getByRole('button', { name: 'Mais' })).toHaveAttribute('aria-expanded', 'false')
  })

  it('numa página de dentro do Mais, o Mais fica marcado', () => {
    mostrar('/senha')
    expect(screen.getByRole('button', { name: 'Mais' })).toHaveClass('navegacao__item--ativo')
    expect(screen.getByRole('link', { name: 'Minha conta' })).toHaveClass('navegacao__item--ativo')
  })

  it('numa página do balcão, o Mais não fica marcado', () => {
    mostrar('/vender')
    expect(screen.getByRole('button', { name: 'Mais' })).not.toHaveClass('navegacao__item--ativo')
    expect(screen.getByRole('link', { name: 'Vender' })).toHaveClass('navegacao__item--ativo')
  })
})
