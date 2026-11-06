import { describe, expect, it } from 'vitest'
import type { GestoNaFila, ResultadoDoGesto } from './fila'
import { vendasDaFila } from './registrosDaFila'

const VENDA = '00000000-0000-4000-8000-000000000011'
const OUTRA = '00000000-0000-4000-8000-000000000012'
const SESSAO = '00000000-0000-4000-8000-000000000021'
const INICIO = '2026-11-04T23:42:00.000Z'
const aplicada: ResultadoDoGesto = { aplicada: true }

type Parcial = Partial<Omit<GestoNaFila, 'tipo' | 'payload'>>

let proximo = 0
function gesto(tipo: string, payload: GestoNaFila['payload'], parcial: Parcial = {}): GestoNaFila {
  proximo++
  return {
    operacaoId: `op-${proximo}`, registroId: VENDA, tipo, payload, dependeDe: [], estado: 'sent',
    criadoEm: new Date(Date.parse(INICIO) + proximo * 1000).toISOString(), resultado: aplicada, ...parcial,
  }
}

function inicio(parcial: Parcial = {}) {
  return gesto('venda.iniciar', { sessaoCaixaId: SESSAO, criadoEm: INICIO }, parcial)
}

function item(preco: number, parcial: Parcial = {}) {
  return gesto('venda.adicionarItem', { itemId: `item-${proximo}`, produtoId: 'produto-1', nome: 'Sonho',
    quantidade: 1, precoUnitario: preco, desconto: 0, versaoProduto: 1 }, parcial)
}

function dinheiro(valor: number, recebido: number, parcial: Parcial = {}) {
  return gesto('venda.registrarPagamento', { pagamentoId: `parcela-${proximo}`, forma: 'DINHEIRO', valor,
    valorRecebido: recebido }, parcial)
}

function conclusao(parcial: Parcial = {}) {
  return gesto('venda.concluir', { concluidoEm: INICIO }, parcial)
}

const recusado = (detalhe: string): Parcial => ({ estado: 'needs_review', resultado: { aplicada: false, detalhe } })

describe('as vendas da fila', () => {
  it('diz a venda recusada como o balcão a registrou: hora, total, forma, dinheiro e o motivo do primeiro recusado', () => {
    const [venda] = vendasDaFila([
      inicio(),
      item(12.4),
      dinheiro(12.4, 20, recusado('Esta venda já está cancelada e não aceita mais alteração.')),
      conclusao(recusado('A venda parou no passo anterior, que foi recusado.')),
    ], 'ana')

    expect(venda).toEqual({
      vendaId: VENDA, criadoEm: INICIO, totalCentavos: 1240, formas: [{ forma: 'DINHEIRO', valor: 12.4 }],
      dinheiroCentavos: 1240, situacao: 'recusada', recusadaNoInicio: false, conferida: false,
      motivo: 'Esta venda já está cancelada e não aceita mais alteração.',
    })
  })

  it('reconhece a recusa já no início, quando a venda não existe no servidor', () => {
    const [venda] = vendasDaFila([
      inicio(recusado('O caixa foi fechado antes de esta venda chegar ao servidor.')),
      item(7, recusado('A venda parou no passo anterior, que foi recusado.')),
    ], 'ana')

    expect(venda).toMatchObject({ situacao: 'recusada', recusadaNoInicio: true, totalCentavos: 700,
      motivo: 'O caixa foi fechado antes de esta venda chegar ao servidor.' })
  })

  it('o gesto feito depois de um recusado, que não cabe na venda como foi registrada, não derruba o resumo', () => {
    // O item recusado ficou de fora da conta quando o balcão recebeu e concluiu: remontado com ele,
    // os pagamentos não cobrem o total, e a conclusão não cabe.
    const [venda] = vendasDaFila([
      inicio(),
      item(5),
      item(3, recusado('Este produto foi inativado.')),
      dinheiro(5, 5, recusado('A venda parou no passo anterior, que foi recusado.')),
      conclusao(recusado('A venda parou no passo anterior, que foi recusado.')),
    ], 'ana')

    expect(venda).toMatchObject({ situacao: 'recusada', totalCentavos: 800, dinheiroCentavos: 500,
      motivo: 'Este produto foi inativado.' })
  })

  it('enquanto algum gesto não teve resultado, a venda ainda não é recusada', () => {
    const [venda] = vendasDaFila([
      inicio(),
      item(5, recusado('Este produto foi inativado.')),
      conclusao({ estado: 'failed', resultado: undefined, falha: 'Sem conexão' }),
    ], 'ana')

    expect(venda.situacao).toBe('por-enviar')
  })

  it('separa a aplicada com revisão e a enviada sem pendência da recusada', () => {
    const revisao: Parcial = { estado: 'needs_review', resultado: { aplicada: true, detalhe: 'Relógio' } }
    const vendas = vendasDaFila([
      inicio(),
      item(5, revisao),
      inicio({ registroId: OUTRA }),
      item(5, { registroId: OUTRA }),
    ], 'ana')

    expect(vendas.map((venda) => [venda.vendaId, venda.situacao, venda.motivo])).toEqual([
      [VENDA, 'em-revisao', undefined], [OUTRA, 'enviada', undefined],
    ])
  })

  it('só está conferida quando tudo o que está em revisão nela foi conferido', () => {
    const conferido = { conferidoEm: '2026-11-05T10:00:00.000Z' }
    const motivo = recusado('Este produto foi inativado.')
    expect(vendasDaFila([inicio(), item(5, { ...motivo, ...conferido }),
      conclusao(recusado('A venda parou no passo anterior, que foi recusado.'))], 'ana')[0].conferida).toBe(false)
    expect(vendasDaFila([inicio(), item(5, { ...motivo, ...conferido })], 'ana')[0].conferida).toBe(true)
  })

  it('a venda sem o gesto de início na fila fica de fora, sem hora nem total para dizer', () => {
    expect(vendasDaFila([item(5, recusado('Este produto foi inativado.'))], 'ana')).toEqual([])
  })

  it('vem da mais antiga para a mais nova, e ignora o que não é venda', () => {
    const vendas = vendasDaFila([
      gesto('venda.iniciar', { sessaoCaixaId: SESSAO, criadoEm: '2026-11-04T12:00:00.000Z' }, { registroId: OUTRA }),
      gesto('venda.iniciar', { sessaoCaixaId: SESSAO, criadoEm: '2026-11-04T11:00:00.000Z' }),
      gesto('caixa.sangrar', { valor: 5, motivo: 'Cofre' }, { registroId: SESSAO }),
    ], 'ana')

    expect(vendas.map((venda) => venda.vendaId)).toEqual([VENDA, OUTRA])
  })
})
