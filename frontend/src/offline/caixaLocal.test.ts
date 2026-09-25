import 'fake-indexeddb/auto'
import { deleteDB } from 'idb'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { caixa, type SessaoCaixa } from '../api/caixa'
import { SemConexao } from '../api/cliente'
import { hojeNoBalcao } from '../dataDoBalcao'
import { gravarIdentidade, gravarToken } from '../sessao/armazenamento'
import type { Identidade } from '../sessao/Identidade'
import { tokenComExpiracao } from '../sessao/tokenDeTeste'
import { criarCaixaLocal } from './caixaLocal'
import { listarGestos } from './fila'

const ana: Identidade = {
  contaId: 'conta-a', usuarioId: 'ana', nome: 'Ana', nomeNegocio: 'Café A',
  perfil: 'OPERADOR', estoqueHabilitado: false,
}

function entrar(identidade: Identidade) {
  gravarToken(tokenComExpiracao(new Date(Date.now() + 60 * 60 * 1000)))
  gravarIdentidade(identidade)
}

function rede(online: boolean) {
  vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(online)
}

beforeEach(async () => { await deleteDB('caixa-simples-offline') })
afterEach(() => vi.restoreAllMocks())

describe('SessaoCaixa local', () => {
  it('preserva abertura, movimentos, conferência e fechamento após recarga, em ordem', async () => {
    entrar(ana)
    rede(false)
    const remoto = {
      ...caixa, abertaDoOperadorAtual: vi.fn(), historico: vi.fn(), consultar: vi.fn(),
      abrir: vi.fn(), sangrar: vi.fn(), suprir: vi.fn(), fechar: vi.fn(),
    }
    const local = criarCaixaLocal(remoto)
    const { id } = await local.abrir(20)
    expect((await local.abertaDoOperadorAtual())?.id).toBe(id)
    await local.sangrar(id, 5, 'Retirada')
    await local.suprir(id, 3, 'Troco')

    const recarregado = criarCaixaLocal(remoto)
    expect(await recarregado.consultar(id)).toMatchObject({
      status: 'ABERTA', versao: 2, valorAbertura: 20, valorFechamentoEsperado: 18,
      pendenteSincronizacao: true, movimentos: [{ tipo: 'SANGRIA', valor: 5 },
        { tipo: 'SUPRIMENTO', valor: 3 }],
    })
    expect((await recarregado.historico(hojeNoBalcao())).map((sessao) => sessao.id)).toContain(id)
    expect(await recarregado.fechar(id, 16)).toEqual({ diferenca: 2 })
    expect(await criarCaixaLocal(remoto).consultar(id)).toMatchObject({
      status: 'FECHADA', versao: 3, valorFechamentoEsperado: 18,
      valorFechamentoContado: 16, diferenca: 2, pendenteSincronizacao: true,
    })
    expect(await recarregado.abertaDoOperadorAtual()).toBeUndefined()
    const gestos = await listarGestos()
    expect(gestos.map((gesto) => gesto.tipo).sort()).toEqual([
      'caixa.abrir', 'caixa.fechar', 'caixa.sangrar', 'caixa.suprir',
    ])
    const abertura = gestos.find((gesto) => gesto.tipo === 'caixa.abrir')!
    const sangria = gestos.find((gesto) => gesto.tipo === 'caixa.sangrar')!
    const suprimento = gestos.find((gesto) => gesto.tipo === 'caixa.suprir')!
    const fechamento = gestos.find((gesto) => gesto.tipo === 'caixa.fechar')!
    expect(sangria.dependeDe).toEqual([abertura.operacaoId])
    expect(sangria.versaoBase).toBe(0)
    expect(suprimento.dependeDe).toEqual([sangria.operacaoId])
    expect(suprimento.versaoBase).toBe(1)
    expect(fechamento.dependeDe).toEqual([abertura, sangria, suprimento].map((gesto) => gesto.operacaoId))
    expect(fechamento.versaoBase).toBe(2)
    expect(remoto.abrir).not.toHaveBeenCalled()
    expect(remoto.fechar).not.toHaveBeenCalled()
  })

  it('recusa estados inválidos antes de gravar gesto', async () => {
    entrar(ana)
    rede(false)
    const local = criarCaixaLocal()
    const { id } = await local.abrir(10)
    await expect(local.abrir(0)).rejects.toThrow('Já existe')
    await expect(local.sangrar(id, 11, 'Retirada')).rejects.toThrow('maior')
    await expect(local.suprir(id, 2, ' ')).rejects.toThrow('Motivo')
    await expect(local.fechar(id, -1)).rejects.toThrow('não negativo')
    await local.fechar(id, 9)
    await expect(local.fechar(id, 9)).rejects.toThrow('já fechada')
    await expect(local.suprir(id, 1, 'Troco')).rejects.toThrow('fechada')
    expect((await listarGestos()).map((gesto) => gesto.tipo).sort()).toEqual(['caixa.abrir', 'caixa.fechar'])
  })

  it('isola sessões e gestos entre Contas e usuários, inclusive após novo login', async () => {
    rede(false)
    entrar(ana)
    const local = criarCaixaLocal()
    const primeiro = await local.abrir(20)

    entrar({ ...ana, contaId: 'conta-b' })
    expect(await local.abertaDoOperadorAtual()).toBeUndefined()
    await expect(local.consultar(primeiro.id)).rejects.toThrow('não encontrada')
    const segundo = await local.abrir(30)
    expect((await listarGestos()).map((gesto) => gesto.registroId)).toEqual([segundo.id])

    entrar({ ...ana, usuarioId: 'outra' })
    expect(await local.abertaDoOperadorAtual()).toBeUndefined()
    entrar(ana)
    expect((await local.abertaDoOperadorAtual())?.id).toBe(primeiro.id)
    expect((await listarGestos()).map((gesto) => gesto.registroId)).toEqual([primeiro.id])
  })

  it('usa extrato guardado para continuar offline e não duplica gesto em falha incerta de escrita online', async () => {
    entrar(ana)
    rede(true)
    const id = crypto.randomUUID()
    const aberta: SessaoCaixa = {
      id, usuarioId: ana.usuarioId, versao: 4, valorAbertura: 10, valorFechamentoEsperado: 12,
      valorFechamentoContado: null, diferenca: null, abertaEm: new Date().toISOString(),
      fechadaEm: null, status: 'ABERTA', movimentos: [{ id: crypto.randomUUID(),
        tipo: 'VENDA', valor: 2, motivo: null, vendaId: crypto.randomUUID(),
        recebimentoId: null, criadoEm: new Date().toISOString() }],
    }
    const remoto = { ...caixa,
      abertaDoOperadorAtual: vi.fn(async () => aberta),
      historico: vi.fn(async () => [aberta]),
      consultar: vi.fn(async () => aberta),
      abrir: vi.fn(async () => { throw new SemConexao() }),
      sangrar: vi.fn(async () => { throw new SemConexao() }),
    }
    const local = criarCaixaLocal(remoto)
    await local.abertaDoOperadorAtual()
    await local.consultar(id)
    await expect(local.sangrar(id, 1, 'Retirada')).rejects.toBeInstanceOf(SemConexao)
    expect(await listarGestos()).toEqual([])

    rede(false)
    await local.sangrar(id, 1, 'Retirada')
    expect(await criarCaixaLocal(remoto).consultar(id)).toMatchObject({
      versao: 5, valorFechamentoEsperado: 11,
      movimentos: [{ tipo: 'VENDA' }, { tipo: 'SANGRIA' }],
    })
    expect((await listarGestos())[0].versaoBase).toBe(4)
  })
})
