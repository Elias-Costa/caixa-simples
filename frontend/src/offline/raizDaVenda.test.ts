import { describe, expect, it } from 'vitest'
import type { GestoNaFila } from './fila'
import {
  abrirVenda, adicionarItem, aplicarDesconto, concluir, dinheiroNaGaveta, faltaPagar, lancado,
  projetarVendas, registrarPagamento, removerItem, total, vincularCliente,
  type DadosDaParcela, type DadosDoItem, type VendaNoDispositivo,
} from './raizDaVenda'

const agora = '2026-09-25T15:00:00.000Z'

function nova(): VendaNoDispositivo {
  return abrirVenda({ id: 'venda-1', sessaoCaixaId: 'sessao-1', usuarioId: 'ana', criadoEm: agora })
}

function item(dados: Partial<DadosDoItem> = {}): DadosDoItem {
  return {
    itemId: crypto.randomUUID(), produtoId: 'produto-1', nome: 'Café', quantidade: 1,
    precoUnitario: 10, desconto: 0, versaoProduto: 1, ...dados,
  }
}

function parcela(dados: Partial<DadosDaParcela> & Pick<DadosDaParcela, 'forma' | 'valor'>): DadosDaParcela {
  return { pagamentoId: crypto.randomUUID(), valorRecebido: null, ...dados }
}

