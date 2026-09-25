/** O maior valor que as colunas de dinheiro guardam, numeric(12,2), em centavos. */
export const MAIOR_VALOR_EM_CENTAVOS = 999_999_999_999

/** Os centavos inteiros do valor, ou nulo se ele tem mais de duas casas ou passa do que a coluna guarda. */
function emCentavos(valor: number): number | null {
  const resultado = Math.round(valor * 100)
  if (!Number.isFinite(valor) || !Number.isSafeInteger(resultado)
    || Math.abs(resultado / 100 - valor) > 1e-8 || Math.abs(resultado) > MAIOR_VALOR_EM_CENTAVOS) {
    return null
  }
  return resultado
}

/**
 * Converte em centavos inteiros um valor digitado ou lançado, que nunca é negativo. As contas
 * feitas no dispositivo somam e subtraem centavos, nunca frações em ponto flutuante, para que o
 * troco e o esperado da gaveta saiam iguais aos do servidor, que conta em decimal.
 */
export function centavos(valor: number, campo: string): number {
  const resultado = emCentavos(valor)
  if (resultado === null || valor < 0) {
    throw new Error(`${campo} deve ser um valor não negativo com até duas casas decimais.`)
  }
  return resultado
}

/**
 * Converte o saldo esperado da gaveta, que pode ser negativo. O servidor o aceita abaixo de zero
 * quando o estorno de um cancelamento devolve dinheiro que uma sangria já tinha tirado, e é um
 * suprimento que o corrige; recusar o saldo aqui impediria justamente essa correção sem rede.
 */
export function centavosDoSaldo(valor: number): number {
  const resultado = emCentavos(valor)
  if (resultado === null) throw new Error('O saldo esperado deve ter até duas casas decimais.')
  return resultado
}

export function reais(valorEmCentavos: number): number {
  return valorEmCentavos / 100
}
