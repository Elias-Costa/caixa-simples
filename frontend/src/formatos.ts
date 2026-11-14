import { diaNoBalcao, FUSO_DO_BALCAO } from './dataDoBalcao'

/** Dinheiro como o balcão lê, com separador de milhar e vírgula, igual em toda tela e comprovante. */
export const moeda = new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL' })

/**
 * Data e hora no fuso do balcão. Um aparelho com outro fuso mostraria a venda num horário que não
 * bate com o extrato do caixa nem com o relatório do mesmo dia.
 */
export const dataHora = new Intl.DateTimeFormat('pt-BR', {
  dateStyle: 'short', timeStyle: 'short', timeZone: FUSO_DO_BALCAO,
})

/** Só a hora, no mesmo fuso: na lista de um caixa, a data se repetiria em toda linha. */
export const hora = new Intl.DateTimeFormat('pt-BR', { timeStyle: 'short', timeZone: FUSO_DO_BALCAO })

/** O dia e o mês, sem o ano, para dizer de que dia é uma hora numa lista curta. */
export const diaEMes = new Intl.DateTimeFormat('pt-BR', { day: '2-digit', month: '2-digit', timeZone: FUSO_DO_BALCAO })

/**
 * A hora de um instante, com o dia e o mês antes dela quando ele não é do dia dado. Num caixa aberto
 * desde a véspera, só a hora misturaria as linhas de um dia com as do outro.
 */
export function horaNoDia(instante: Date, dia: string): string {
  return diaNoBalcao(instante) === dia ? hora.format(instante)
    : `${diaEMes.format(instante)} ${hora.format(instante)}`
}

/** A quantidade com vírgula e até três casas, as do que se vende por peso, sem zeros sobrando. */
export const quantidade = new Intl.NumberFormat('pt-BR', { maximumFractionDigits: 3 })

/** Quantos registros uma lista tem, com o ponto de milhar: 2.017 itens. */
export const contagem = new Intl.NumberFormat('pt-BR', { maximumFractionDigits: 0 })

/**
 * A quantidade com a unidade do cadastro, como 0,35 kg. O item sem unidade, como o serviço, mostra
 * só o número.
 */
export function quantidadeComUnidade(valor: number, unidade?: string | null): string {
  return unidade ? `${quantidade.format(valor)} ${unidade}` : quantidade.format(valor)
}

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
