import 'fake-indexeddb/auto'
import { deleteDB } from 'idb'
import { render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { caixa, type SessaoCaixa } from '../api/caixa'
import { gravarIdentidade, gravarToken } from '../sessao/armazenamento'
import { tokenComExpiracao } from '../sessao/tokenDeTeste'
import { Inicio } from './Inicio'

afterEach(() => vi.restoreAllMocks())
beforeEach(async () => {
  await deleteDB('fechadia-offline')
  gravarToken(tokenComExpiracao(new Date(Date.now() + 60 * 60 * 1000)))
  gravarIdentidade({ usuarioId: 'u-1', nome: 'Ana', perfil: 'OPERADOR', contaId: 'c-1',
    nomeNegocio: 'Loja da Esquina', estoqueHabilitado: false })
})

const aberta: SessaoCaixa = {
  id: 's-1', usuarioId: 'u-1', valorAbertura: 20,
  valorFechamentoEsperado: 20, valorFechamentoContado: null, diferenca: null,
  abertaEm: '2026-09-22T12:00:00Z', fechadaEm: null, status: 'ABERTA',
}

function mostrar() {
  render(<MemoryRouter initialEntries={['/']}>
    <Routes>
      <Route path="/" element={<Inicio />} />
      <Route path="/vender" element={<p>Tela de venda</p>} />
      <Route path="/caixa" element={<p>Tela do caixa</p>} />
    </Routes>
  </MemoryRouter>)
}

describe('entrada', () => {
  it('com o caixa aberto, vai direto ao PDV', async () => {
    vi.spyOn(caixa, 'abertaDoOperadorAtual').mockResolvedValue(aberta)
    mostrar()
    expect(await screen.findByText('Tela de venda')).toBeInTheDocument()
  })

  it('sem caixa aberto, vai ao caixa para abri-lo', async () => {
    vi.spyOn(caixa, 'abertaDoOperadorAtual').mockResolvedValue(undefined)
    mostrar()
    expect(await screen.findByText('Tela do caixa')).toBeInTheDocument()
  })
})
