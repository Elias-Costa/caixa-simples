import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { comServidor, registrarResposta } from './api/conexao'
import { gravarToken, limparSessao } from './sessao/armazenamento'

// O módulo guarda o que já relatou nesta carga da página: cada teste o carrega de novo, como uma página nova.
async function carregar() {
  vi.resetModules()
  return import('./registroDeErros')
}

/** Um erro com a pilha que o Chrome escreve, com o primeiro trecho no código do aplicativo. */
function erroDoAplicativo(nome = 'TypeError', arquivo = 'assets/index-B2x9.js:1:23456'): Error {
  const erro = new TypeError("Cannot read properties of undefined (reading 'nome') Maria Souza")
  erro.name = nome
  erro.stack = `${nome}: Cannot read properties of undefined (reading 'nome') Maria Souza\n`
    + '    at chrome-extension://abcdef/conteudo.js:3:9\n'
    + `    at Object.lancar (${location.origin}/${arquivo})\n`
    + `    at ${location.origin}/assets/vendor-Xy12.js:9:87`
  return erro
}

const enviar = vi.fn(async () => new Response(null, { status: 204 }))

beforeEach(() => {
  registrarResposta()
  vi.stubGlobal('fetch', enviar)
  gravarToken('token-de-teste')
})
afterEach(() => {
  vi.unstubAllGlobals()
  enviar.mockClear()
  limparSessao()
  history.replaceState(null, '', '/')
})

describe('o relato do erro do aplicativo', () => {
  it('manda o tipo, o local no código do aplicativo, a tela e a versão, sem a mensagem, com o token', async () => {
    const { relatarErro } = await carregar()
    history.replaceState(null, '', '/vender')

    relatarErro(erroDoAplicativo())

    expect(enviar).toHaveBeenCalledTimes(1)
    const [caminho, pedido] = enviar.mock.calls[0] as unknown as [string, RequestInit]
    expect(caminho).toBe('/api/erros-do-aplicativo')
    expect(pedido.method).toBe('POST')
    expect(pedido.keepalive).toBe(true)
    expect(pedido.headers).toEqual(expect.objectContaining({ Authorization: 'Bearer token-de-teste' }))
    expect(JSON.parse(pedido.body as string)).toEqual({
      tipo: 'TypeError', local: 'assets/index-B2x9.js:1:23456', tela: '/vender', versao: expect.any(String),
    })
    expect(pedido.body).not.toMatch(/Maria|Cannot read/)
  })

  it('sem trecho da pilha no aplicativo, como o erro de uma extensão do navegador, não manda nada', async () => {
    const { relatarErro } = await carregar()
    const daExtensao = new Error('falha')
    daExtensao.stack = 'Error: falha\n    at chrome-extension://abcdef/conteudo.js:3:9'

    relatarErro(daExtensao, { arquivo: 'chrome-extension://abcdef/conteudo.js', linha: 3, coluna: 9 })
    relatarErro('texto lançado sem pilha')

    expect(enviar).not.toHaveBeenCalled()
  })

  it('sem pilha, usa o arquivo, a linha e a coluna do evento quando são do aplicativo', async () => {
    const { relatarErro } = await carregar()

    relatarErro('texto lançado', { arquivo: `${location.origin}/assets/index-B2x9.js`, linha: 4, coluna: 17 })

    const [, pedido] = enviar.mock.calls[0] as unknown as [string, RequestInit]
    expect(JSON.parse(pedido.body as string)).toEqual(expect.objectContaining({
      tipo: 'Error', local: 'assets/index-B2x9.js:4:17',
    }))
  })

  it('sem sessão não manda nada', async () => {
    const { relatarErro } = await carregar()
    limparSessao()

    relatarErro(erroDoAplicativo())

    expect(enviar).not.toHaveBeenCalled()
  })

  it('o mesmo erro vai uma vez, e a carga da página para no quinto relato', async () => {
    const { relatarErro } = await carregar()

    relatarErro(erroDoAplicativo())
    relatarErro(erroDoAplicativo())
    expect(enviar).toHaveBeenCalledTimes(1)

    for (let i = 2; i <= 8; i++) relatarErro(erroDoAplicativo('TypeError', `assets/index-B2x9.js:${i}:1`))
    expect(enviar).toHaveBeenCalledTimes(5)
  })

  it('o caractere que forjaria outra linha no log vira sublinhado', async () => {
    const { relatarErro } = await carregar()
    history.replaceState(null, '', '/caixa%0Aforjado')

    relatarErro(erroDoAplicativo('Erro meu'))

    const [, pedido] = enviar.mock.calls[0] as unknown as [string, RequestInit]
    const corpo = JSON.parse(pedido.body as string)
    expect(corpo.tipo).toBe('Erro_meu')
    expect(corpo.tela).toMatch(/^[A-Za-z0-9._:/@%+-]+$/)
  })

  it('o relato que falha não lança, e o aparelho continua com servidor', async () => {
    const { relatarErro } = await carregar()
    enviar.mockRejectedValueOnce(new TypeError('sem rede'))

    expect(() => relatarErro(erroDoAplicativo())).not.toThrow()
    await Promise.resolve()
    await new Promise((pronto) => setTimeout(pronto, 0))

    expect(comServidor()).toBe(true)
  })

  it('observarErros relata a exceção sem tratamento e a promessa rejeitada sem quem a espere', async () => {
    const { observarErros } = await carregar()
    observarErros()

    const erro = erroDoAplicativo()
    dispatchEvent(new ErrorEvent('error', { error: erro, filename: `${location.origin}/assets/index-B2x9.js` }))
    const rejeitada = new Event('unhandledrejection') as Event & { reason?: unknown }
    rejeitada.reason = erroDoAplicativo('RangeError', 'assets/index-B2x9.js:7:7')
    dispatchEvent(rejeitada)

    expect(enviar).toHaveBeenCalledTimes(2)
    const tipos = enviar.mock.calls.map((chamada) => JSON.parse((chamada as unknown as [string, RequestInit])[1].body as string).tipo)
    expect(tipos).toEqual(['TypeError', 'RangeError'])
  })
})
