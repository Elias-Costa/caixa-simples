import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { intencaoOnline } from './intencaoOnline'

describe('intenção online de dinheiro', () => {
  let sequencia = 0
  beforeEach(() => {
    sessionStorage.clear()
    sequencia = 0
    vi.stubGlobal('crypto', {
      randomUUID: () => `id-${++sequencia}`,
      subtle: { digest: async (_algoritmo: string, dados: Uint8Array) => dados.buffer },
    })
  })
  afterEach(() => vi.unstubAllGlobals())

  it('reusa o UUID após uma resposta perdida e libera a intenção só após confirmação', async () => {
    const rota = '/api/caixa/sessoes/sessao-a/sangrias'
    const conteudo = { valor: 5, motivo: 'Retirada' }
    const identidade = { contaId: 'conta-a', usuarioId: 'usuario-a' }
    const primeira = await intencaoOnline(rota, conteudo, identidade)
    const repetida = await intencaoOnline(rota, conteudo, identidade)
    expect(repetida.id).toBe(primeira.id)
    expect((await intencaoOnline(rota, { valor: 6, motivo: 'Retirada' }, identidade)).id)
      .not.toBe(primeira.id)
    const gravado = Array.from({ length: sessionStorage.length }, (_, indice) => {
      const chave = sessionStorage.key(indice) ?? ''
      return chave + sessionStorage.getItem(chave)
    }).join(' ')
    expect(gravado).not.toContain('Retirada')
    primeira.confirmar()
    expect((await intencaoOnline(rota, conteudo, identidade)).id).not.toBe(primeira.id)
  })
})
