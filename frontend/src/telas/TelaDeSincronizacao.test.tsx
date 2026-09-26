import 'fake-indexeddb/auto'
import { deleteDB } from 'idb'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { contas } from '../api/contas'
import { sincronizacao, type RevisaoDaConta } from '../api/sincronizacao'
import { enfileirarGesto, listarGestos, mudarEstadoDoGesto } from '../offline/fila'
import { gravarIdentidade, gravarToken } from '../sessao/armazenamento'
import { SessaoContext } from '../sessao/contexto'
import type { Identidade, Perfil } from '../sessao/Identidade'
import { tokenComExpiracao } from '../sessao/tokenDeTeste'
import { SincronizacaoContext, type Sincronizacao } from '../shell/sincronizacao'
import { TelaDeSincronizacao } from './TelaDeSincronizacao'

afterEach(() => vi.restoreAllMocks())
beforeEach(async () => { await deleteDB('caixa-simples-offline') })

function identidade(perfil: Perfil): Identidade {
  return { usuarioId: 'u-1', nome: 'Ana', perfil, contaId: 'c-1', nomeNegocio: 'Loja da Esquina',
    estoqueHabilitado: false }
}

function entrar(perfil: Perfil) {
  gravarToken(tokenComExpiracao(new Date(Date.now() + 60 * 60 * 1000)))
  gravarIdentidade(identidade(perfil))
}

function mostrar(perfil: Perfil) {
  const estado: Sincronizacao = { enviando: false, porEnviar: 0, comFalha: 0, emRevisao: 0, rodada: 0,
    enviarAgora: vi.fn(), atualizar: vi.fn() }
  render(
    <SessaoContext.Provider value={{ identidade: identidade(perfil), entrar: vi.fn(), sair: vi.fn(),
      atualizarIdentidade: vi.fn() }}>
      <SincronizacaoContext.Provider value={estado}><TelaDeSincronizacao /></SincronizacaoContext.Provider>
    </SessaoContext.Provider>,
  )
  return estado
}

/** Um gesto na fila, um com falha e uma Venda concluída que o servidor recusou. */
async function filaComOsTresCasos() {
  await enfileirarGesto({ tipo: 'cliente.criar', registroId: crypto.randomUUID(),
    payload: { nome: 'Maria', contato: null } })
  const falhou = await enfileirarGesto({ tipo: 'caixa.sangrar', registroId: crypto.randomUUID(),
    payload: { valor: 5, motivo: 'Depósito', criadoEm: new Date().toISOString() } })
  await mudarEstadoDoGesto(falhou.operacaoId, 'queued', 'syncing')
  await mudarEstadoDoGesto(falhou.operacaoId, 'syncing', 'failed')
  const recusada = await enfileirarGesto({ tipo: 'venda.concluir', registroId: crypto.randomUUID(),
    payload: { concluidoEm: new Date().toISOString() } })
  await mudarEstadoDoGesto(recusada.operacaoId, 'queued', 'syncing')
  await mudarEstadoDoGesto(recusada.operacaoId, 'syncing', 'needs_review', {
    aplicada: false, detalhe: 'a sessao de caixa esta fechada',
  })
  return recusada
}

describe('sincronização na tela', () => {
  it('mostra o que falta enviar e a recusa, e a conferência tira a revisão do aviso', async () => {
    entrar('OPERADOR')
    const recusada = await filaComOsTresCasos()
    const revisoes = vi.spyOn(sincronizacao, 'revisoes')
    const estado = mostrar('OPERADOR')

    expect(await screen.findByText('Cliente cadastrado')).toBeInTheDocument()
    expect(screen.getByText('Maria')).toBeInTheDocument()
    expect(screen.getByText(/Na fila/)).toBeInTheDocument()
    expect(screen.getByText('R$ 5,00 · Depósito')).toBeInTheDocument()
    expect(screen.getByText(/Com falha, será repetido/)).toBeInTheDocument()
    expect(screen.getByText(/Não aplicado: a sessao de caixa esta fechada/)).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Conferir' }))
    await waitFor(() => expect(estado.atualizar).toHaveBeenCalled())
    expect((await listarGestos()).find((gesto) => gesto.operacaoId === recusada.operacaoId))
      .toMatchObject({ estado: 'needs_review', conferidoEm: expect.any(String) })
    expect(await screen.findByText('Nenhuma revisão por conferir neste aparelho.')).toBeInTheDocument()
    expect(screen.getByText('Conferidos neste aparelho (1)')).toBeInTheDocument()

    expect(screen.queryByText('Revisões da Conta')).not.toBeInTheDocument()
    expect(revisoes).not.toHaveBeenCalled()
  })

  it('mostra ao administrador as revisões da Conta com o autor, e confere no servidor', async () => {
    entrar('ADMIN')
    const deOutroAparelho: RevisaoDaConta = {
      operacaoId: crypto.randomUUID(), usuarioId: 'u-2', tipo: 'venda.adicionarItem',
      registroId: crypto.randomUUID(), payload: { nome: 'Bolo', quantidade: 2, precoUnitario: 8 },
      criadaEm: '2026-09-26T13:00:00Z', recebidaEm: '2026-09-26T14:00:00Z',
      resultado: 'APLICADA_COM_REVISAO', detalhe: 'o estoque ficou negativo',
    }
    const revisoes = vi.spyOn(sincronizacao, 'revisoes')
      .mockResolvedValueOnce({ pendentes: [deOutroAparelho], conferidas: [] })
      .mockResolvedValue({ pendentes: [], conferidas: [{ ...deOutroAparelho,
        conferidaEm: '2026-09-26T15:00:00Z', conferidaPor: 'u-1' }] })
    vi.spyOn(contas, 'usuarios').mockResolvedValue([
      { id: 'u-1', nome: 'Ana', perfil: 'ADMIN', ativo: true },
      { id: 'u-2', nome: 'Bruno', perfil: 'OPERADOR', ativo: true },
    ])
    const conferir = vi.spyOn(sincronizacao, 'conferir').mockResolvedValue(undefined)
    const estado = mostrar('ADMIN')

    const secao = (await screen.findByText('Revisões da Conta')).closest('div') as HTMLElement
    expect(await within(secao).findByText(/, por Bruno/)).toBeInTheDocument()
    expect(within(secao).getByText('2 × Bolo · R$ 8,00')).toBeInTheDocument()
    expect(within(secao).getByText(/Aplicado, com revisão: o estoque ficou negativo/)).toBeInTheDocument()

    fireEvent.click(within(secao).getByRole('button', { name: 'Conferir' }))
    await waitFor(() => expect(conferir).toHaveBeenCalledWith(deOutroAparelho.operacaoId))
    expect(await within(secao).findByText(/Conferido por Ana/)).toBeInTheDocument()
    expect(within(secao).getByText('Nenhuma revisão da Conta por conferir.')).toBeInTheDocument()
    expect(revisoes).toHaveBeenCalledWith(expect.stringMatching(/^\d{4}-\d{2}-\d{2}$/))
    expect(estado.atualizar).toHaveBeenCalled()
  })
})
