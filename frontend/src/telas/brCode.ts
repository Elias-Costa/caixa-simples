/*
 * O BR Code estático do Pix lançado à mão, montado no aparelho, sem rede: a chave da Conta, o nome
 * do negócio, a cidade e o valor, no formato que todo aplicativo de banco lê.
 *
 * O QR não confirma nada. Ninguém avisa o sistema quando o cliente paga, e a parcela só é lançada
 * quando o operador vê o dinheiro no aplicativo do banco. O identificador da transação vai como
 * três asteriscos, o valor do QR estático sem identificador.
 */

/** Os limites do BR Code para o nome e a cidade do recebedor. */
const TAMANHO_DO_NOME = 25
const TAMANHO_DA_CIDADE = 15

/** Um campo do BR Code: o identificador, o tamanho com dois dígitos e o valor. */
function campo(id: string, valor: string): string {
  return id + String(valor.length).padStart(2, '0') + valor
}

/**
 * O nome e a cidade como o BR Code os aceita: sem acento, em maiúsculas, só com os caracteres
 * imprimíveis do ASCII e cortados no limite. Vazio quando não sobra nada do texto.
 */
function textoDoQr(texto: string, limite: number): string {
  // Decomposto, o acento vira uma marca à parte da letra, e sai sozinho.
  const semAcento = texto.normalize('NFD').replace(/\p{M}/gu, '')
  const imprimivel = semAcento.toUpperCase().replace(/[^ -~]/g, '').replace(/\s+/g, ' ').trim()
  return imprimivel.slice(0, limite).trim()
}

/**
 * O CRC16 do BR Code, o CCITT com polinômio 1021 e valor inicial FFFF, sobre o texto inteiro até o
 * identificador e o tamanho do próprio campo do CRC, em quatro dígitos hexadecimais maiúsculos.
 */
export function crc16(texto: string): string {
  let crc = 0xffff
  for (const byte of new TextEncoder().encode(texto)) {
    crc ^= byte << 8
    for (let bit = 0; bit < 8; bit++) {
      crc = crc & 0x8000 ? (crc << 1) ^ 0x1021 : crc << 1
      crc &= 0xffff
    }
  }
  return crc.toString(16).toUpperCase().padStart(4, '0')
}

/**
 * O texto do QR estático, ou nulo quando ele não se monta: valor que não é positivo, ou nome ou
 * cidade sem nenhum caractere que o BR Code aceite.
 *
 * @param chave a chave como o servidor a guardou, já na forma que o Pix espera
 * @param valor em reais, com até duas casas
 */
export function montarBrCode(chave: string, nome: string, cidade: string, valor: number): string | null {
  const nomeNoQr = textoDoQr(nome, TAMANHO_DO_NOME)
  const cidadeNoQr = textoDoQr(cidade, TAMANHO_DA_CIDADE)
  const centavos = Math.round(valor * 100)
  if (!nomeNoQr || !cidadeNoQr || !Number.isFinite(centavos) || centavos <= 0) return null
  const semCrc = campo('00', '01')
    + campo('26', campo('00', 'br.gov.bcb.pix') + campo('01', chave))
    + campo('52', '0000')
    + campo('53', '986')
    + campo('54', (centavos / 100).toFixed(2))
    + campo('58', 'BR')
    + campo('59', nomeNoQr)
    + campo('60', cidadeNoQr)
    + campo('62', campo('05', '***'))
    + '6304'
  return semCrc + crc16(semCrc)
}
