import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { cadastro } from '../api/cadastro'
import { ErroDaApi } from '../api/cliente'
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
    expect(await screen.findByRole('status')).toHaveTextContent('Café salvo.')
  })

  it('o preço em edição vem com vírgula, e o erro dele aparece no formulário, perto do botão', async () => {
    vi.spyOn(cadastro, 'produtos').mockResolvedValue([{ ...produto, preco: 6.5 }])
    const editar = vi.spyOn(cadastro, 'editarProduto')
    mostrarProdutos('ADMIN')

    fireEvent.click(await screen.findByRole('button', { name: 'Editar' }))
    expect(screen.getByLabelText('Preço (R$)')).toHaveValue('6,50')
    fireEvent.change(screen.getByLabelText('Preço (R$)'), { target: { value: '5,505' } })
    fireEvent.click(screen.getByRole('button', { name: 'Salvar' }))

    const formulario = screen.getByRole('heading', { name: 'Editar item' }).closest('form')!
    expect(within(formulario).getByRole('alert')).toHaveTextContent('até duas casas decimais')
    expect(editar).not.toHaveBeenCalled()
  })

  it('OPERADOR consulta produto e não recebe ação de escrita na tela', async () => {
    vi.spyOn(cadastro, 'produtos').mockResolvedValue([produto])
    mostrarProdutos('OPERADOR')

    expect(await screen.findByText('Café')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Novo item' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Editar' })).not.toBeInTheDocument()
    // O catálogo sugerido nasce sem preço, e a lista lembra o que falta precificar.
    expect(screen.getByText('R$ 0,00')).toBeInTheDocument()
    expect(screen.getByText('preço zerado')).toBeInTheDocument()
  })

  it('a recusa da validação marca o campo e diz o porquê em português', async () => {
    vi.spyOn(cadastro, 'clientes').mockResolvedValue([])
    vi.spyOn(cadastro, 'clientesInativos').mockResolvedValue([])
    vi.spyOn(cadastro, 'criarCliente').mockRejectedValue(new ErroDaApi(400, 'Bad Request',
      'Invalid request content.', { nome: 'não deve estar em branco' }))
    render(<TelaDeClientes />)

    fireEvent.click(await screen.findByRole('button', { name: 'Novo cliente' }))
    fireEvent.change(screen.getByLabelText('Nome'), { target: { value: '   ' } })
    fireEvent.click(screen.getByRole('button', { name: 'Salvar' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Confira: nome não deve estar em branco.')
    expect(screen.queryByText(/Invalid request/)).not.toBeInTheDocument()
    const nome = screen.getByLabelText('Nome')
    expect(nome).toHaveAttribute('aria-invalid', 'true')
    expect(nome).toHaveAccessibleDescription('não deve estar em branco')
    await waitFor(() => expect(nome).toHaveFocus())
  })

  it('a busca filtra a lista de produtos já carregada pelo nome, sem diferença de maiúsculas nem de acento', async () => {
    const buscar = vi.spyOn(cadastro, 'buscarProdutos')
    vi.spyOn(cadastro, 'produtos').mockResolvedValue([
      produto, { ...produto, id: 'item-2', nome: 'Pão de queijo', preco: 4 },
    ])
    mostrarProdutos('OPERADOR')

    fireEvent.change(await screen.findByLabelText('Buscar por nome'), { target: { value: 'PÃO' } })
    expect(screen.getByText('Pão de queijo')).toBeInTheDocument()
    expect(screen.queryByText('Café')).not.toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('Buscar por nome'), { target: { value: 'pao' } })
    expect(screen.getByText('Pão de queijo')).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('Buscar por nome'), { target: { value: 'chá' } })
    expect(screen.getByRole('status')).toHaveTextContent('Nenhum item encontrado para chá.')
    expect(buscar).not.toHaveBeenCalled()
  })

  it('a busca de clientes vale para ativos e inativos', async () => {
    vi.spyOn(cadastro, 'clientes').mockResolvedValue([
      { id: 'cliente-1', nome: 'Lia', contato: null },
      { id: 'cliente-2', nome: 'Marta Lima', contato: null },
    ])
    vi.spyOn(cadastro, 'clientesInativos').mockResolvedValue([
      { id: 'cliente-3', nome: 'Dona Marta', contato: null },
    ])
    render(<TelaDeClientes />)

    fireEvent.change(await screen.findByLabelText('Buscar por nome'), { target: { value: 'marta' } })
    expect(screen.getByText('Marta Lima')).toBeInTheDocument()
    expect(screen.getByText('Dona Marta')).toBeInTheDocument()
    expect(screen.queryByText('Lia')).not.toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('Buscar por nome'), { target: { value: 'joão' } })
    expect(screen.getByRole('status')).toHaveTextContent('Nenhum cliente ativo encontrado para joão.')
    expect(screen.queryByRole('heading', { name: 'Inativos' })).not.toBeInTheDocument()
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
