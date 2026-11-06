/*
 * O que a fila diz de cada registro, como a pessoa o reconhece no balcão, e não gesto por gesto: a
 * venda, o movimento de caixa, o cliente e o produto. O agrupamento é só da tela: o servidor continua
 * gravando e conferindo por operação.
 */
import type { RevisaoDaConta, RevisoesDaConta } from '../api/sincronizacao'
import type { FormaPagamento, ValorDaForma } from '../api/vendas'
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

/**
 * De que registro o gesto faz parte. Todo gesto de um caixa leva o id dele, e por isso o caixa não
 * agrupa: cada abertura, sangria, suprimento e fechamento é um registro, com a própria linha.
 */
export function chaveDoRegistro(gesto: Pick<GestoNaFila, 'tipo' | 'registroId' | 'operacaoId'>): string {
  return gesto.tipo.startsWith('caixa.') ? gesto.operacaoId : gesto.registroId
}

/**
 * Onde o registro está. O que ainda tem gesto sem resultado fica por enviar mesmo que outro gesto dele
 * já esteja em revisão: conferir é do registro inteiro, e ainda falta saber o resto.
 */
export type SituacaoDoRegistro = 'por-enviar' | 'em-revisao' | 'conferido' | 'enviado'

export type RegistroDaFila = Readonly<{
  chave: string
  /** Todos os gestos dele, na ordem em que foram feitos, inclusive o que não coube na remontagem da venda. */
  gestos: readonly GestoNaFila[]
  /** O início da venda, quando há, ou o primeiro gesto. */
  criadoEm: string
  situacao: SituacaoDoRegistro
  recusado: boolean
  /** O motivo do primeiro recusado, porque os seguintes só o seguiram; sem recusa, o que o servidor pede para conferir. */
  motivo?: string
  /** Só na venda. Sem o início ela não tem total, porque o que falta não se sabe. */
  venda?: Readonly<{ totalCentavos?: number; formas: readonly ValorDaForma[] }>
}>

function situacaoDoRegistro(gestos: readonly GestoNaFila[]): SituacaoDoRegistro {
  if (gestos.some(gestoPendente)) return 'por-enviar'
  const emRevisao = gestos.filter((gesto) => gesto.estado === 'needs_review')
  if (emRevisao.length === 0) return 'enviado'
  return emRevisao.some((gesto) => !gesto.conferidoEm) ? 'em-revisao' : 'conferido'
}

function motivoDo(gestos: readonly GestoNaFila[]): string | undefined {
  const recusado = gestos.find(gestoRecusado)
  if (recusado) return recusado.resultado?.detalhe
  // O relógio adiantado repete a mesma frase em todo gesto da venda, e a conclusão com o estoque negativo
  // a traz junto com a dela: a frase que outra já contém não se repete.
  const detalhes = [...new Set(gestos.filter((gesto) => gesto.estado === 'needs_review')
    .map((gesto) => gesto.resultado?.detalhe).filter((detalhe): detalhe is string => !!detalhe))]
  const semRepetir = detalhes.filter((detalhe) => !detalhes.some((outro) => outro !== detalhe && outro.includes(detalhe)))
  return semRepetir.length ? semRepetir.join(' ') : undefined
}

function formasDosPagamentos(gestos: readonly GestoNaFila[]): ValorDaForma[] {
  const parcelas = gestos.filter((gesto) => gesto.tipo === 'venda.registrarPagamento').map((gesto) => {
    const { forma, valor } = gesto.payload as { forma: FormaPagamento; valor: number }
    return { forma, status: 'CONFIRMADO', centavos: Math.round(valor * 100) }
  })
  return raiz.formasDasParcelas(parcelas)
}

/**
 * A fila agrupada, um registro por linha, dos mais antigos para os mais novos. A venda sem o gesto de
 * início também entra, sem total: a revisão antiga e o que veio depois de uma recusa não se perdem.
 */
export function registrosDaFila(gestos: readonly GestoNaFila[], usuarioId: string): RegistroDaFila[] {
  const vendas = new Map(vendasDaFila(gestos, usuarioId).map((venda) => [venda.vendaId, venda]))
  const porRegistro = new Map<string, GestoNaFila[]>()
  for (const gesto of gestos) {
    const chave = chaveDoRegistro(gesto)
    porRegistro.set(chave, [...(porRegistro.get(chave) ?? []), gesto])
  }
  return [...porRegistro].map(([chave, doRegistro]): RegistroDaFila => {
    const ordenados = ordenarPorDependencia(doRegistro)
    const primeiro = ordenados[0]
    const daVenda = primeiro.tipo.startsWith('venda.')
    const venda = daVenda ? vendas.get(primeiro.registroId) : undefined
    return {
      chave,
      gestos: ordenados,
      criadoEm: venda?.criadoEm ?? primeiro.criadoEm,
      situacao: situacaoDoRegistro(ordenados),
      recusado: ordenados.some(gestoRecusado),
      motivo: motivoDo(ordenados),
      venda: !daVenda ? undefined
        : venda ? { totalCentavos: venda.totalCentavos, formas: venda.formas }
          : { formas: formasDosPagamentos(ordenados) },
    }
  }).sort((a, b) => a.criadoEm.localeCompare(b.criadoEm))
}

/** A revisão como o gesto que o aparelho de quem operou gravou, para a lista da Conta falar como a fila. */
function revisaoComoGesto(revisao: RevisaoDaConta): GestoNaFila {
  return {
    operacaoId: revisao.operacaoId,
    registroId: revisao.registroId,
    tipo: revisao.tipo,
    payload: revisao.payload,
    dependeDe: [],
    estado: 'needs_review',
    criadoEm: revisao.criadaEm,
    resultado: { aplicada: revisao.resultado === 'APLICADA_COM_REVISAO', detalhe: revisao.detalhe },
    conferidoEm: revisao.conferidaEm,
  }
}

/**
 * A lista traz só os gestos com revisão, e um gesto conferido em outro dia já saiu dela: o total só vale
 * quando o que ela traz da venda fecha a conta.
 */
function semTotalQueNaoFecha(registro: RegistroDaFila): RegistroDaFila {
  const venda = registro.venda
  if (venda?.totalCentavos === undefined || !registro.gestos.some((gesto) => gesto.tipo === 'venda.concluir')) {
    return registro
  }
  const pago = venda.formas.reduce((soma, forma) => soma + Math.round(forma.valor * 100), 0)
  return pago === venda.totalCentavos ? registro : { ...registro, venda: { formas: venda.formas } }
}

/**
 * As revisões da Conta agrupadas como a fila, por registro. A venda conferida pela metade tem gestos
 * nas duas listas e continua sendo uma linha só, em revisão, dizendo o que falta. A dependência entre os
 * gestos não vem na lista: a ordem é a do relógio do aparelho que os fez.
 */
export function registrosDaConta({ pendentes, conferidas }: RevisoesDaConta, usuarioId: string): RegistroDaFila[] {
  const semRepetir = new Map([...conferidas, ...pendentes].map((revisao) => [revisao.operacaoId, revisao]))
  const gestos = [...semRepetir.values()]
    .sort((a, b) => Date.parse(a.criadaEm) - Date.parse(b.criadaEm))
    .map(revisaoComoGesto)
  return registrosDaFila(gestos, usuarioId).map(semTotalQueNaoFecha)
}
