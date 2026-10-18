import { describe, expect, it } from 'vitest'
import { lerDinheiro, valorNoCampo } from './formatos'

describe('dinheiro digitado', () => {
  it('aceita vírgula ou ponto antes de até duas casas', () => {
    expect(lerDinheiro('12,5')).toBe(12.5)
    expect(lerDinheiro('12.50')).toBe(12.5)
    expect(lerDinheiro('0')).toBe(0)
    expect(lerDinheiro(valorNoCampo(1234.5))).toBe(1234.5)
  })

  it('recusa terceira casa, ponto de milhar, sinal e texto vazio', () => {
    expect(lerDinheiro('5,505')).toBeNull()
    expect(lerDinheiro('1.500')).toBeNull()
    expect(lerDinheiro('1.500,00')).toBeNull()
    expect(lerDinheiro('-5')).toBeNull()
    expect(lerDinheiro('12,')).toBeNull()
    expect(lerDinheiro('')).toBeNull()
  })
})
