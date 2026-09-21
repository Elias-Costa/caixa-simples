import { describe, expect, it } from 'vitest'
import { tokenExpirado } from './token'
import { tokenComExpiracao } from './tokenDeTeste'

describe('tokenExpirado', () => {
  const agora = new Date('2026-09-21T12:00:00Z')

  it('token que expira no futuro ainda vale', () => {
    const token = tokenComExpiracao(new Date('2026-09-22T11:59:00Z'))
    expect(tokenExpirado(token, agora)).toBe(false)
  })

  it('token que expirou no passado não vale', () => {
    const token = tokenComExpiracao(new Date('2026-09-21T11:59:00Z'))
    expect(tokenExpirado(token, agora)).toBe(true)
  })

  it('token que expira exatamente agora já não vale', () => {
    expect(tokenExpirado(tokenComExpiracao(agora), agora)).toBe(true)
  })

  it('token ilegível conta como expirado', () => {
    expect(tokenExpirado('isto.nao.e.um.jwt', agora)).toBe(true)
    expect(tokenExpirado('a.b', agora)).toBe(true)
    expect(tokenExpirado('a.!!!.c', agora)).toBe(true)
    expect(tokenExpirado(`a.${btoa('{"sem":"exp"}')}.c`, agora)).toBe(true)
  })
})
