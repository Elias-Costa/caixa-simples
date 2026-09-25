/** O maior valor que as colunas de dinheiro guardam, numeric(12,2), em centavos. */
export const MAIOR_VALOR_EM_CENTAVOS = 999_999_999_999

/**
 * Converte reais em centavos inteiros. As contas feitas no dispositivo somam e subtraem centavos,
 * nunca frações em ponto flutuante, para que o troco e o esperado da gaveta saiam iguais aos do
 * servidor, que conta em decimal.
 */
export function centavos(valor: number, campo: string): number {
  const resultado = Math.round(valor * 100)
  if (!Number.isFinite(valor) || valor < 0 || !Number.isSafeInteger(resultado)
    || Math.abs(resultado / 100 - valor) > 1e-8 || resultado > MAIOR_VALOR_EM_CENTAVOS) {
    throw new Error(`${campo} deve ser um valor não negativo com até duas casas decimais.`)
  }
  return resultado
}

export function reais(valorEmCentavos: number): number {
  return valorEmCentavos / 100
}
