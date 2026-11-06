import { describe, expect, it } from 'vitest'
import estilos from './estilos.css?inline'

function regra(seletor: string): string {
  const inicio = estilos.indexOf(`\n${seletor} {`)
  expect(inicio, `a regra ${seletor} existe`).toBeGreaterThanOrEqual(0)
  return estilos.slice(inicio, estilos.indexOf('}', inicio))
}

describe('alvos de toque', () => {
  // O jsdom não calcula layout: a altura medida fica com o roteiro do navegador, e aqui fica a regra.
  it('os avisos do cabeçalho têm o alvo de toque, porque no celular são o caminho até a Sincronização', () => {
    const aviso = regra('.cabecalho__aviso')
    expect(aviso).toContain('min-height: var(--toque)')
    // Um link em linha ignoraria a altura mínima.
    expect(aviso).toContain('display: inline-flex')
  })
})
