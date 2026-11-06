import { describe, expect, it } from 'vitest'
import { avisoDaSessao } from './avisoDaSessao'

const UMA_HORA = 60 * 60 * 1000

describe('avisoDaSessao', () => {
  it('não avisa com mais de 24 horas pela frente', () => {
    expect(avisoDaSessao(24 * UMA_HORA + 1)).toBeNull()
  })

  it('começa 24 horas antes', () => {
    expect(avisoDaSessao(24 * UMA_HORA)).toBe('Sessão vence em 24 horas. Qualquer conexão renova.')
  })

  it('arredonda a hora para baixo, para nunca prometer o tempo que não há', () => {
    expect(avisoDaSessao(5.9 * UMA_HORA)).toBe('Sessão vence em 5 horas. Qualquer conexão renova.')
  })

  it('uma hora fica no singular', () => {
    expect(avisoDaSessao(1.5 * UMA_HORA)).toBe('Sessão vence em 1 hora. Qualquer conexão renova.')
  })

  it('abaixo de uma hora, diz menos de 1 hora', () => {
    expect(avisoDaSessao(59 * 60 * 1000)).toBe('Sessão vence em menos de 1 hora. Qualquer conexão renova.')
  })

  it('o token já vencido não tem o que avisar: a entrada é quem fala', () => {
    expect(avisoDaSessao(0)).toBeNull()
  })
})
