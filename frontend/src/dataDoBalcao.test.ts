import { afterEach, expect, it, vi } from 'vitest'
import { hojeNoBalcao } from './dataDoBalcao'

afterEach(() => vi.useRealTimers())

it('a data do balcão ainda é a anterior quando UTC já virou o dia', () => {
  vi.useFakeTimers()
  vi.setSystemTime(new Date('2026-09-23T02:30:00Z'))

  expect(hojeNoBalcao()).toBe('2026-09-22')
})
