import { describe, expect, it } from 'vitest'
import { paraBusca } from './textoDeBusca'

describe('texto de busca', () => {
  it('tira o acento, a cedilha, as maiúsculas e os espaços das pontas', () => {
    expect(paraBusca('  Água Mineral ')).toBe('agua mineral')
    expect(paraBusca('Pão de queijo')).toBe('pao de queijo')
    expect(paraBusca('AÇAÍ')).toBe('acai')
    expect(paraBusca('João')).toBe(paraBusca('joao'))
  })
})
