import { afterEach, describe, expect, it } from 'vitest'
import { aoAvisoDeOutraAba, avisarAsOutrasAbas, type AvisoEntreAbas } from './canalDasAbas'

const abertos: BroadcastChannel[] = []

function outraAba() {
  const canal = new BroadcastChannel('ecrx-caixa-abas')
  abertos.push(canal)
  return canal
}

const depoisDaEntrega = () => new Promise((resolver) => setTimeout(resolver, 0))

afterEach(() => {
  for (const canal of abertos.splice(0)) canal.close()
})

describe('o canal entre as abas', () => {
  it('leva o aviso à outra aba e não o devolve a quem avisou', async () => {
    const outra = outraAba()
    const naOutra: unknown[] = []
    outra.addEventListener('message', (evento) => naOutra.push(evento.data))
    const aqui: AvisoEntreAbas[] = []
    const parar = aoAvisoDeOutraAba((aviso) => aqui.push(aviso))

    avisarAsOutrasAbas({ tipo: 'fila' })
    await depoisDaEntrega()
    parar()

    expect(naOutra).toEqual([{ tipo: 'fila' }])
    expect(aqui).toEqual([])
  })

  it('ignora o aviso que esta versão não conhece e tira os campos que ela não espera', async () => {
    const outra = outraAba()
    const aqui: AvisoEntreAbas[] = []
    const parar = aoAvisoDeOutraAba((aviso) => aqui.push(aviso))

    for (const dados of [null, 'fila', 7, { tipo: 'outra-coisa' }, { tipo: 'rodada', fase: 'meio' },
      { tipo: 'rodada', fase: 'fim' }, { tipo: 'conexao', semResposta: 'sim', em: 1 },
      { tipo: 'conexao', semResposta: true, em: Number.NaN }]) {
      outra.postMessage(dados)
    }
    outra.postMessage({ tipo: 'fila', token: 'abc', gestos: [{ nome: 'Maria' }] })
    outra.postMessage({ tipo: 'rodada', fase: 'fim', gravou: true, dono: 'conta-a' })
    await depoisDaEntrega()
    parar()

    expect(aqui).toEqual([{ tipo: 'fila' }, { tipo: 'rodada', fase: 'fim', gravou: true }])
  })
})
