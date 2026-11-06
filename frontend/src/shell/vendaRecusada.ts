import { diaNoBalcao } from '../dataDoBalcao'
import { diaEMes, hora, moeda } from '../formatos'
import type { VendaDaFila } from '../offline/registrosDaFila'

/** A hora da venda, com o dia quando ela é de outro dia: no caixa aberto desde ontem, só a hora engana. */
function quando(criadoEm: string, hoje: string): string {
  const instante = new Date(criadoEm)
  return diaNoBalcao(instante) === hoje ? `das ${hora.format(instante)}`
    : `de ${diaEMes.format(instante)} às ${hora.format(instante)}`
}

/**
 * O que a faixa diz da venda recusada: qual venda, pela hora e pelo valor que a pessoa reconhece, por
 * que parou, nas palavras do servidor, e se o dinheiro dela ficou fora do saldo esperado. O dinheiro só
 * entra na gaveta do servidor com a conclusão, e a venda recusada nunca concluiu. A conta da gaveta não
 * muda: quem decide o que fazer com esse dinheiro é o administrador.
 */
export function textoDaVendaRecusada(venda: VendaDaFila, hoje: string): string {
  const qual = `A venda de ${moeda.format(venda.totalCentavos / 100)} ${quando(venda.criadoEm, hoje)}, feita sem rede,`
  const partes = [
    venda.recusadaNoInicio ? `${qual} foi recusada pelo servidor.` : `${qual} não foi concluída no servidor.`,
  ]
  if (venda.motivo) partes.push(venda.motivo)
  if (venda.dinheiroCentavos > 0) {
    partes.push(`O dinheiro dela, ${moeda.format(venda.dinheiroCentavos / 100)}, está na gaveta e fora do `
      + 'saldo esperado: avise o administrador.')
  }
  return partes.join(' ')
}

/** Uma faixa por vez, porque no celular a busca do PDV já disputa a altura com o teclado. */
export function textoDasOutrasRecusadas(quantas: number): string | null {
  if (quantas === 0) return null
  return quantas === 1 ? 'Há mais uma venda recusada para conferir.' : `Há mais ${quantas} vendas recusadas para conferir.`
}
