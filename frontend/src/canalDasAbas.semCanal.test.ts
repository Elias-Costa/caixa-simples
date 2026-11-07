import 'fake-indexeddb/auto'
import { deleteDB } from 'idb'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { Identidade } from './sessao/Identidade'
import { tokenComExpiracao } from './sessao/tokenDeTeste'

const ana: Identidade = {
  contaId: 'conta-a', usuarioId: 'ana', nome: 'Ana', nomeNegocio: 'Cafeteria Aurora',
  perfil: 'OPERADOR', estoqueHabilitado: false,
}

/**
 * O navegador sem o canal entre as abas. Os módulos são carregados de novo depois de tirar o global, porque o
 * canal abre uma vez, na primeira vez que alguém avisa ou ouve.
 */
async function semCanal() {
  vi.stubGlobal('BroadcastChannel', undefined)
  vi.resetModules()
  return {
    canal: await import('./canalDasAbas'),
    conexao: await import('./api/conexao'),
    fila: await import('./offline/fila'),
    envio: await import('./offline/envio'),
    armazenamento: await import('./sessao/armazenamento'),
  }
}

beforeEach(async () => {
  await deleteDB('ecrx-caixa-offline')
})

describe('sem o canal entre as abas no navegador', () => {
  it('cada aba continua como antes, sem erro', async () => {
    const { canal, conexao, fila, envio, armazenamento } = await semCanal()
    expect(typeof BroadcastChannel).toBe('undefined')

    const parar = canal.aoAvisoDeOutraAba(() => undefined)
    canal.avisarAsOutrasAbas({ tipo: 'fila' })
    parar()

    armazenamento.gravarToken(tokenComExpiracao(new Date(Date.now() + 60 * 60 * 1000)))
    armazenamento.gravarIdentidade(ana)
    const mudancas: string[] = []
    const pararFila = fila.aoMudarFila((mudanca) => mudancas.push(mudanca))
    await fila.enfileirarGesto({ tipo: 'cliente.criar', registroId: crypto.randomUUID(),
      payload: { nome: 'Maria', contato: null } })
    expect(await envio.enviarFila(async (operacoes) => operacoes.map((operacao) => ({
      operacaoId: operacao.operacaoId, resultado: 'APLICADA' as const }))))
      .toEqual({ desfecho: 'concluida', resolvidos: 1, respondida: true })
    pararFila()
    expect(mudancas).toEqual(['gesto-novo', 'estado', 'estado'])

    conexao.registrarSemResposta()
    expect(conexao.estadoDaConexao()).toBe('sem-servidor')
    conexao.registrarResposta()
    expect(conexao.estadoDaConexao()).toBe('com-servidor')

    armazenamento.marcarSessaoEncerrada()
    expect(armazenamento.sessaoFoiEncerrada()).toBe(true)
  })
})
