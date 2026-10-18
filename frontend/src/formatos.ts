import { FUSO_DO_BALCAO } from './dataDoBalcao'

/** Dinheiro como o balcão lê, com separador de milhar e vírgula, igual em toda tela e comprovante. */
export const moeda = new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL' })

/**
 * Data e hora no fuso do balcão. Um aparelho com outro fuso mostraria a venda num horário que não
 * bate com o extrato do caixa nem com o relatório do mesmo dia.
 */
export const dataHora = new Intl.DateTimeFormat('pt-BR', {
  dateStyle: 'short', timeStyle: 'short', timeZone: FUSO_DO_BALCAO,
})

/** O valor como se digita num campo: vírgula decimal e sem o símbolo, que o campo aceita de volta. */
export function valorNoCampo(valor: number): string {
  return valor.toFixed(2).replace('.', ',')
}
