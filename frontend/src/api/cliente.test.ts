import { describe, expect, it, vi } from 'vitest'
import { gravarToken, lerToken } from '../sessao/armazenamento'
import { CABECALHO_DO_TOKEN_RENOVADO, chamarApi, ErroDaApi, SemConexao } from './cliente'

function respostaJson(status: number, corpo: unknown, cabecalhos: Record<string, string> = {}) {
  return new Response(JSON.stringify(corpo), {
    status,
    headers: { 'Content-Type': 'application/json', ...cabecalhos },
  })
}

describe('chamarApi', () => {
  it('envia o token guardado como Bearer e devolve o corpo lido', async () => {
    gravarToken('token-guardado')
    const fetchFalso = vi.fn().mockResolvedValue(respostaJson(200, { nome: 'Aurora' }))
    vi.stubGlobal('fetch', fetchFalso)

    const corpo = await chamarApi<{ nome: string }>('/api/auth/eu')

    expect(corpo).toEqual({ nome: 'Aurora' })
    const [caminho, opcoes] = fetchFalso.mock.calls[0] as [string, RequestInit]
    expect(caminho).toBe('/api/auth/eu')
    expect(new Headers(opcoes.headers).get('Authorization')).toBe('Bearer token-guardado')
  })

  it('substitui o token guardado pelo que a resposta devolve no cabeçalho', async () => {
    gravarToken('token-antigo')
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(respostaJson(200, {}, { [CABECALHO_DO_TOKEN_RENOVADO]: 'token-novo' })),
    )

    await chamarApi('/api/auth/eu')

    expect(lerToken()).toBe('token-novo')
  })

  it('chamada sem sessão não envia Authorization e serializa o corpo como JSON', async () => {
    const fetchFalso = vi.fn().mockResolvedValue(respostaJson(200, { token: 'abc' }))
    vi.stubGlobal('fetch', fetchFalso)

    await chamarApi('/api/auth/login', {
      metodo: 'POST',
      corpo: { email: 'a@b.c', senha: 'x' },
      autenticado: false,
    })

    const [, opcoes] = fetchFalso.mock.calls[0] as [string, RequestInit]
    expect(opcoes.method).toBe('POST')
    expect(new Headers(opcoes.headers).has('Authorization')).toBe(false)
    expect(new Headers(opcoes.headers).get('Content-Type')).toBe('application/json')
    expect(opcoes.body).toBe(JSON.stringify({ email: 'a@b.c', senha: 'x' }))
  })

  it('erro em Problem Details vira ErroDaApi com status, detalhe e campos', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            status: 400,
            title: 'Bad Request',
            detail: 'Pedido inválido',
            campos: { nome: 'não pode ser vazio' },
          }),
          { status: 400, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    )

    const falha = await chamarApi('/api/qualquer').catch((erro: unknown) => erro)

    expect(falha).toBeInstanceOf(ErroDaApi)
    const erro = falha as ErroDaApi
    expect(erro.status).toBe(400)
    expect(erro.detalhe).toBe('Pedido inválido')
    expect(erro.campos).toEqual({ nome: 'não pode ser vazio' })
  })

  it('401 sem corpo vira ErroDaApi com o status e sem campos', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(null, { status: 401 })))

    const falha = await chamarApi('/api/auth/login', { autenticado: false }).catch((erro: unknown) => erro)

    expect(falha).toBeInstanceOf(ErroDaApi)
    expect((falha as ErroDaApi).status).toBe(401)
    expect((falha as ErroDaApi).campos).toEqual({})
  })

  it('fetch que não chega ao servidor vira SemConexao', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')))

    await expect(chamarApi('/api/auth/eu')).rejects.toBeInstanceOf(SemConexao)
  })
})
