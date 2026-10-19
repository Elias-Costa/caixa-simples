import { describe, expect, it } from 'vitest'
import { crc16, montarBrCode } from './brCode'

/** O exemplo de QR estático do manual de padrões do Pix, até o campo do CRC; o CRC publicado é 1D3D. */
const EXEMPLO_DO_MANUAL = '00020126580014br.gov.bcb.pix0136123e4567-e12b-12d1-a456-426655440000'
  + '5204000053039865802BR5913Fulano de Tal6008BRASILIA62070503***6304'

const ALEATORIA = '123e4567-e12b-12d1-a456-426655440000'

describe('BR Code do Pix manual', () => {
  it('calcula o CRC do exemplo do manual do Banco Central', () => {
    expect(crc16(EXEMPLO_DO_MANUAL)).toBe('1D3D')
  })

  it('monta os campos na ordem do QR estático, com o valor, os limites do nome e da cidade e o CRC', () => {
    expect(montarBrCode(ALEATORIA, 'Padaria São João do Recôncavo Baiano', 'Vitória da Conquista', 12.5))
      .toBe('000201'
        + '26580014br.gov.bcb.pix0136' + ALEATORIA
        + '52040000' + '5303986' + '540512.50' + '5802BR'
        + '5925PADARIA SAO JOAO DO RECON' + '6015VITORIA DA CONQ'
        + '62070503***' + '6304BCCE')
  })

  it('tira o acento, mantém o que o ASCII aceita e não deixa espaço no fim do corte', () => {
    const codigo = montarBrCode(ALEATORIA, 'Casa do Pão de Queijo da Ana', 'Ilhéus', 0.1 + 0.2)
    expect(codigo).toContain('5924CASA DO PAO DE QUEIJO DA6006ILHEUS')
    expect(codigo).toContain('54040.30')
    expect(montarBrCode(ALEATORIA, 'Açaí & Cia', 'Salvador', 5)).toContain('5910ACAI & CIA')
  })

  it('confere o próprio CRC, com a chave de e-mail mais longa que o Pix aceita', () => {
    const email = 'a'.repeat(65) + '@exemplo.com'
    const codigo = montarBrCode(email, 'Loja', 'Salvador', 1)!
    expect(codigo).toContain('2699' + '0014br.gov.bcb.pix' + '0177' + email)
    expect(crc16(codigo.slice(0, -4))).toBe(codigo.slice(-4))
  })

  it('não monta o QR sem valor positivo, nem com nome ou cidade sem caractere aceito', () => {
    expect(montarBrCode(ALEATORIA, 'Loja', 'Salvador', 0)).toBeNull()
    expect(montarBrCode(ALEATORIA, 'Loja', 'Salvador', -1)).toBeNull()
    expect(montarBrCode(ALEATORIA, 'Loja', 'Salvador', Number.NaN)).toBeNull()
    expect(montarBrCode(ALEATORIA, '☕', 'Salvador', 1)).toBeNull()
    expect(montarBrCode(ALEATORIA, 'Loja', '   ', 1)).toBeNull()
  })
})