describe('raiz da Venda no dispositivo', () => {
  it('mantém o total igual à soma dos itens menos o desconto, arredondando cada item', () => {
    let venda = nova()
    venda = adicionarItem(venda, item({ quantidade: 0.333, precoUnitario: 10 }))
    venda = adicionarItem(venda, item({ quantidade: 1.5, precoUnitario: 3.33 }))
    venda = adicionarItem(venda, item({ quantidade: 2, precoUnitario: 6.25, desconto: 0.5 }))
    venda = aplicarDesconto(venda, 0.33)

    // 3,33 + 5,00 (4,995 arredondado para cima no item) + 12,00 - 0,33
    expect(total(venda)).toBe(2000)
    expect(venda.versao).toBe(4)
    expect(aplicarDesconto(venda, 0).descontoCentavos).toBe(0)
  })

  it('recusa desconto acima do valor, no item e na venda, e a Venda anterior não muda', () => {
    const venda = adicionarItem(nova(), item({ precoUnitario: 10 }))
    expect(() => adicionarItem(venda, item({ precoUnitario: 5, desconto: 5.01 })))
      .toThrow('maior que o valor do item')
    expect(total(adicionarItem(venda, item({ precoUnitario: 5, desconto: 5 })))).toBe(1000)
    expect(() => aplicarDesconto(venda, 10.01)).toThrow('maior que a soma dos itens')
    expect(() => aplicarDesconto(nova(), 0.01)).toThrow('maior que a soma dos itens')
    expect(() => aplicarDesconto(venda, -1)).toThrow('Desconto da venda')

    const comDesconto = aplicarDesconto(adicionarItem(venda, item({ precoUnitario: 2 })), 9)
    expect(() => removerItem(comDesconto, comDesconto.itens[0].id)).toThrow('Reduza o desconto')
    expect(() => removerItem(comDesconto, 'item-que-nao-existe')).toThrow('não está nesta Venda')
    expect(total(venda)).toBe(1000)
    expect(venda.itens).toHaveLength(1)
  })

  it('recusa quantidade e preço fora da regra', () => {
    for (const quantidade of [0, -1, 1.0005, Number.NaN]) {
      expect(() => adicionarItem(nova(), item({ quantidade }))).toThrow('três casas')
    }
    expect(() => adicionarItem(nova(), item({ precoUnitario: -1 }))).toThrow('Preço unitário')
    expect(() => adicionarItem(nova(), item({ precoUnitario: 1.001 }))).toThrow('Preço unitário')
    expect(adicionarItem(nova(), item({ quantidade: 0.75, precoUnitario: 0 })).itens).toHaveLength(1)
  })

  it('recusa parcela acima do que falta, não positiva ou fora da regra da forma', () => {
    const venda = adicionarItem(nova(), item({ precoUnitario: 10 }))
    expect(() => registrarPagamento(venda, parcela({ forma: 'CARTAO', valor: 10.01 })))
      .toThrow('maior que o que falta pagar')
    expect(() => registrarPagamento(venda, parcela({ forma: 'CARTAO', valor: 0 }))).toThrow('positivo')
    expect(() => registrarPagamento(venda, parcela({ forma: 'DINHEIRO', valor: 10 })))
      .toThrow('valor recebido')
    expect(() => registrarPagamento(venda, parcela({ forma: 'DINHEIRO', valor: 10, valorRecebido: 9.99 })))
      .toThrow('menor que o valor da parcela')
    expect(() => registrarPagamento(venda, parcela({ forma: 'CARTAO', valor: 10, valorRecebido: 10 })))
      .toThrow('não aceita valor recebido')
    expect(() => registrarPagamento(venda, parcela({ forma: 'FIADO', valor: 10, valorRecebido: 10 })))
      .toThrow('não aceita valor recebido')
    expect(() => registrarPagamento(venda, parcela({ forma: 'PIX', valor: 10 }))).toThrow('Pix não entra')

    const emDinheiro = registrarPagamento(venda, parcela({ forma: 'DINHEIRO', valor: 6, valorRecebido: 20 }))
    expect(emDinheiro.parcelas[0]).toMatchObject({ status: 'CONFIRMADO', trocoCentavos: 1400 })
    expect(faltaPagar(emDinheiro)).toBe(400)
    const fiado = registrarPagamento(emDinheiro, parcela({ forma: 'FIADO', valor: 2 }))
    expect(fiado.parcelas[1]).toMatchObject({ status: 'PENDENTE', trocoCentavos: 0 })
    expect(() => registrarPagamento(fiado, parcela({ forma: 'FIADO', valor: 2 }))).toThrow('única parcela FIADO')
    expect(venda.parcelas).toHaveLength(0)
  })

  it('não deixa a montagem baixar o total abaixo do que já foi lançado', () => {
    let venda = adicionarItem(nova(), item({ precoUnitario: 10 }))
    venda = adicionarItem(venda, item({ precoUnitario: 5 }))
    venda = registrarPagamento(venda, parcela({ forma: 'CARTAO', valor: 12 }))

    expect(() => removerItem(venda, venda.itens[0].id)).toThrow('abaixo dos')
    expect(() => aplicarDesconto(venda, 3.01)).toThrow('abaixo dos')
    expect(total(aplicarDesconto(venda, 3))).toBe(1200)
    expect(adicionarItem(venda, item()).itens).toHaveLength(3)
    expect(lancado(venda)).toBe(1200)
  })

  it('conclui só com item, com Cliente no FIADO e com as parcelas cobrindo o total', () => {
    expect(() => concluir(nova(), agora)).toThrow('não tem item')
    const venda = adicionarItem(nova(), item({ precoUnitario: 10 }))
    expect(() => concluir(venda, agora)).toThrow('faltam')
    const fiado = registrarPagamento(venda, parcela({ forma: 'FIADO', valor: 10 }))
    expect(() => concluir(fiado, agora)).toThrow('Cliente vinculado')

    const concluida = concluir(vincularCliente(fiado, 'cliente-1'), agora)
    expect(concluida).toMatchObject({ status: 'CONCLUIDA', concluidoEm: agora, clienteId: 'cliente-1' })
    expect(() => concluir(concluida, agora)).toThrow('não conclui de novo')
    expect(() => adicionarItem(concluida, item())).toThrow('não aceita montagem')
    expect(() => registrarPagamento(concluida, parcela({ forma: 'CARTAO', valor: 1 })))
      .toThrow('não aceita montagem')
    expect(() => vincularCliente(concluida, 'cliente-2')).toThrow('não aceita montagem')

    const brinde = concluir(adicionarItem(nova(), item({ precoUnitario: 0 })), agora)
    expect(brinde.status).toBe('CONCLUIDA')
  })

  it('põe na gaveta só o dinheiro confirmado', () => {
    let venda = adicionarItem(nova(), item({ precoUnitario: 10 }))
    venda = registrarPagamento(venda, parcela({ forma: 'DINHEIRO', valor: 6, valorRecebido: 10 }))
    venda = registrarPagamento(venda, parcela({ forma: 'CARTAO', valor: 4 }))
    expect(dinheiroNaGaveta(venda)).toBe(600)
  })

  it('remonta as Vendas pelos gestos em ordem de dependência e ignora o recusado na revisão', () => {
    const gesto = (operacaoId: string, tipo: string, payload: GestoNaFila['payload'],
      dependeDe: string[], extra: Partial<GestoNaFila> = {}): GestoNaFila => ({
      operacaoId, registroId: 'venda-1', tipo, payload, dependeDe, estado: 'queued', criadoEm: agora, ...extra,
    })
    const inicio = gesto('op-1', 'venda.iniciar', { sessaoCaixaId: 'sessao-1', criadoEm: agora }, [])
    const cafe = gesto('op-2', 'venda.adicionarItem', { itemId: 'item-1', produtoId: 'produto-1',
      nome: 'Café', quantidade: 2, precoUnitario: 6.25, desconto: 0, versaoProduto: 3 }, ['op-1'])
    const recusado = gesto('op-3', 'venda.aplicarDesconto', { valor: 1 }, ['op-2'],
      { estado: 'needs_review', resultado: { aplicada: false, detalhe: 'desconto recusado' } })
    const pagamento = gesto('op-4', 'venda.registrarPagamento', { pagamentoId: 'pagamento-1',
      forma: 'DINHEIRO', valor: 12.5, valorRecebido: 20 }, ['op-2'])
    const conclusao = gesto('op-5', 'venda.concluir', { concluidoEm: agora }, ['op-4'])

    const vendas = projetarVendas([conclusao, pagamento, recusado, cafe, inicio], 'ana')
    const venda = vendas.get('venda-1')
    expect(venda).toMatchObject({ status: 'CONCLUIDA', usuarioId: 'ana', descontoCentavos: 0, versao: 3 })
    expect(total(venda!)).toBe(1250)
    expect(venda!.parcelas[0].trocoCentavos).toBe(750)
  })
})
