import 'fake-indexeddb/auto'
import { deleteDB } from 'idb'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { Produto } from '../api/cadastro'
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
const espera = { timeout: 5000 }

beforeEach(async () => {
  await deleteDB('caixa-simples-offline')
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
    identidade, entrar: vi.fn(), sair: vi.fn(), atualizarIdentidade: vi.fn(),
  }}><TelaDeVenda /></SessaoContext.Provider></MemoryRouter>)
}

describe('PDV sem rede', () => {
  it('conclui a Venda no dispositivo com troco e comprovante pendente e a mostra de novo ao reabrir', async () => {
    await criarCaixaLocal().abrir(20)
    const tela = mostrar()
    expect(await screen.findByText(/lista mostra as Vendas registradas neste dispositivo/)).toBeInTheDocument()

    const busca = await screen.findByLabelText('Produto por nome ou código')
    let toques = 0
    const contar = () => { toques++ }
    document.addEventListener('click', contar)
    fireEvent.change(busca, { target: { value: '2*café' } })
    fireEvent.click(await screen.findByRole('button', { name: /Café expresso · R\$\s*6,25/ }, espera))
    expect(await screen.findByText(/2 × Café expresso/, undefined, espera)).toBeInTheDocument()
    expect(screen.getByText('Registrada neste dispositivo. Aguardando sincronização com o servidor.'))
      .toBeInTheDocument()
    expect(within(screen.getByLabelText('Forma')).queryByRole('option', { name: 'Pix integrado' }))
      .not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Cancelar venda' })).not.toBeInTheDocument()
    expect(screen.getByText(/cancelamento fica disponível depois da sincronização/)).toBeInTheDocument()

    fireEvent.click(screen.getByLabelText('Valor recebido em dinheiro'))
    fireEvent.change(screen.getByLabelText('Valor recebido em dinheiro'), { target: { value: '20.00' } })
    fireEvent.click(screen.getByRole('button', { name: 'Receber e concluir' }))

    const comprovante = await screen.findByLabelText('Comprovante não fiscal', undefined, espera)
    expect(comprovante).toHaveTextContent('Pendente de sincronização com o servidor')
    expect(comprovante).toHaveTextContent('Total: R$ 12,50')
    expect(comprovante).toHaveTextContent('Troco: R$ 7,50')
    expect(screen.getAllByRole('status').map((aviso) => aviso.textContent?.replace(/\s+/g, ' ')))
      .toContain('Troco: R$ 7,50')
    expect(toques).toBeLessThanOrEqual(6)
    document.removeEventListener('click', contar)
    await waitFor(async () => expect((await listarGestos()).map((gesto) => gesto.tipo).sort()).toEqual([
      'caixa.abrir', 'venda.adicionarItem', 'venda.concluir', 'venda.iniciar', 'venda.registrarPagamento',
    ]))

    tela.unmount()
    mostrar()
    fireEvent.click(await screen.findByRole('button', { name: /CONCLUIDA.*pendente de sincronização/ }, espera))
    expect(await screen.findByLabelText('Comprovante não fiscal', undefined, espera))
      .toHaveTextContent('Troco: R$ 7,50')
    expect(screen.getByText('Registrada neste dispositivo. Aguardando sincronização com o servidor.'))
      .toBeInTheDocument()
    expect(buscar).not.toHaveBeenCalled()
  }, 20000)
})
