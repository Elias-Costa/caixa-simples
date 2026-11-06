import { describe, expect, it } from 'vitest'
import type { VendaDaFila } from '../offline/registrosDaFila'
import { textoDasOutrasRecusadas, textoDaVendaRecusada } from './vendaRecusada'

const HOJE = '2026-11-04'
const venda: VendaDaFila = {
  vendaId: 'venda-1', criadoEm: '2026-11-05T02:42:00.000Z', totalCentavos: 1240, formas: [],
  dinheiroCentavos: 1240, situacao: 'recusada', recusadaNoInicio: false, conferida: false,
  motivo: 'Esta venda já está cancelada e não aceita mais alteração.',
}

/** A moeda usa espaço que não quebra entre o símbolo e o valor; o teste compara o que se lê. */
const lido = (texto: string) => texto.replaceAll(' ', ' ')

describe('o texto da venda recusada', () => {
  it('diz qual venda, o motivo, o dinheiro fora do saldo e a quem avisar', () => {
    expect(lido(textoDaVendaRecusada(venda, HOJE))).toBe('A venda de R$ 12,40 das 23:42, feita sem rede, não foi'
      + ' concluída no servidor. Esta venda já está cancelada e não aceita mais alteração. O dinheiro dela,'
      + ' R$ 12,40, está na gaveta e fora do saldo esperado: avise o administrador.')
  })

  it('na recusa do início, diz que o servidor recusou a venda', () => {
    expect(lido(textoDaVendaRecusada({
      ...venda, totalCentavos: 700, dinheiroCentavos: 700, recusadaNoInicio: true,
      motivo: 'O caixa foi fechado antes de esta venda chegar ao servidor, e ela não foi registrada.'
        + ' O administrador decide o que fazer com ela.',
    }, HOJE))).toBe('A venda de R$ 7,00 das 23:42, feita sem rede, foi recusada pelo servidor. O caixa foi fechado'
      + ' antes de esta venda chegar ao servidor, e ela não foi registrada. O administrador decide o que fazer'
      + ' com ela. O dinheiro dela, R$ 7,00, está na gaveta e fora do saldo esperado: avise o administrador.')
  })

  it('sem dinheiro na venda, não fala da gaveta', () => {
    const texto = lido(textoDaVendaRecusada({ ...venda, dinheiroCentavos: 0, motivo: 'Motivo do servidor.' }, HOJE))
    expect(texto).toBe('A venda de R$ 12,40 das 23:42, feita sem rede, não foi concluída no servidor. Motivo do servidor.')
  })

  it('a venda de outro dia leva o dia', () => {
    expect(lido(textoDaVendaRecusada(venda, '2026-11-06'))).toContain('A venda de R$ 12,40 de 04/11 às 23:42, feita')
  })
})

describe('o aviso das outras vendas recusadas', () => {
  it('só aparece com outra para conferir, no singular e no plural', () => {
    expect(textoDasOutrasRecusadas(0)).toBeNull()
    expect(textoDasOutrasRecusadas(1)).toBe('Há mais uma venda recusada para conferir.')
    expect(textoDasOutrasRecusadas(2)).toBe('Há mais 2 vendas recusadas para conferir.')
  })
})
