import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterEach, vi } from 'vitest'

// Cada teste começa sem sessão guardada e sem fetch falso do teste anterior.
afterEach(() => {
  cleanup()
  localStorage.clear()
  sessionStorage.clear()
  vi.unstubAllGlobals()
})
