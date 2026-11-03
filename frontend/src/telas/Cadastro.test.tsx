import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cadastro } from '../api/cadastro'
import { ErroDaApi } from '../api/cliente'
import { gravarIdentidade, gravarToken, limparSessao } from '../sessao/armazenamento'
import { SessaoContext } from '../sessao/contexto'
import { tokenComExpiracao } from '../sessao/tokenDeTeste'
import { confirmacaoAberta, responderConfirmacao } from './confirmacaoDeTeste'
import { TelaDeClientes } from './TelaDeClientes'
import { TelaDeProdutos } from './TelaDeProdutos'

// Só o administrador lê os inativos; o teste que precisa deles troca a resposta.
beforeEach(() => { vi.spyOn(cadastro, 'produtosInativos').mockResolvedValue([]) })
afterEach(() => { vi.restoreAllMocks(); limparSessao() })

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

  it('avisa o nome repetido de outro item ativo sem impedir o salvar, e não acusa o próprio item', async () => {
    vi.spyOn(cadastro, 'produtos').mockResolvedValue([{ ...produto, nome: 'Pão de queijo', preco: 3.5 }])
    const criar = vi.spyOn(cadastro, 'criarProduto').mockResolvedValue({ id: 'item-2' })
    mostrarProdutos('ADMIN')

    fireEvent.click(await screen.findByRole('button', { name: 'Editar' }))
    expect(screen.getByLabelText('Nome')).not.toHaveAccessibleDescription()
    fireEvent.click(screen.getByRole('button', { name: 'Cancelar' }))

    fireEvent.click(screen.getByRole('button', { name: 'Novo item' }))
    const nome = screen.getByLabelText('Nome')
    // Acento, maiúscula e espaço nas pontas não contam, como na busca do balcão.
    fireEvent.change(nome, { target: { value: ' PAO DE QUEIJO ' } })
    expect(nome).toHaveAccessibleDescription(
      'Já existe um item ativo com este nome. Na busca do balcão, os dois aparecem iguais.')
    fireEvent.change(screen.getByLabelText('Preço (R$)'), { target: { value: '6,10' } })
    fireEvent.click(screen.getByRole('button', { name: 'Salvar' }))
    await waitFor(() => expect(criar).toHaveBeenCalledWith(expect.objectContaining({ preco: 6.1 })))
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
    expect(screen.getByText('R$ 0,00/un')).toBeInTheDocument()
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

  it('a lista continua em ordem alfabética depois de reativar, que devolve o cliente no fim', async () => {
    const bruno = { id: 'cliente-b', nome: 'Bruno', contato: null }
    const carla = { id: 'cliente-c', nome: 'Carla', contato: null }
    const ana = { id: 'cliente-a', nome: 'Ana', contato: null }
    let reativada = false
    // O servidor devolve na ordem em que guarda: a reativada vem por último.
    vi.spyOn(cadastro, 'clientes').mockImplementation(async () => reativada ? [bruno, carla, ana] : [bruno, carla])
    vi.spyOn(cadastro, 'clientesInativos').mockImplementation(async () => reativada ? [] : [ana])
    vi.spyOn(cadastro, 'reativarCliente').mockImplementation(async () => { reativada = true })
    render(<TelaDeClientes />)

    fireEvent.click(await screen.findByRole('button', { name: 'Reativar' }))
    await screen.findByText('Ana reativado.')
    const ativos = screen.getByRole('heading', { name: 'Ativos' }).closest('section') as HTMLElement
    expect(within(ativos).getAllByRole('listitem').map((item) => item.textContent))
      .toEqual([expect.stringMatching(/^Ana/), expect.stringMatching(/^Bruno/), expect.stringMatching(/^Carla/)])
  })

  it('produtos em ordem alfabética, com o preço da unidade de venda e o filtro de categoria', async () => {
    vi.spyOn(cadastro, 'produtos').mockResolvedValue([
      { ...produto, id: 'item-1', nome: 'Sanduíche natural', categoria: 'Lanches', preco: 12 },
      { ...produto, id: 'item-2', nome: 'Muçarela', categoria: 'Frios', unidade: 'kg', preco: 54.9 },
      { ...produto, id: 'item-3', nome: 'Água', categoria: null, unidade: null, preco: 3 },
      { ...produto, id: 'item-4', nome: 'Cappuccino', categoria: 'Bebidas', preco: 9 },
    ])
    mostrarProdutos('OPERADOR')

    const nomes = async () => (await screen.findAllByRole('listitem')).map((item) => item.querySelector('strong')?.textContent)
    expect(await nomes()).toEqual(['Água', 'Cappuccino', 'Muçarela', 'Sanduíche natural'])
    expect(screen.getByText('R$ 54,90/kg')).toBeInTheDocument()
    // Sem unidade, o preço sai sozinho, como no balcão.
    expect(screen.getByText('R$ 3,00')).toBeInTheDocument()

    const categoria = screen.getByLabelText('Categoria')
    expect([...categoria.querySelectorAll('option')].map((opcao) => opcao.textContent))
      .toEqual(['Todas as categorias', 'Bebidas', 'Frios', 'Lanches', 'Sem categoria'])
    fireEvent.change(categoria, { target: { value: 'categoria:Frios' } })
    expect(await nomes()).toEqual(['Muçarela'])
    fireEvent.change(categoria, { target: { value: 'sem' } })
    expect(await nomes()).toEqual(['Água'])
    // A busca por nome vale junto com a categoria.
    fireEvent.change(categoria, { target: { value: 'todas' } })
    fireEvent.change(screen.getByLabelText('Buscar por nome'), { target: { value: 'ca' } })
    expect(await nomes()).toEqual(['Cappuccino', 'Muçarela'])
  })

  it('ADMIN vê os inativos no fim e reativa; a recusa aparece no item, com o nome de quem tem o código', async () => {
    const paoFrances = { ...produto, id: 'item-9', nome: 'Pão francês', codigo: '789', unidade: 'kg' }
    let reativado = false
    vi.spyOn(cadastro, 'produtos').mockImplementation(async () => reativado ? [produto, paoFrances] : [produto])
    vi.spyOn(cadastro, 'produtosInativos').mockImplementation(async () => reativado ? [] : [paoFrances])
    const reativar = vi.spyOn(cadastro, 'reativarProduto')
      .mockRejectedValueOnce(new ErroDaApi(409, 'Conflict', 'O código 789 já é de Pão de queijo, que está '
        + 'ativo. Para reativar Pão francês, mude o código de Pão de queijo ou inative-o antes.', {}))
      .mockImplementation(async () => { reativado = true })
    mostrarProdutos('ADMIN')

    const inativos = (await screen.findByRole('heading', { name: 'Inativos' })).nextElementSibling as HTMLElement
    expect(within(inativos).getByText('Pão francês')).toBeInTheDocument()
    expect(screen.getByText('Itens da sua Conta. Os inativos ficam no fim da página.')).toBeInTheDocument()
    fireEvent.click(within(inativos).getByRole('button', { name: 'Reativar' }))
    expect(await within(inativos).findByRole('alert')).toHaveTextContent('O código 789 já é de Pão de queijo')

    fireEvent.click(within(inativos).getByRole('button', { name: 'Reativar' }))
    expect(await screen.findByRole('status')).toHaveTextContent('Pão francês reativado.')
    expect(reativar).toHaveBeenCalledWith('item-9')
    expect(screen.queryByRole('heading', { name: 'Inativos' })).not.toBeInTheDocument()
    expect(screen.getAllByRole('button', { name: 'Editar' })).toHaveLength(2)
  })

  it('OPERADOR não vê nem pede os inativos', async () => {
    vi.spyOn(cadastro, 'produtos').mockResolvedValue([produto])
    const inativos = vi.spyOn(cadastro, 'produtosInativos')
    mostrarProdutos('OPERADOR')

    expect(await screen.findByText('Itens ativos da sua Conta.')).toBeInTheDocument()
    expect(inativos).not.toHaveBeenCalled()
    expect(screen.queryByRole('heading', { name: 'Inativos' })).not.toBeInTheDocument()
  })

  it('a confirmação de inativar produto diz que ele pode ser reativado', async () => {
    vi.spyOn(cadastro, 'produtos').mockResolvedValue([produto])
    const inativar = vi.spyOn(cadastro, 'inativarProduto').mockResolvedValue(undefined)
    mostrarProdutos('ADMIN')

    fireEvent.click(await screen.findByRole('button', { name: 'Inativar' }))
    const dialogo = await confirmacaoAberta()
    expect(dialogo).toHaveTextContent('O item sai do balcão e da lista de ativos e pode ser reativado depois.')
    await responderConfirmacao('Inativar')
    await waitFor(() => expect(inativar).toHaveBeenCalledWith('item-1'))
  })

  it('na lista de clientes só Editar fica à vista; inativar e remover os dados ficam na edição', async () => {
    gravarToken(tokenComExpiracao(new Date(Date.now() + 60 * 60 * 1000)))
    gravarIdentidade({ usuarioId: 'u-1', nome: 'Ana', perfil: 'ADMIN', contaId: 'c-1',
      nomeNegocio: 'Cafeteria', estoqueHabilitado: false })
    vi.spyOn(cadastro, 'clientes').mockResolvedValue([{ id: 'cliente-1', nome: 'Lia', contato: null }])
    vi.spyOn(cadastro, 'clientesInativos').mockResolvedValue([])
    const inativar = vi.spyOn(cadastro, 'inativarCliente')
      .mockRejectedValueOnce(new ErroDaApi(409, 'Conflict', 'Recusa de teste.', {}))
      .mockResolvedValue(undefined)
    render(<TelaDeClientes />)

    const linha = (await screen.findByText('Lia')).closest('li')!
    expect(within(linha).getAllByRole('button').map((botao) => botao.textContent)).toEqual(['Editar'])
    fireEvent.click(within(linha).getByRole('button', { name: 'Editar' }))
    const formulario = screen.getByRole('heading', { name: 'Editar cliente' }).closest('form')!
    expect(within(formulario).getByRole('button', { name: 'Remover dados' })).toBeInTheDocument()

    fireEvent.click(within(formulario).getByRole('button', { name: 'Inativar' }))
    await responderConfirmacao('Inativar')
    // A recusa aparece no formulário, perto do botão tocado.
    expect(await within(formulario).findByRole('alert')).toHaveTextContent('Recusa de teste.')
    fireEvent.click(within(formulario).getByRole('button', { name: 'Inativar' }))
    await responderConfirmacao('Inativar')
    await waitFor(() => expect(inativar).toHaveBeenCalledTimes(2))
    expect(await screen.findByText('Lia inativado.')).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Editar cliente' })).not.toBeInTheDocument()
  })
})
