import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { MemoryRouter, Route, Routes } from 'react-router'
import { estoque, type EstoqueDoProduto } from '../api/estoque'
import { SessaoContext } from '../sessao/contexto'
import type { Identidade } from '../sessao/Identidade'
import { ExigeAdmin } from '../shell/ExigeAdmin'
import { TelaDeEstoque } from './TelaDeEstoque'

afterEach(() => vi.restoreAllMocks())

const arroz: EstoqueDoProduto = {
  id: 'arroz', nome: 'Arroz', codigo: '001', unidade: 'un', estoqueAtual: 5,
  estoqueMinimo: 6,
}

describe('estoque na tela', () => {
  it.each([
    ['conta desligada', 'ADMIN', false],
    ['operador', 'OPERADOR', true],
  ] as const)('esconde a rota da %s', async (_cenario, perfil, estoqueHabilitado) => {
    const produtos = vi.spyOn(estoque, 'produtos')
    const identidade: Identidade = {
      usuarioId: 'usuario', nome: 'Pessoa', perfil, contaId: 'conta',
      nomeNegocio: 'Loja', estoqueHabilitado,
    }
    render(<SessaoContext.Provider value={{
      identidade, entrar: vi.fn(), sair: vi.fn(), atualizarIdentidade: vi.fn(),
    }}><MemoryRouter initialEntries={['/estoque']}><Routes>
      <Route path="/" element={<p>Início</p>} />
      <Route element={<ExigeAdmin comEstoque />}>
        <Route path="/estoque" element={<TelaDeEstoque />} />
      </Route>
    </Routes></MemoryRouter></SessaoContext.Provider>)

    expect(await screen.findByText('Início')).toBeInTheDocument()
    expect(produtos).not.toHaveBeenCalled()
  })

  it('mostra o alerta e lança a diferença calculada de uma contagem', async () => {
    vi.spyOn(estoque, 'produtos').mockResolvedValue([arroz])
    vi.spyOn(estoque, 'baixo').mockResolvedValue([arroz])
    const ajustar = vi.spyOn(estoque, 'ajustar').mockResolvedValue(undefined)
    render(<TelaDeEstoque />)

    expect(await screen.findByText('Estoque baixo')).toBeInTheDocument()
    expect(screen.getByRole('option', { name: /Arroz/ })).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('Modo'), { target: { value: 'contagem' } })
    fireEvent.change(screen.getByLabelText('Quantidade contada'), { target: { value: '3' } })
    expect(screen.getByText(/Diferença a lançar/)).toHaveTextContent('-2')
    fireEvent.change(screen.getByLabelText('Motivo'), { target: { value: 'contagem do dia' } })
    fireEvent.click(screen.getByRole('button', { name: 'Confirmar ajuste' }))

    await waitFor(() => expect(ajustar).toHaveBeenCalledWith('arroz', -2, 'contagem do dia'))
    expect(await screen.findByRole('status')).toHaveTextContent('Estoque ajustado')
  })

  it('registra a entrada pela quantidade comprada, com o motivo opcional, e recusa zero na tela', async () => {
    vi.spyOn(estoque, 'produtos').mockResolvedValue([arroz])
    vi.spyOn(estoque, 'baixo').mockResolvedValue([arroz])
    const entrada = vi.spyOn(estoque, 'registrarEntrada').mockResolvedValue(undefined)
    render(<TelaDeEstoque />)

    const quantidade = await screen.findByLabelText('Quantidade comprada')
    expect(screen.getByLabelText('Motivo (opcional)')).toHaveAttribute('maxLength', '120')
    fireEvent.change(quantidade, { target: { value: '0' } })
    fireEvent.click(screen.getByRole('button', { name: 'Registrar entrada' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('A quantidade da entrada precisa ser maior que zero.')
    expect(entrada).not.toHaveBeenCalled()

    fireEvent.change(quantidade, { target: { value: '12,5' } })
    fireEvent.click(screen.getByRole('button', { name: 'Registrar entrada' }))
    await waitFor(() => expect(entrada).toHaveBeenCalledWith('arroz', 12.5, ''))
    expect(await screen.findByRole('status')).toHaveTextContent('Entrada registrada.')

    fireEvent.change(screen.getByLabelText('Quantidade comprada'), { target: { value: '3' } })
    fireEvent.change(screen.getByLabelText('Motivo (opcional)'), { target: { value: '  nota 123 ' } })
    fireEvent.click(screen.getByRole('button', { name: 'Registrar entrada' }))
    await waitFor(() => expect(entrada).toHaveBeenLastCalledWith('arroz', 3, 'nota 123'))
  })

  it('define o mínimo e recusa diferença zero na tela', async () => {
    vi.spyOn(estoque, 'produtos').mockResolvedValue([arroz])
    vi.spyOn(estoque, 'baixo').mockResolvedValue([arroz])
    const ajustar = vi.spyOn(estoque, 'ajustar').mockResolvedValue(undefined)
    const minimo = vi.spyOn(estoque, 'definirMinimo').mockResolvedValue(undefined)
    render(<TelaDeEstoque />)

    fireEvent.change(await screen.findByLabelText('Diferença com sinal'), { target: { value: '0' } })
    fireEvent.change(screen.getByLabelText('Motivo'), { target: { value: 'contagem' } })
    fireEvent.click(screen.getByRole('button', { name: 'Confirmar ajuste' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('diferente de zero')
    expect(ajustar).not.toHaveBeenCalled()

    fireEvent.change(screen.getByLabelText('Novo estoque mínimo'), { target: { value: '8,5' } })
    fireEvent.click(screen.getByRole('button', { name: 'Salvar mínimo' }))
    await waitFor(() => expect(minimo).toHaveBeenCalledWith('arroz', 8.5))
  })
})
