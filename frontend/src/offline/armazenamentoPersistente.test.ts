import { describe, expect, it, vi } from 'vitest'
import { observarInstalacao, pedirArmazenamentoPersistente } from './armazenamentoPersistente'

describe('armazenamento persistente', () => {
  it('pede persistência ao navegador quando ainda não foi concedida', async () => {
    const persist = vi.fn().mockResolvedValue(true)
    vi.stubGlobal('navigator', {
      storage: { persisted: vi.fn().mockResolvedValue(false), persist },
    })
    expect(await pedirArmazenamentoPersistente()).toBe(true)
    expect(persist).toHaveBeenCalledOnce()
  })

  it('continua quando o navegador não oferece a API', async () => {
    vi.stubGlobal('navigator', {})
    expect(await pedirArmazenamentoPersistente()).toBeUndefined()
  })

  it('pede persistência quando o PWA é instalado', async () => {
    const persist = vi.fn().mockResolvedValue(true)
    vi.stubGlobal('navigator', {
      storage: { persisted: vi.fn().mockResolvedValue(false), persist },
    })
    observarInstalacao()
    window.dispatchEvent(new Event('appinstalled'))
    await vi.waitFor(() => expect(persist).toHaveBeenCalledOnce())
  })
})
