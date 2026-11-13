import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { MemoryRouter, Route, Routes } from 'react-router'
import { ErroDaApi, SemConexao } from '../api/cliente'
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
      identidade, entrar: vi.fn(), sair: vi.fn(), atualizarIdentidade: vi.fn(), relerIdentidade: vi.fn(),
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

    await waitFor(() => expect(ajustar).toHaveBeenCalledWith('arroz', -2, 'contagem do dia', expect.any(String)))
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
    await waitFor(() => expect(entrada).toHaveBeenCalledWith('arroz', 12.5, '', expect.any(String)))
    expect(await screen.findByRole('status')).toHaveTextContent('Entrada registrada.')

    fireEvent.change(screen.getByLabelText('Quantidade comprada'), { target: { value: '3' } })
    fireEvent.change(screen.getByLabelText('Motivo (opcional)'), { target: { value: '  nota 123 ' } })
    fireEvent.click(screen.getByRole('button', { name: 'Registrar entrada' }))
    await waitFor(() => expect(entrada).toHaveBeenLastCalledWith('arroz', 3, 'nota 123', expect.any(String)))
  })

  it('lista o saldo de todos, põe o negativo no alto do alerta com a marca, e o toque escolhe o produto', async () => {
    const pao: EstoqueDoProduto = { id: 'pao', nome: 'Pão francês', codigo: null, unidade: 'kg',
      estoqueAtual: -19.173, estoqueMinimo: 5 }
    const leite: EstoqueDoProduto = { id: 'leite', nome: 'Leite', codigo: '002', unidade: 'un',
      estoqueAtual: 2, estoqueMinimo: 6 }
    const acucar: EstoqueDoProduto = { id: 'acucar', nome: 'Açúcar', codigo: null, unidade: 'kg',
      estoqueAtual: 12, estoqueMinimo: 2 }
    vi.spyOn(estoque, 'produtos').mockResolvedValue([pao, leite, acucar])
    vi.spyOn(estoque, 'baixo').mockResolvedValue([leite, pao])
    render(<TelaDeEstoque />)

    const alerta = (await screen.findByRole('heading', { name: 'Estoque baixo' })).closest('section')!
    const linhasDoAlerta = within(screen.getByRole('table', { name: 'Estoque baixo' })).getAllByRole('row').slice(1)
    expect(linhasDoAlerta.map((linha) => [...linha.children].map((celula) => celula.textContent))).toEqual([
      ['Pão francêsnegativo', '-19,173 kg', '5 kg'],
      ['Leite', '2 un', '6 un'],
    ])
    expect(alerta).toHaveTextContent('Saldo negativo é venda acima do estoque registrado. Conte a prateleira e lance a contagem física no ajuste.')

    const tabela = screen.getByRole('table', { name: 'Saldo dos produtos' })
    expect(within(tabela).getAllByRole('rowheader').map((celula) => within(celula).getByRole('button').textContent))
      .toEqual(['Açúcar', 'Leite', 'Pão francês'])
    const seletor = screen.getByLabelText('Produto')
    expect(seletor).toHaveValue('acucar')
    fireEvent.change(screen.getByLabelText('Quantidade comprada'), { target: { value: '3' } })
    fireEvent.click(within(tabela).getByRole('button', { name: 'Leite' }))
    expect(seletor).toHaveValue('leite')
    expect(seletor).toHaveFocus()
    // O que se digitou para outro produto não segue para o escolhido.
    expect(screen.getByLabelText('Quantidade comprada')).toHaveValue('')

    fireEvent.click(within(alerta).getByRole('button', { name: 'Pão francês' }))
    expect(seletor).toHaveValue('pao')
    expect(screen.getByText(/Saldo atual/)).toHaveTextContent('Saldo atual: -19,173 kg negativo · mínimo: 5 kg')
  })

  it('o ajuste igual depois da resposta perdida leva o mesmo id, e depois da resposta o seguinte leva outro', async () => {
    vi.spyOn(estoque, 'produtos').mockResolvedValue([arroz])
    vi.spyOn(estoque, 'baixo').mockResolvedValue([arroz])
    const ajustar = vi.spyOn(estoque, 'ajustar')
      .mockRejectedValueOnce(new SemConexao())
      .mockRejectedValueOnce(new SemConexao())
      .mockResolvedValue(undefined)
    render(<TelaDeEstoque />)

    fireEvent.change(await screen.findByLabelText('Diferença com sinal'), { target: { value: '-2' } })
    fireEvent.change(screen.getByLabelText('Motivo'), { target: { value: 'quebra' } })
    const confirmar = screen.getByRole('button', { name: 'Confirmar ajuste' })
    fireEvent.click(confirmar)
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Não foi possível confirmar a resposta do servidor. Reabra a lista antes de repetir.')
    // O segundo toque, e o terceiro, que o servidor responde, levam o id do primeiro.
    for (const vez of [2, 3]) {
      await waitFor(() => expect(confirmar).toBeEnabled())
      fireEvent.click(confirmar)
      await waitFor(() => expect(ajustar).toHaveBeenCalledTimes(vez))
    }
    expect(await screen.findByRole('status')).toHaveTextContent('Estoque ajustado.')
    const [primeiro, segundo, terceiro] = ajustar.mock.calls.map((chamada) => chamada[3])
    expect(primeiro).toEqual(expect.any(String))
    expect(segundo).toBe(primeiro)
    expect(terceiro).toBe(primeiro)

    // Respondido, o mesmo ajuste de novo é outro movimento.
    fireEvent.change(screen.getByLabelText('Diferença com sinal'), { target: { value: '-2' } })
    fireEvent.change(screen.getByLabelText('Motivo'), { target: { value: 'quebra' } })
    fireEvent.click(confirmar)
    await waitFor(() => expect(ajustar).toHaveBeenCalledTimes(4))
    expect(ajustar.mock.calls[3][3]).not.toBe(primeiro)
  })

  it('na entrada, outro conteúdo e a recusa do servidor liberam o id da tentativa sem resposta', async () => {
    vi.spyOn(estoque, 'produtos').mockResolvedValue([arroz])
    vi.spyOn(estoque, 'baixo').mockResolvedValue([arroz])
    const entrada = vi.spyOn(estoque, 'registrarEntrada')
      .mockRejectedValueOnce(new SemConexao())
      .mockRejectedValueOnce(new SemConexao())
      .mockRejectedValueOnce(new ErroDaApi(409, 'Conflict', 'O produto Arroz está inativo e não recebe'
        + ' entrada de estoque.', {}))
      .mockResolvedValue(undefined)
    render(<TelaDeEstoque />)

    const quantidade = await screen.findByLabelText('Quantidade comprada')
    const registrar = screen.getByRole('button', { name: 'Registrar entrada' })
    for (const [vez, comprado] of [[1, '12'], [2, '13'], [3, '13'], [4, '13']] as const) {
      fireEvent.change(quantidade, { target: { value: comprado } })
      await waitFor(() => expect(registrar).toBeEnabled())
      fireEvent.click(registrar)
      await waitFor(() => expect(entrada).toHaveBeenCalledTimes(vez))
    }

    const ids = entrada.mock.calls.map((chamada) => chamada[3])
    // 13 é outro conteúdo; o 13 repetido leva o mesmo id; depois da recusa, um id novo.
    expect(ids[1]).not.toBe(ids[0])
    expect(ids[2]).toBe(ids[1])
    expect(ids[3]).not.toBe(ids[2])
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
