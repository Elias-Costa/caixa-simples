import { afterEach, describe, expect, it, vi } from 'vitest'
import { aoMudarConexao, estadoDaConexao, registrarResposta, registrarSemResposta } from './conexao'

const abertos: BroadcastChannel[] = []

/** O canal que a outra aba do aplicativo abriria, com o que chega nele. */
function outraAba() {
  const canal = new BroadcastChannel('ecrx-caixa-abas')
  const recebidos: unknown[] = []
  canal.addEventListener('message', (evento) => recebidos.push(evento.data))
  abertos.push(canal)
  return { canal, recebidos }
}

const depoisDaEntrega = () => new Promise((resolver) => setTimeout(resolver, 0))

afterEach(() => {
  for (const canal of abertos.splice(0)) canal.close()
})

describe('o estado da conexão entre as abas', () => {
  it('o sem resposta do servidor numa aba chega à outra, e a resposta também', async () => {
    const { canal } = outraAba()
    const avisos = vi.fn()
    const parar = aoMudarConexao(avisos)
    try {
      canal.postMessage({ tipo: 'conexao', semResposta: true, em: Date.now() + 1 })
      await vi.waitFor(() => expect(estadoDaConexao()).toBe('sem-servidor'))
      expect(avisos).toHaveBeenCalledTimes(1)

      canal.postMessage({ tipo: 'conexao', semResposta: false, em: Date.now() + 2 })
      await vi.waitFor(() => expect(estadoDaConexao()).toBe('com-servidor'))
      expect(avisos).toHaveBeenCalledTimes(2)
    } finally {
      parar()
    }
  })

  it('a chamada desta aba avisa as outras só quando muda o estado, com o momento em que terminou', async () => {
    const { recebidos } = outraAba()
    const antes = Date.now()
    registrarSemResposta()
    registrarSemResposta()
    registrarResposta()
    registrarResposta()
    await depoisDaEntrega()

    expect(recebidos).toEqual([
      { tipo: 'conexao', semResposta: true, em: expect.any(Number) },
      { tipo: 'conexao', semResposta: false, em: expect.any(Number) },
    ])
    expect((recebidos[0] as { em: number }).em).toBeGreaterThanOrEqual(antes)
  })

  it('o aviso mais velho que o último fato desta aba não o desfaz', async () => {
    const { canal } = outraAba()
    const antigo = Date.now() - 1000
    registrarResposta()

    canal.postMessage({ tipo: 'conexao', semResposta: true, em: antigo })
    await depoisDaEntrega()

    expect(estadoDaConexao()).toBe('com-servidor')
  })

  it('o estado que chega de outra aba não volta ao canal', async () => {
    const { canal, recebidos } = outraAba()
    canal.postMessage({ tipo: 'conexao', semResposta: true, em: Date.now() + 1 })
    await vi.waitFor(() => expect(estadoDaConexao()).toBe('sem-servidor'))
    await depoisDaEntrega()

    expect(recebidos).toEqual([])
  })
})
