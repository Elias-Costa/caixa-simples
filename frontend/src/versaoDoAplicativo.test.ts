import { describe, expect, it, vi } from 'vitest'
import { versaoDoAplicativo } from './versaoDoAplicativo'

describe('a versão do aplicativo', () => {
  it('são os sete primeiros caracteres do commit que o build recebeu', () => {
    vi.stubEnv('VITE_VERSAO', '0a557ae4c1d2e3f40000000000000000000000ab')
    expect(versaoDoAplicativo()).toBe('0a557ae')
  })

  it('o build que não recebeu o commit, ou o recebeu vazio, diz local', () => {
    vi.stubEnv('VITE_VERSAO', undefined)
    expect(versaoDoAplicativo()).toBe('local')
    vi.stubEnv('VITE_VERSAO', '  ')
    expect(versaoDoAplicativo()).toBe('local')
  })
})
