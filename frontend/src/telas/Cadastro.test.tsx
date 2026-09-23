import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { cadastro } from '../api/cadastro'
import { SessaoContext } from '../sessao/contexto'
import { TelaDeClientes } from './TelaDeClientes'
import { TelaDeProdutos } from './TelaDeProdutos'

afterEach(() => vi.restoreAllMocks())

const produto = {
  id: 'item-1', tipo: 'PRODUTO' as const, nome: 'Café', preco: 0,
  codigo: null, categoria: 'Bebidas', unidade: 'un', atributos: { gramas: 250 },
}

function mostrarProdutos(perfil: 'ADMIN' | 'OPERADOR') {
  render(<SessaoContext.Provider value={{
    identidade: {
      usuarioId: 'u-1', nome: 'Ana', perfil, contaId: 'c-1',
      nomeNegocio: 'Cafeteria', estoqueHabilitado: false,
    },
    entrar: vi.fn(), sair: vi.fn(), atualizarIdentidade: vi.fn(),
  }}><TelaDeProdutos /></SessaoContext.Provider>)
}

describe('cadastro na tela', () => {
  it('ADMIN precifica item sugerido sem perder atributo numérico do catálogo', async () => {
    vi.spyOn(cadastro, 'produtos').mockResolvedValue([produto])
    const editar = vi.spyOn(cadastro, 'editarProduto').mockResolvedValue(undefined)
    mostrarProdutos('ADMIN')

    expect(await screen.findByText('Café')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Editar' }))
    fireEvent.change(screen.getByLabelText('Preço (R$)'), { target: { value: '12,50' } })
    fireEvent.click(screen.getByRole('button', { name: 'Salvar' }))

    await waitFor(() => expect(editar).toHaveBeenCalledWith('item-1', expect.objectContaining({
      preco: 12.5, atributos: { gramas: 250 },
    })))
  })

  it('OPERADOR consulta produto e não recebe ação de escrita na tela', async () => {
    vi.spyOn(cadastro, 'produtos').mockResolvedValue([produto])
    mostrarProdutos('OPERADOR')

    expect(await screen.findByText('Café')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Novo item' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Editar' })).not.toBeInTheDocument()
  })

  it('cliente inativo pode ser reativado pelo balcão', async () => {
    vi.spyOn(cadastro, 'clientes').mockResolvedValue([])
    vi.spyOn(cadastro, 'clientesInativos').mockResolvedValue([
      { id: 'cliente-1', nome: 'Dona Marta', contato: null },
    ])
    const reativar = vi.spyOn(cadastro, 'reativarCliente').mockResolvedValue(undefined)
    render(<TelaDeClientes />)

    expect(await screen.findByText('Dona Marta')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Reativar' }))
    await waitFor(() => expect(reativar).toHaveBeenCalledWith('cliente-1'))
  })
})
