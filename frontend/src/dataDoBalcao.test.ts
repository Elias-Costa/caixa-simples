import { afterEach, expect, it, vi } from 'vitest'
import { abertaAntesDeHoje, diaNoBalcao, hojeNoBalcao } from './dataDoBalcao'

afterEach(() => vi.useRealTimers())

it('a data do balcão ainda é a anterior quando UTC já virou o dia', () => {
  vi.useFakeTimers()
  vi.setSystemTime(new Date('2026-09-23T02:30:00Z'))

  expect(hojeNoBalcao()).toBe('2026-09-22')
  expect(diaNoBalcao(new Date('2026-09-23T03:00:00Z'))).toBe('2026-09-23')
})

it('o caixa aberto ontem, mesmo às 23h59 do balcão, é de um dia anterior; o de hoje cedo, não', () => {
  vi.useFakeTimers()
  vi.setSystemTime(new Date('2026-10-24T03:10:00Z')) // 0h10 do dia 24 no balcão

  expect(abertaAntesDeHoje('2026-10-24T02:59:00Z')).toBe(true) // 23h59 do dia 23
  expect(abertaAntesDeHoje('2026-10-24T03:00:00Z')).toBe(false) // meia-noite do dia 24
  expect(abertaAntesDeHoje('2026-10-21T12:00:00Z')).toBe(true)
})
