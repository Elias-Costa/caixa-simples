import 'fake-indexeddb/auto'
import { deleteDB } from 'idb'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ErroDaApi, SemConexao } from '../api/cliente'
import type { OperacaoDoLote, ResultadoDaOperacao, ResultadoNoServidor } from '../api/sincronizacao'
import { gravarIdentidade, gravarToken } from '../sessao/armazenamento'
import type { Identidade } from '../sessao/Identidade'
import { tokenComExpiracao } from '../sessao/tokenDeTeste'
import { enviarFila, LIMITE_DO_LOTE } from './envio'
import { enfileirarGesto, iniciarEnvio, listarGestos, type GestoNaFila } from './fila'

const ana: Identidade = {
  contaId: 'conta-a', usuarioId: 'ana', nome: 'Ana', nomeNegocio: 'Cafeteria Aurora',
  perfil: 'OPERADOR', estoqueHabilitado: false,
}

function entrar(identidade: Identidade) {
  gravarToken(tokenComExpiracao(new Date(Date.now() + 60 * 60 * 1000)))
  gravarIdentidade(identidade)
}

/**
 * O servidor como o contrato do lote o descreve: o resultado fica gravado por id de operação, e o
 * reenvio devolve o gravado sem aplicar de novo. O erro transitório não é gravado.
 */
function servidorFalso(decidir: (operacao: OperacaoDoLote) => ResultadoNoServidor = () => 'APLICADA') {
  const gravados = new Map<string, ResultadoDaOperacao>()
  const aplicadas: string[] = []
  let respostasPerdidas = 0
  const enviar = vi.fn(async (operacoes: OperacaoDoLote[]) => {
    const resultados = operacoes.map((operacao) => {
      const gravado = gravados.get(operacao.operacaoId)
      if (gravado) return gravado
      const resultado = decidir(operacao)
      const novo: ResultadoDaOperacao = { operacaoId: operacao.operacaoId, resultado,
        ...(resultado === 'APLICADA' ? { versao: 1 } : { detalhe: `motivo de ${operacao.tipo}` }) }
      if (resultado !== 'ERRO_TRANSITORIO') {
        gravados.set(operacao.operacaoId, novo)
        aplicadas.push(operacao.operacaoId)
      }
      return novo
    })
    if (respostasPerdidas > 0) {
      respostasPerdidas--
      throw new SemConexao()
    }
    return resultados
  })
  return {
    enviar,
    aplicadas,
    perderAsProximasRespostas(quantas: number) { respostasPerdidas = quantas },
  }
}

async function cliente(nome: string, dependeDe: GestoNaFila[] = []): Promise<GestoNaFila> {
  return enfileirarGesto({ tipo: dependeDe.length ? 'cliente.editar' : 'cliente.criar',
    registroId: '00000000-0000-4000-8000-000000000001', payload: { nome, contato: null },
    dependeDe: dependeDe.map((gesto) => gesto.operacaoId) })
}

async function estados(): Promise<Record<string, string>> {
  return Object.fromEntries((await listarGestos()).map((gesto) => [gesto.operacaoId, gesto.estado]))
}

beforeEach(async () => {
  await deleteDB('caixa-simples-offline')
})

