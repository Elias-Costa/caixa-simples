import 'fake-indexeddb/auto'
import { deleteDB } from 'idb'
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cadastro, type Produto } from '../api/cadastro'
import { criarCaixaLocal } from '../offline/caixaLocal'
import { guardarRetrato, listarGestos } from '../offline/fila'
import { gravarIdentidade, gravarToken } from '../sessao/armazenamento'
import type { Identidade } from '../sessao/Identidade'
import { SessaoContext } from '../sessao/contexto'
import { tokenComExpiracao } from '../sessao/tokenDeTeste'
import { TelaDeVenda } from './TelaDeVenda'

const identidade: Identidade = {
  contaId: 'conta-a', usuarioId: 'ana', nome: 'Ana', nomeNegocio: 'Cafeteria Aurora',
  perfil: 'OPERADOR', estoqueHabilitado: false,
}
const cafe: Produto = {
  id: '00000000-0000-4000-8000-000000000001', versao: 3, tipo: 'PRODUTO', nome: 'Café expresso',
  preco: 6.25, codigo: 'CA-1', categoria: 'Bebidas', unidade: 'un', atributos: {},
}
const buscar = vi.fn(async () => { throw new TypeError('sem rede') })
// Cada gesto abre o IndexedDB simulado mais de uma vez; com a suíte inteira em paralelo, a espera
// padrão de um segundo não basta para receber, concluir e montar o comprovante.
const espera = { timeout: 10000 }

beforeEach(async () => {
  await deleteDB('ecrx-caixa-offline')
  gravarToken(tokenComExpiracao(new Date(Date.now() + 60 * 60 * 1000)))
  gravarIdentidade(identidade)
  vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(false)
  vi.stubGlobal('fetch', buscar)
  await guardarRetrato('produtos', [cafe], 0)
  await guardarRetrato('clientesAtivos', [], 0)
  await guardarRetrato('clientesInativos', [], 0)
})
afterEach(() => {
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
  buscar.mockClear()
})

function mostrar() {
  return render(<MemoryRouter><SessaoContext.Provider value={{
    identidade, entrar: vi.fn(), sair: vi.fn(), atualizarIdentidade: vi.fn(), relerIdentidade: vi.fn(),
  }}><TelaDeVenda /></SessaoContext.Provider></MemoryRouter>)
}

