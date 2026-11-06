/*
 * O que a fila diz de cada registro, como a pessoa o reconhece no balcão, e não gesto por gesto.
 * Cobre a Venda, que é o que o balcão avisa quando volta recusada.
 */
import type { ValorDaForma } from '../api/vendas'
import { gestoPendente, gestoRecusado, ordenarPorDependencia, type GestoNaFila } from './fila'
import * as raiz from './raizDaVenda'

/** O pior que aconteceu com a venda; enquanto algum gesto não teve resultado, ainda não se sabe. */
export type SituacaoDaVenda = 'por-enviar' | 'recusada' | 'em-revisao' | 'enviada'

export type VendaDaFila = Readonly<{
  vendaId: string
  /** O início da venda, a mesma hora que a lista do caixa mostra. */
  criadoEm: string
  totalCentavos: number
  formas: readonly ValorDaForma[]
  /** O dinheiro em espécie que a venda recebeu no balcão, com o troco já descontado. */
  dinheiroCentavos: number
  situacao: SituacaoDaVenda
  /** Por que o primeiro gesto recusado parou, como o servidor escreveu; os seguintes só o seguiram. */
  motivo?: string
  /** O servidor recusou já o início: a venda não existe lá. Sem isso, ela existe e não concluiu. */
  recusadaNoInicio: boolean
  /** Quem operou já conferiu neste aparelho tudo o que está em revisão nela. */
  conferida: boolean
}>

function situacaoDe(gestos: readonly GestoNaFila[]): SituacaoDaVenda {
  if (gestos.some(gestoPendente)) return 'por-enviar'
  if (gestos.some(gestoRecusado)) return 'recusada'
  return gestos.some((gesto) => gesto.estado === 'needs_review') ? 'em-revisao' : 'enviada'
}

/**
 * As vendas nascidas neste aparelho, das mais antigas para as mais novas, como o balcão as registrou.
 * A que já não tem o gesto de início na fila fica de fora: sem ele, não há hora nem total para dizer.
 */
export function vendasDaFila(gestos: readonly GestoNaFila[], usuarioId: string): VendaDaFila[] {
  const registradas = raiz.remontarComoRegistrada(gestos, usuarioId)
  return [...registradas.values()]
    .map((venda): VendaDaFila => {
      const daVenda = ordenarPorDependencia(gestos.filter((gesto) =>
        gesto.tipo.startsWith('venda.') && gesto.registroId === venda.id))
      const primeiroRecusado = daVenda.find(gestoRecusado)
      return {
        vendaId: venda.id,
        criadoEm: venda.criadoEm,
        totalCentavos: raiz.total(venda),
        formas: raiz.formasDasParcelas(venda.parcelas.map((parcela) =>
          ({ forma: parcela.forma, status: parcela.status, centavos: parcela.valorCentavos }))),
        dinheiroCentavos: raiz.dinheiroNaGaveta(venda),
        situacao: situacaoDe(daVenda),
        motivo: primeiroRecusado?.resultado?.detalhe,
        recusadaNoInicio: primeiroRecusado?.tipo === 'venda.iniciar',
        conferida: daVenda.every((gesto) => gesto.estado !== 'needs_review' || !!gesto.conferidoEm),
      }
    })
    .sort((a, b) => a.criadoEm.localeCompare(b.criadoEm))
}
