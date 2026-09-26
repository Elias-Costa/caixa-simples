import 'fake-indexeddb/auto'
import { deleteDB } from 'idb'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { gravarIdentidade, gravarToken, limparSessao } from '../sessao/armazenamento'
import type { Identidade } from '../sessao/Identidade'
import { tokenComExpiracao } from '../sessao/tokenDeTeste'
import { SessaoContext } from '../sessao/contexto'
import { SessaoProvider } from '../sessao/SessaoProvider'
import { useSessao } from '../sessao/useSessao'
import { cadastro } from '../api/cadastro'
import { guardarRetrato, lerRetrato, listarGestos } from '../offline/fila'
import { TelaDeClientes } from './TelaDeClientes'
import { TelaDeProdutos } from './TelaDeProdutos'

const identidade: Identidade = {
  contaId: 'conta-a', usuarioId: 'ana', nome: 'Ana', nomeNegocio: 'Cafeteria Aurora',
  perfil: 'ADMIN', estoqueHabilitado: false,
}

beforeEach(async () => {
  await deleteDB('caixa-simples-offline')
  gravarToken(tokenComExpiracao(new Date(Date.now() + 60 * 60 * 1000)))
  gravarIdentidade(identidade)
  vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(false)
  await guardarRetrato('clientesAtivos', [], 0)
  await guardarRetrato('clientesInativos', [], 0)
})
afterEach(() => vi.restoreAllMocks())

describe('telas de cadastro sem rede', () => {
  it('prepara a cópia local do catálogo ao entrar online após o primeiro acesso ADMIN', async () => {
    limparSessao()
    vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(true)
    const token = tokenComExpiracao(new Date(Date.now() + 60 * 60 * 1000))
    const buscar = vi.fn(async (caminho: string) => {
      if (caminho === '/api/auth/login') return new Response(JSON.stringify({ token }), { status: 200 })
      if (caminho === '/api/auth/eu') return new Response(JSON.stringify(identidade), { status: 200 })
      if (caminho === '/api/produtos') return new Response(JSON.stringify([{
        id: '00000000-0000-4000-8000-000000000001', versao: 0, tipo: 'PRODUTO',
        nome: 'Café expresso', preco: 0, codigo: null, categoria: 'Bebidas',
        unidade: 'un', atributos: {},
      }]), { status: 200 })
      if (caminho === '/api/clientes' || caminho === '/api/clientes/inativos') {
        return new Response('[]', { status: 200 })
      }
      throw new Error(`Rota inesperada: ${caminho}`)
    })
    vi.stubGlobal('fetch', buscar)
    function Entrada() {
      const { entrar } = useSessao()
      return <button onClick={() => void entrar('a@teste.local', 'senha')}>Entrar</button>
    }
    render(<SessaoProvider><Entrada /></SessaoProvider>)
    fireEvent.click(screen.getByRole('button', { name: 'Entrar' }))
    await waitFor(async () => expect((await lerRetrato<unknown[]>('produtos'))?.dados).toHaveLength(1))
    expect(await listarGestos()).toEqual([])
    vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(false)
    expect((await cadastro.produtos()).map((item) => item.nome)).toEqual(['Café expresso'])
  })

  it('mostra o catálogo preparado, guarda preço editado e o recupera na reabertura', async () => {
    await guardarRetrato('produtos', [{
      id: '00000000-0000-4000-8000-000000000001', versao: 2, tipo: 'PRODUTO',
      nome: 'Café expresso', preco: 0, codigo: null, categoria: 'Bebidas',
      unidade: 'un', atributos: {},
    }], 0)
    const sessao = { identidade, entrar: vi.fn(), sair: vi.fn(), atualizarIdentidade: vi.fn() }
    const tela = render(<SessaoContext.Provider value={sessao}><TelaDeProdutos /></SessaoContext.Provider>)
    expect(await screen.findByText('Café expresso')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Editar' }))
    fireEvent.change(screen.getByLabelText('Preço (R$)'), { target: { value: '7,50' } })
    fireEvent.click(screen.getByRole('button', { name: 'Salvar' }))
    await waitFor(() => expect(screen.getByText('R$ 7,50')).toBeInTheDocument())
    expect((await listarGestos())[0]).toMatchObject({ tipo: 'produto.editar', versaoBase: 2 })

    tela.unmount()
    render(<SessaoContext.Provider value={sessao}><TelaDeProdutos /></SessaoContext.Provider>)
    expect(await screen.findByText('R$ 7,50')).toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent('aguardando sincronização')
  })

  it('cadastra Cliente novo sem rede e conserva o UUID na fila após reabrir', async () => {
    const tela = render(<TelaDeClientes />)
    await screen.findByText('Nenhum cliente ativo.')
    fireEvent.click(screen.getByRole('button', { name: 'Novo cliente' }))
    fireEvent.change(screen.getByLabelText('Nome'), { target: { value: 'Maria' } })
    fireEvent.click(screen.getByRole('button', { name: 'Salvar' }))
    await screen.findByText('Maria')
    const [gesto] = await listarGestos()
    expect(gesto).toMatchObject({ tipo: 'cliente.criar', estado: 'queued' })

    tela.unmount()
    render(<TelaDeClientes />)
    expect(await screen.findByText('Maria')).toBeInTheDocument()
    expect((await listarGestos())[0].registroId).toBe(gesto.registroId)
  })
})
