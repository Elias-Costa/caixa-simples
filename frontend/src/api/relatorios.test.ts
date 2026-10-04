import { describe, expect, it, vi } from 'vitest'
import { gravarToken } from '../sessao/armazenamento'
import { relatorios } from './relatorios'

function caminhoPedido(fetchFalso: ReturnType<typeof vi.fn>): string {
  return fetchFalso.mock.calls[0][0] as string
}

function respostaVazia() {
  return new Response('{}', { status: 200, headers: { 'Content-Type': 'application/json' } })
}

describe('chamadas dos relatórios', () => {
  it('o faturamento leva só os filtros pedidos', async () => {
    gravarToken('token-de-teste')
    const semFiltro = vi.fn().mockResolvedValue(respostaVazia())
    vi.stubGlobal('fetch', semFiltro)
    await relatorios.faturamento('2026-09-01', '2026-09-15')
    expect(caminhoPedido(semFiltro)).toBe('/api/relatorios/faturamento?inicio=2026-09-01&fim=2026-09-15')

    const comFiltros = vi.fn().mockResolvedValue(respostaVazia())
    vi.stubGlobal('fetch', comFiltros)
    await relatorios.faturamento('2026-09-01', '2026-09-15', { forma: 'PIX', operadorId: 'usuario-1' })
    expect(caminhoPedido(comFiltros))
      .toBe('/api/relatorios/faturamento?inicio=2026-09-01&fim=2026-09-15&forma=PIX&operadorId=usuario-1')
  })

  it('o ranking leva o limite e, quando houver, o operador; o fluxo, só o período', async () => {
    gravarToken('token-de-teste')
    const daConta = vi.fn().mockResolvedValue(respostaVazia())
    vi.stubGlobal('fetch', daConta)
    await relatorios.maisVendidos('2026-09-15', '2026-09-15', 10)
    expect(caminhoPedido(daConta)).toBe('/api/relatorios/mais-vendidos?inicio=2026-09-15&fim=2026-09-15&limite=10')

    const doOperador = vi.fn().mockResolvedValue(respostaVazia())
    vi.stubGlobal('fetch', doOperador)
    await relatorios.maisVendidos('2026-09-15', '2026-09-15', 10, 'usuario-1')
    expect(caminhoPedido(doOperador))
      .toBe('/api/relatorios/mais-vendidos?inicio=2026-09-15&fim=2026-09-15&limite=10&operadorId=usuario-1')

    const fluxo = vi.fn().mockResolvedValue(respostaVazia())
    vi.stubGlobal('fetch', fluxo)
    await relatorios.fluxoDeCaixa('2026-09-01', '2026-09-15')
    expect(caminhoPedido(fluxo)).toBe('/api/relatorios/fluxo-de-caixa?inicio=2026-09-01&fim=2026-09-15')
  })
})