describe('PDV sem rede', () => {
  it('conclui a Venda no dispositivo com troco e comprovante pendente e a mostra de novo ao reabrir', async () => {
    await criarCaixaLocal().abrir(20)
    const tela = mostrar()
    // Sem venda guardada aqui, a lista não afirma que o caixa não vendeu.
    expect(await screen.findByText('Sem conexão. As vendas deste caixa aparecem quando a rede voltar.'))
      .toBeInTheDocument()

    const busca = await screen.findByLabelText('Produto por nome ou código')
    let toques = 0
    const contar = () => { toques++ }
    document.addEventListener('click', contar)
    fireEvent.change(busca, { target: { value: '2*café' } })
    fireEvent.click(await screen.findByRole('button', { name: /Café expresso · R\$\s*6,25/ }, espera))
    expect(await screen.findByText(/2 un × Café expresso/, undefined, espera)).toBeInTheDocument()
    expect(screen.getByText('Registrada neste aparelho. Aguardando sincronização com o servidor.'))
      .toBeInTheDocument()
    expect(within(screen.getByLabelText('Forma')).queryByRole('option', { name: 'Pix integrado' }))
      .not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Cancelar venda' })).not.toBeInTheDocument()
    expect(screen.getByText(/cancelamento fica disponível depois da sincronização/)).toBeInTheDocument()

    fireEvent.click(screen.getByLabelText('Valor recebido em dinheiro'))
    fireEvent.change(screen.getByLabelText('Valor recebido em dinheiro'), { target: { value: '20.00' } })
    await waitFor(() => expect(screen.getByRole('button', { name: 'Receber e concluir' })).toBeEnabled(), espera)
    fireEvent.click(screen.getByRole('button', { name: 'Receber e concluir' }))

    await waitFor(async () => {
      expect((await listarGestos()).map((gesto) => gesto.tipo).sort()).toEqual([
        'caixa.abrir', 'venda.adicionarItem', 'venda.concluir', 'venda.iniciar',
        'venda.registrarPagamento',
      ])
    }, espera)

    const comprovante = await screen.findByLabelText('Comprovante não fiscal', undefined, espera)
    expect(comprovante).toHaveTextContent('Pendente de sincronização com o servidor')
    expect(comprovante).toHaveTextContent('Total: R$ 12,50')
    expect(comprovante).toHaveTextContent('Troco: R$ 7,50')
    expect(screen.getAllByRole('status').map((aviso) => aviso.textContent?.replace(/\s+/g, ' ')))
      .toContain('Troco: R$ 7,50')
    expect(toques).toBeLessThanOrEqual(6)
    document.removeEventListener('click', contar)
    tela.unmount()
    mostrar()
    fireEvent.click(await screen.findByRole('button', { name: /Concluída.*pendente de sincronização/ }, espera))
    expect(screen.getByText('Sem conexão, a lista mostra as vendas registradas neste aparelho.')).toBeInTheDocument()
    expect(await screen.findByLabelText('Comprovante não fiscal', undefined, espera))
      .toHaveTextContent('Troco: R$ 7,50')
    expect(screen.getByText('Registrada neste aparelho. Aguardando sincronização com o servidor.'))
      .toBeInTheDocument()
    expect(buscar).not.toHaveBeenCalled()
  }, 20000)

  it('sem rede, o Pix manual mostra o QR da chave guardada e conclui a Venda no dispositivo', async () => {
    const comChave: Identidade = { ...identidade, chavePix: '+5571991234567', cidadePix: 'Salvador' }
    gravarIdentidade(comChave)
    await criarCaixaLocal().abrir(0)
    render(<MemoryRouter><SessaoContext.Provider value={{
      identidade: comChave, entrar: vi.fn(), sair: vi.fn(), atualizarIdentidade: vi.fn(), relerIdentidade: vi.fn(),
    }}><TelaDeVenda /></SessaoContext.Provider></MemoryRouter>)

    const busca = await screen.findByLabelText('Produto por nome ou código')
    fireEvent.change(busca, { target: { value: 'café' } })
    fireEvent.click(await screen.findByRole('button', { name: /Café expresso · R\$\s*6,25/ }, espera))
    expect(await screen.findByText(/1 un × Café expresso/, undefined, espera)).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('Forma'), { target: { value: 'PIX_MANUAL' } })
    expect(screen.getByRole('img', { name: 'QR Pix do valor' })).toBeInTheDocument()
    expect(screen.getByText(/br\.gov\.bcb\.pix/)).toHaveTextContent('0114+5571991234567')
    await waitFor(() => expect(screen.getByRole('button', { name: 'Receber e concluir' })).toBeEnabled(), espera)
    fireEvent.click(screen.getByRole('button', { name: 'Receber e concluir' }))

    await waitFor(async () => {
      const pagamento = (await listarGestos()).find((gesto) => gesto.tipo === 'venda.registrarPagamento')
      expect(pagamento?.payload).toEqual(expect.objectContaining({ forma: 'PIX', valor: 6.25, valorRecebido: null }))
    }, espera)
    expect(await screen.findByLabelText('Comprovante não fiscal', undefined, espera))
      .toHaveTextContent('Pix: R$ 6,25')
    expect(buscar).not.toHaveBeenCalled()
  }, 20000)

  it('sem rede, o termo que acha muitos na cópia do cadastro desenha os 20 primeiros e diz quantos há', async () => {
    await guardarRetrato('produtos', Array.from({ length: 30 }, (_, i) => ({ ...cafe,
      id: `00000000-0000-4000-8000-${String(100 + i).padStart(12, '0')}`,
      nome: `Café da casa ${String(30 - i).padStart(2, '0')}`, codigo: null })), 0)
    await criarCaixaLocal().abrir(20)
    mostrar()

    fireEvent.change(await screen.findByLabelText('Produto por nome ou código'), { target: { value: 'cafe da' } })
    expect(await screen.findByText('Mostrando 20 de 30 produtos. Continue digitando o nome para achar o item.',
      undefined, espera)).toHaveAttribute('role', 'status')
    const botoes = screen.getAllByRole('button', { name: /Café da casa/ })
    expect(botoes).toHaveLength(20)
    expect(botoes[0]).toHaveTextContent('Café da casa 01')
    expect(buscar).not.toHaveBeenCalled()
  })
})