describe('envio da fila', () => {
  it('envia em ordem de dependência, em lotes de até cem, e numera os resultados nessa ordem', async () => {
    entrar(ana)
    const criados: GestoNaFila[] = [await cliente('Cliente 0')]
    for (let i = 1; i < 150; i++) criados.push(await cliente(`Cliente ${i}`, [criados[i - 1]]))
    const servidor = servidorFalso()

    expect(await enviarFila(servidor.enviar)).toEqual({ desfecho: 'concluida', resolvidos: 150 })

    expect(servidor.enviar).toHaveBeenCalledTimes(2)
    const [primeiro, segundo] = servidor.enviar.mock.calls.map(([operacoes]) => operacoes)
    expect(primeiro).toHaveLength(LIMITE_DO_LOTE)
    expect(segundo).toHaveLength(50)
    expect([...primeiro, ...segundo].map((operacao) => operacao.operacaoId))
      .toEqual(criados.map((gesto) => gesto.operacaoId))
    expect(primeiro[1]).toMatchObject({ tipo: 'cliente.editar', dependeDe: [criados[0].operacaoId],
      payload: { nome: 'Cliente 1', contato: null }, criadoEm: criados[1].criadoEm })
    const gestos = new Map((await listarGestos()).map((gesto) => [gesto.operacaoId, gesto]))
    expect(criados.map((gesto) => gestos.get(gesto.operacaoId)?.ordemDoResultado))
      .toEqual(criados.map((_gesto, indice) => indice + 1))
  })

  it('separa o aplicado, a revisão, a recusa e a falha temporária, e só repete a falha', async () => {
    entrar(ana)
    const resultados: ResultadoNoServidor[] = ['APLICADA', 'APLICADA_COM_REVISAO', 'NAO_APLICADA',
      'ERRO_TRANSITORIO']
    const gestos: GestoNaFila[] = []
    for (const resultado of resultados) {
      gestos.push(await enfileirarGesto({ tipo: 'cliente.criar', registroId: crypto.randomUUID(),
        payload: { nome: resultado, contato: null } }))
    }
    const servidor = servidorFalso((operacao) => (operacao.payload as { nome: ResultadoNoServidor }).nome)

    expect(await enviarFila(servidor.enviar)).toEqual({ desfecho: 'concluida', resolvidos: 3 })

    const porId = new Map((await listarGestos()).map((gesto) => [gesto.operacaoId, gesto]))
    expect(porId.get(gestos[0].operacaoId)).toMatchObject({ estado: 'sent',
      resultado: { aplicada: true, versao: 1 } })
    expect(porId.get(gestos[1].operacaoId)).toMatchObject({ estado: 'needs_review',
      resultado: { aplicada: true, detalhe: 'motivo de cliente.criar' } })
    expect(porId.get(gestos[2].operacaoId)).toMatchObject({ estado: 'needs_review',
      resultado: { aplicada: false, detalhe: 'motivo de cliente.criar' } })
    expect(porId.get(gestos[3].operacaoId)).toMatchObject({ estado: 'failed',
      falha: 'motivo de cliente.criar' })

    await enviarFila(servidor.enviar)
    expect(servidor.enviar.mock.calls[1][0].map((operacao) => operacao.operacaoId))
      .toEqual([gestos[3].operacaoId])
  })

  it('com a resposta perdida duas vezes, o terceiro envio do mesmo lote termina sem aplicar de novo', async () => {
    entrar(ana)
    const criacao = await cliente('Maria')
    const edicao = await cliente('Maria Souza', [criacao])
    const servidor = servidorFalso()
    servidor.perderAsProximasRespostas(2)

    expect((await enviarFila(servidor.enviar)).desfecho).toBe('interrompida')
    expect(await estados()).toEqual({ [criacao.operacaoId]: 'failed', [edicao.operacaoId]: 'failed' })
    expect((await enviarFila(servidor.enviar)).desfecho).toBe('interrompida')
    expect(await enviarFila(servidor.enviar)).toEqual({ desfecho: 'concluida', resolvidos: 2 })

    expect(await estados()).toEqual({ [criacao.operacaoId]: 'sent', [edicao.operacaoId]: 'sent' })
    for (const [operacoes] of servidor.enviar.mock.calls) {
      expect(operacoes.map((operacao) => operacao.operacaoId))
        .toEqual([criacao.operacaoId, edicao.operacaoId])
    }
    expect(servidor.aplicadas).toEqual([criacao.operacaoId, edicao.operacaoId])
  })

  it('erro do servidor e falta de rede viram falha, param a rodada e voltam na seguinte', async () => {
    entrar(ana)
    const gesto = await cliente('Bia')
    const servidor = servidorFalso()

    const foraDoAr = vi.fn(async () => { throw new ErroDaApi(503, 'Indisponível', 'manutenção', {}) })
    expect((await enviarFila(foraDoAr)).desfecho).toBe('interrompida')
    expect((await listarGestos())[0]).toMatchObject({ estado: 'failed',
      falha: expect.stringContaining('manutenção') })

    const semRede = vi.fn(async () => { throw new SemConexao() })
    expect((await enviarFila(semRede)).desfecho).toBe('interrompida')
    expect((await listarGestos())[0]).toMatchObject({ estado: 'failed',
      falha: expect.stringContaining('Sem conexão') })

    expect(await enviarFila(servidor.enviar)).toEqual({ desfecho: 'concluida', resolvidos: 1 })
    expect(await estados()).toEqual({ [gesto.operacaoId]: 'sent' })
  })

  it('o 401 devolve o lote à fila, sem perder nada, e o novo login do mesmo usuário o envia', async () => {
    entrar(ana)
    const gesto = await cliente('Carla')
    const recusado = vi.fn(async () => { throw new ErroDaApi(401, 'Não autorizado', undefined, {}) })

    expect(await enviarFila(recusado)).toEqual({ desfecho: 'sessao-recusada', resolvidos: 0 })
    expect(await estados()).toEqual({ [gesto.operacaoId]: 'queued' })

    entrar(ana)
    const servidor = servidorFalso()
    expect(await enviarFila(servidor.enviar)).toEqual({ desfecho: 'concluida', resolvidos: 1 })
  })

  it('com o token expirado não lê nem envia a fila, que continua para o próximo login', async () => {
    entrar(ana)
    const gesto = await cliente('Dora')
    gravarToken(tokenComExpiracao(new Date(Date.now() - 1000)))
    gravarIdentidade(ana)
    const servidor = servidorFalso()

    expect(await enviarFila(servidor.enviar)).toEqual({ desfecho: 'sem-fila', resolvidos: 0 })
    expect(servidor.enviar).not.toHaveBeenCalled()

    entrar(ana)
    expect(await estados()).toEqual({ [gesto.operacaoId]: 'queued' })
  })

  it('a fila da Conta A não sai com a Conta B autenticada', async () => {
    entrar(ana)
    const daContaA = await cliente('Eva')
    entrar({ ...ana, contaId: 'conta-b', nomeNegocio: 'Loja da Esquina' })
    const servidor = servidorFalso()

    expect(await enviarFila(servidor.enviar)).toEqual({ desfecho: 'concluida', resolvidos: 0 })
    expect(servidor.enviar).not.toHaveBeenCalled()

    entrar(ana)
    await enviarFila(servidor.enviar)
    expect(servidor.enviar.mock.calls[0][0].map((operacao) => operacao.operacaoId))
      .toEqual([daContaA.operacaoId])
  })

  it('repete com o mesmo id o envio interrompido por recarga', async () => {
    entrar(ana)
    const gesto = await cliente('Fabi')
    // A rodada anterior marcou o gesto e a página recarregou antes da resposta.
    await iniciarEnvio([gesto.operacaoId])
    const servidor = servidorFalso()

    expect(await enviarFila(servidor.enviar)).toEqual({ desfecho: 'concluida', resolvidos: 1 })
    expect(servidor.enviar.mock.calls[0][0][0].operacaoId).toBe(gesto.operacaoId)
    expect(await estados()).toEqual({ [gesto.operacaoId]: 'sent' })
  })

  it('não começa outra rodada na mesma aba enquanto uma está em curso', async () => {
    entrar(ana)
    await cliente('Gil')
    let liberar: () => void = () => undefined
    const lento = vi.fn((operacoes: OperacaoDoLote[]) => new Promise<ResultadoDaOperacao[]>((resolver) => {
      liberar = () => resolver(operacoes.map((operacao) => ({ operacaoId: operacao.operacaoId,
        resultado: 'APLICADA' })))
    }))

    const primeira = enviarFila(lento)
    await vi.waitFor(() => expect(lento).toHaveBeenCalled())
    expect(await enviarFila(lento)).toEqual({ desfecho: 'ocupada', resolvidos: 0 })
    liberar()
    expect(await primeira).toEqual({ desfecho: 'concluida', resolvidos: 1 })
    expect(lento).toHaveBeenCalledTimes(1)
  })
})
