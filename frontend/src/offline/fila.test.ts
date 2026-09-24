import 'fake-indexeddb/auto'
import { deleteDB, openDB } from 'idb'
import { beforeEach, describe, expect, it } from 'vitest'
import { gravarIdentidade, gravarToken } from '../sessao/armazenamento'
import type { Identidade } from '../sessao/Identidade'
import { tokenComExpiracao } from '../sessao/tokenDeTeste'
import {
  enfileirarGesto, listarGestos, mudarEstadoDoGesto, recuperarEnviosInterrompidos,
} from './fila'

const ana: Identidade = {
  contaId: 'conta-a', usuarioId: 'ana', nome: 'Ana', nomeNegocio: 'Café A',
  perfil: 'ADMIN', estoqueHabilitado: false,
}

function entrar(identidade: Identidade) {
  gravarToken(tokenComExpiracao(new Date(Date.now() + 60 * 60 * 1000)))
  gravarIdentidade(identidade)
}

const produto = {
  tipo: 'produto.criar', registroId: '00000000-0000-4000-8000-000000000001',
  payload: { nome: 'Café', preco: 7 },
}

beforeEach(async () => {
  await deleteDB('caixa-simples-offline')
})

describe('fila local de gestos', () => {
  it('atualiza o banco local existente sem perder gestos ao criar os retratos', async () => {
    const antigo = await openDB('caixa-simples-offline', 1, {
      upgrade(banco) {
        const gestos = banco.createObjectStore('gestos', { keyPath: 'chave' })
        gestos.createIndex('porDono', 'dono')
      },
    })
    const operacaoId = '00000000-0000-4000-8000-000000000009'
    await antigo.put('gestos', {
      chave: `conta-a\u0000ana\u0000${operacaoId}`, dono: 'conta-a\u0000ana', operacaoId,
      registroId: produto.registroId, tipo: produto.tipo, payload: produto.payload,
      dependeDe: [], estado: 'queued', criadoEm: new Date().toISOString(),
    })
    antigo.close()

    entrar(ana)
    expect(await listarGestos()).toEqual([expect.objectContaining({ operacaoId, estado: 'queued' })])
  })

  it('confirma cada gesto no IndexedDB e o recupera após novo login na mesma Conta', async () => {
    entrar(ana)
    const payload = { nome: 'Café', preco: 7 }
    const criado = await enfileirarGesto({ ...produto, payload })
    payload.nome = 'alterado depois'

    expect(criado.operacaoId).toMatch(/^[0-9a-f-]{36}$/)
    expect(criado.estado).toBe('queued')
    entrar(ana)
    expect(await listarGestos()).toEqual([expect.objectContaining({
      operacaoId: criado.operacaoId, payload: { nome: 'Café', preco: 7 },
    })])
  })

  it('conserva dependências e recusa referência a gesto de outra Conta', async () => {
    entrar(ana)
    const primeiro = await enfileirarGesto(produto)
    const segundo = await enfileirarGesto({
      tipo: 'venda.iniciar', registroId: '00000000-0000-4000-8000-000000000002',
      payload: {}, dependeDe: [primeiro.operacaoId],
    })
    expect(segundo.dependeDe).toEqual([primeiro.operacaoId])

    entrar({ ...ana, contaId: 'conta-b' })
    await expect(enfileirarGesto({
      tipo: 'venda.iniciar', registroId: '00000000-0000-4000-8000-000000000003',
      payload: {}, dependeDe: [primeiro.operacaoId],
    })).rejects.toThrow('Dependência fora da fila')
    expect(await listarGestos()).toEqual([])
  })

  it('isola a fila por Conta e usuário mesmo quando o token muda', async () => {
    entrar(ana)
    const gesto = await enfileirarGesto(produto)
    entrar({ ...ana, contaId: 'conta-b' })
    expect(await listarGestos()).toEqual([])
    await expect(mudarEstadoDoGesto(gesto.operacaoId, 'queued', 'syncing'))
      .rejects.toThrow('Gesto não encontrado')

    entrar({ ...ana, usuarioId: 'outro' })
    expect(await listarGestos()).toEqual([])
    entrar(ana)
    expect((await listarGestos()).map((item) => item.operacaoId)).toEqual([gesto.operacaoId])
  })

  it('preserva o resultado e recupera envio interrompido sem trocar o UUID', async () => {
    entrar(ana)
    const primeiro = await enfileirarGesto(produto)
    await mudarEstadoDoGesto(primeiro.operacaoId, 'queued', 'syncing')
    entrar(ana)
    await recuperarEnviosInterrompidos()
    expect((await listarGestos())[0]).toMatchObject({
      operacaoId: primeiro.operacaoId, estado: 'queued',
    })
    await mudarEstadoDoGesto(primeiro.operacaoId, 'queued', 'syncing')
    await mudarEstadoDoGesto(primeiro.operacaoId, 'syncing', 'failed')
    await mudarEstadoDoGesto(primeiro.operacaoId, 'failed', 'queued')
    await mudarEstadoDoGesto(primeiro.operacaoId, 'queued', 'syncing')
    await mudarEstadoDoGesto(primeiro.operacaoId, 'syncing', 'needs_review', {
      aplicada: true, detalhe: 'conferir estoque',
    })
    expect((await listarGestos())[0]).toMatchObject({
      operacaoId: primeiro.operacaoId, estado: 'needs_review',
      resultado: { aplicada: true, detalhe: 'conferir estoque' },
    })
    await expect(mudarEstadoDoGesto(primeiro.operacaoId, 'needs_review', 'sent'))
      .rejects.toThrow('Transição de estado inválida')

    const segundo = await enfileirarGesto({ ...produto,
      registroId: '00000000-0000-4000-8000-000000000004' })
    await mudarEstadoDoGesto(segundo.operacaoId, 'queued', 'syncing')
    await mudarEstadoDoGesto(segundo.operacaoId, 'syncing', 'sent', { aplicada: true })
    entrar(ana)
    expect((await listarGestos()).find((item) => item.operacaoId === segundo.operacaoId))
      .toMatchObject({ estado: 'sent', resultado: { aplicada: true } })
  })

  it('recusa gesto novo sem sessão válida e conserva o que já estava gravado', async () => {
    entrar(ana)
    const primeiro = await enfileirarGesto(produto)
    gravarToken(tokenComExpiracao(new Date(Date.now() - 1000)))
    gravarIdentidade(ana)
    await expect(enfileirarGesto(produto)).rejects.toThrow('Entre novamente')
    entrar(ana)
    expect((await listarGestos()).map((item) => item.operacaoId)).toEqual([primeiro.operacaoId])
  })
})
