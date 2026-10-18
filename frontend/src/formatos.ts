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

/**
 * O valor digitado num campo de dinheiro, ou nulo quando o texto não é um número com até duas casas.
 * Aceita vírgula ou ponto antes dos centavos, porque o teclado decimal de um aparelho mostra um e o
 * de outro mostra o outro. Recusa o ponto de milhar: 1.500,00 seria ambíguo, e 1.500 viraria um real
 * e meio se a terceira casa fosse cortada em silêncio.
 */
export function lerDinheiro(texto: string): number | null {
  if (!/^\d+([,.]\d{1,2})?$/.test(texto)) return null
  return Number(texto.replace(',', '.'))
}