describe('PDV com o cadastro que chega depois da entrada', () => {
  const CATALOGO_SEM_REDE = 'O catálogo ainda não foi copiado para este aparelho. Ele é copiado quando a rede voltar.'

  it('a busca que acha no catálogo copiado tira a frase de que ele ainda não estava no aparelho', async () => {
    await deleteDB('ecrx-caixa-offline')
    await guardarRetrato('clientesAtivos', [], 0)
    await guardarRetrato('clientesInativos', [], 0)
    await criarCaixaLocal().abrir(20)
    mostrar()

    const busca = await screen.findByLabelText('Produto por nome ou código')
    fireEvent.change(busca, { target: { value: 'café' } })
    expect(await screen.findByText(CATALOGO_SEM_REDE, undefined, espera)).toBeInTheDocument()

    // A cópia chega na volta do servidor, e a busca seguinte já acha nela.
    await guardarRetrato('produtos', [cafe], 0)
    fireEvent.change(busca, { target: { value: 'expresso' } })
    expect(await screen.findByRole('button', { name: /Café expresso · R\$\s*6,25/ }, espera)).toBeInTheDocument()
    expect(screen.queryByText(CATALOGO_SEM_REDE)).not.toBeInTheDocument()
  })

  it('a busca que responde não tira o erro do lançamento que a pessoa tentou', async () => {
    const dona = { ...identidade, perfil: 'ADMIN' as const }
    gravarIdentidade(dona)
    await criarCaixaLocal().abrir(20)
    render(<MemoryRouter><SessaoContext.Provider value={{
      identidade: dona, entrar: vi.fn(), sair: vi.fn(), atualizarIdentidade: vi.fn(), relerIdentidade: vi.fn(),
    }}><TelaDeVenda /></SessaoContext.Provider></MemoryRouter>)

    fireEvent.change(await screen.findByLabelText('Desconto deste item'), { target: { value: '1,234' } })
    fireEvent.change(screen.getByLabelText('Produto por nome ou código'), { target: { value: 'café' } })
    fireEvent.click(await screen.findByRole('button', { name: /Café expresso · R\$\s*6,25/ }, espera))
    const desconto = 'Informe o desconto do item com até duas casas decimais.'
    expect(await screen.findByText(desconto)).toBeInTheDocument()

    fireEvent.change(screen.getByLabelText('Produto por nome ou código'), { target: { value: 'expresso' } })
    await waitFor(() => expect(screen.getByLabelText('Produto por nome ou código')).toHaveValue('expresso'))
    await new Promise((pronto) => setTimeout(pronto, 400))
    expect(screen.getByRole('button', { name: /Café expresso · R\$\s*6,25/ })).toBeInTheDocument()
    expect(screen.getByText(desconto)).toBeInTheDocument()
  })

  it('os clientes são lidos de novo na volta do servidor, e a frase da cópia que faltava sai', async () => {
    await deleteDB('ecrx-caixa-offline')
    await guardarRetrato('produtos', [cafe], 0)
    await criarCaixaLocal().abrir(20)
    const onLine = vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(false)
    const lerClientes = vi.spyOn(cadastro, 'clientes')
    mostrar()

    const semClientes = 'O cadastro de clientes ainda não foi copiado para este aparelho. Ele é copiado quando a rede voltar.'
    expect(await screen.findByText(semClientes, undefined, espera)).toBeInTheDocument()

    // A rede volta, e o servidor responde: nenhum caixa aberto lá, e as listas vazias, a de clientes também.
    vi.stubGlobal('fetch', vi.fn(async (caminho: string) => caminho === '/api/caixa/sessoes/aberta'
      ? new Response(null, { status: 204 })
      : new Response('[]', { status: 200, headers: { 'Content-Type': 'application/json' } })))
    onLine.mockReturnValue(true)
    act(() => { window.dispatchEvent(new Event('online')) })

    await waitFor(() => expect(lerClientes.mock.calls.length).toBeGreaterThanOrEqual(2), espera)
    await waitFor(() => expect(screen.queryByText(/ainda não foi copiado/)).not.toBeInTheDocument(), espera)
  })
})
