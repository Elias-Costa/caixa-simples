/*
 * As regras da raiz Venda do servidor, conferidas no dispositivo antes de cada gesto ir para a fila.
 *
 * Sem rede ninguém mais confere a Venda: o gesto gravado é o que o servidor vai receber, e ele
 * recusaria na sincronização o que a raiz dele não aceita. Por isso cada operação daqui aplica a
 * mesma regra da raiz e devolve a Venda nova sem tocar na anterior, e uma recusa não deixa rastro.
 * As guardas que dependem de quem opera, do caixa e do cadastro ficam em vendaLocal, do mesmo modo
 * que no servidor ficam no caso de uso.
 *
 * O dinheiro é contado em centavos inteiros e a quantidade em milésimos, e o valor de cada item é
 * arredondado no próprio item, como o servidor faz, para que o total do comprovante local feche com
 * o que o servidor vai calcular.
 */
import type { FormaPagamento } from '../api/vendas'
import { centavos, MAIOR_VALOR_EM_CENTAVOS } from './dinheiro'
import { gestoAplicavel, ordenarPorDependencia, type GestoNaFila } from './fila'

/** Pix não entra na fila: a cobrança depende da confirmação do provedor, com o cliente presente. */
export const PIX_FORA_DA_FILA =
  'Pix não entra na Venda registrada sem rede: a cobrança depende da confirmação do provedor.'

export type FormaSemRede = Exclude<FormaPagamento, 'PIX'>

export type ItemNoDispositivo = Readonly<{
  id: string
  produtoId: string
  nome: string
  milesimos: number
  precoCentavos: number
  descontoCentavos: number
}>

export type ParcelaNoDispositivo = Readonly<{
  id: string
  forma: FormaSemRede
  valorCentavos: number
  status: 'CONFIRMADO' | 'PENDENTE'
  trocoCentavos: number
}>

export type VendaNoDispositivo = Readonly<{
  id: string
  sessaoCaixaId: string
  usuarioId: string
  clienteId: string | null
  status: 'ABERTA' | 'CONCLUIDA'
  criadoEm: string
  concluidoEm: string | null
  descontoCentavos: number
  itens: readonly ItemNoDispositivo[]
  parcelas: readonly ParcelaNoDispositivo[]
  /** Zero no início e mais um a cada gesto aceito: é a versão que o gesto seguinte leu. */
  versao: number
}>

/** O que cada gesto grava na fila, em reais, como a API recebe. */
export type DadosDoInicio = { sessaoCaixaId: string; criadoEm: string }
export type DadosDoItem = {
  itemId: string
  produtoId: string
  nome: string
  quantidade: number
  precoUnitario: number
  desconto: number
  versaoProduto: number | null
}
export type DadosDaRemocao = { itemId: string }
export type DadosDoDesconto = { valor: number }
export type DadosDoVinculo = { clienteId: string }
export type DadosDaParcela = {
  pagamentoId: string
  forma: FormaPagamento
  valor: number
  valorRecebido: number | null
}
export type DadosDaConclusao = { concluidoEm: string }

/** O maior valor de numeric(12,3), a coluna da quantidade, em milésimos. */
const MAIOR_QUANTIDADE_EM_MILESIMOS = 999_999_999_999

const moeda = new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL' })

function emReais(valorEmCentavos: number): string {
  return moeda.format(valorEmCentavos / 100)
}

function milesimos(quantidade: number): number {
  const resultado = Math.round(quantidade * 1000)
  if (!Number.isFinite(quantidade) || resultado <= 0 || Math.abs(resultado / 1000 - quantidade) > 1e-9
    || resultado > MAIOR_QUANTIDADE_EM_MILESIMOS) {
    throw new Error('A quantidade deve ser positiva, com até três casas decimais.')
  }
  return resultado
}

/**
 * Quantidade vezes preço, arredondado para centavos no próprio item com HALF_UP, como o servidor
 * faz. Milésimos vezes centavos dá milésimos de centavo, e esse produto pode passar do maior
 * inteiro exato do JavaScript; por isso a conta é feita em BigInt.
 */
export function valorBruto(item: ItemNoDispositivo): number {
  const milesimosDeCentavo = BigInt(item.milesimos) * BigInt(item.precoCentavos)
  return Number((milesimosDeCentavo + 500n) / 1000n)
}

export function subtotal(item: ItemNoDispositivo): number {
  return valorBruto(item) - item.descontoCentavos
}

export function somaDosItens(venda: VendaNoDispositivo): number {
  return venda.itens.reduce((soma, item) => soma + subtotal(item), 0)
}

export function total(venda: VendaNoDispositivo): number {
  return somaDosItens(venda) - venda.descontoCentavos
}

/**
 * O que as parcelas já cobrem. Sem rede toda parcela conta para a conclusão: dinheiro e cartão
 * nascem confirmados e o FIADO pendente cobre a parte dele (RF33). Pix, a única forma que espera um
 * provedor, não entra na fila.
 */
export function lancado(venda: VendaNoDispositivo): number {
  return venda.parcelas.reduce((soma, parcela) => soma + parcela.valorCentavos, 0)
}

export function faltaPagar(venda: VendaNoDispositivo): number {
  return total(venda) - lancado(venda)
}

/** O que a conclusão põe na gaveta: só o dinheiro em espécie confirmado, como no servidor. */
export function dinheiroNaGaveta(venda: VendaNoDispositivo): number {
  return venda.parcelas
    .filter((parcela) => parcela.forma === 'DINHEIRO' && parcela.status === 'CONFIRMADO')
    .reduce((soma, parcela) => soma + parcela.valorCentavos, 0)
}

function avancar(venda: VendaNoDispositivo, mudancas: Partial<VendaNoDispositivo>): VendaNoDispositivo {
  return { ...venda, ...mudancas, versao: venda.versao + 1 }
}

function exigirAberta(venda: VendaNoDispositivo): void {
  if (venda.status !== 'ABERTA') {
    throw new Error(`A Venda está ${venda.status} e não aceita montagem nem pagamento.`)
  }
}

/** Parcela lançada não se desfaz, então nenhuma montagem pode deixar o total abaixo dela. */
function exigirTotalNaoAbaixoDoLancado(venda: VendaNoDispositivo, totalResultante: number,
  operacao: string): void {
  const jaLancado = lancado(venda)
  if (totalResultante < jaLancado) {
    throw new Error(`${operacao} deixaria o total da venda em ${emReais(totalResultante)}, abaixo dos `
      + `${emReais(jaLancado)} já lançados em pagamento. Parcela lançada não se desfaz.`)
  }
}

export function abrirVenda(dados: DadosDoInicio & { id: string; usuarioId: string }): VendaNoDispositivo {
  return {
    id: dados.id, sessaoCaixaId: dados.sessaoCaixaId, usuarioId: dados.usuarioId, clienteId: null,
    status: 'ABERTA', criadoEm: dados.criadoEm, concluidoEm: null, descontoCentavos: 0,
    itens: [], parcelas: [], versao: 0,
  }
}

export function adicionarItem(venda: VendaNoDispositivo, dados: DadosDoItem): VendaNoDispositivo {
  exigirAberta(venda)
  const item: ItemNoDispositivo = {
    id: dados.itemId,
    produtoId: dados.produtoId,
    nome: dados.nome,
    milesimos: milesimos(dados.quantidade),
    precoCentavos: centavos(dados.precoUnitario, 'Preço unitário'),
    descontoCentavos: centavos(dados.desconto, 'Desconto do item'),
  }
  const bruto = valorBruto(item)
  if (!Number.isSafeInteger(bruto) || bruto > MAIOR_VALOR_EM_CENTAVOS) {
    throw new Error('O valor do item passa do maior valor que o sistema guarda.')
  }
  if (item.descontoCentavos > bruto) {
    // Igual ao bruto passa: o item vira cortesia e vale zero.
    throw new Error(`O desconto de ${emReais(item.descontoCentavos)} é maior que o valor do item, `
      + `${emReais(bruto)}. Brinde é preço zero, não desconto acima do valor.`)
  }
  return avancar(venda, { itens: [...venda.itens, item] })
}

export function removerItem(venda: VendaNoDispositivo, itemId: string): VendaNoDispositivo {
  exigirAberta(venda)
  const item = venda.itens.find((candidato) => candidato.id === itemId)
  if (!item) throw new Error('O item não está nesta Venda.')
  const somaSemOItem = somaDosItens(venda) - subtotal(item)
  const totalSemOItem = somaSemOItem - venda.descontoCentavos
  if (totalSemOItem < 0) {
    throw new Error(`Remover o item deixaria o desconto da venda, ${emReais(venda.descontoCentavos)}, `
      + `maior que a soma dos itens restantes, ${emReais(somaSemOItem)}. Reduza o desconto antes de `
      + 'remover o item.')
  }
  exigirTotalNaoAbaixoDoLancado(venda, totalSemOItem, 'Remover o item')
  return avancar(venda, { itens: venda.itens.filter((candidato) => candidato.id !== itemId) })
}

/** Substitui o desconto anterior, não acumula; zero o tira. */
export function aplicarDesconto(venda: VendaNoDispositivo, valor: number): VendaNoDispositivo {
  exigirAberta(venda)
  const desconto = centavos(valor, 'Desconto da venda')
  const soma = somaDosItens(venda)
  if (desconto > soma) {
    throw new Error(`O desconto de ${emReais(desconto)} é maior que a soma dos itens, ${emReais(soma)}. `
      + 'O total da venda não fica negativo.')
  }
  exigirTotalNaoAbaixoDoLancado(venda, soma - desconto, 'Aplicar o desconto')
  return avancar(venda, { descontoCentavos: desconto })
}

export function vincularCliente(venda: VendaNoDispositivo, clienteId: string): VendaNoDispositivo {
  exigirAberta(venda)
  if (!clienteId) throw new Error('Informe o Cliente.')
  return avancar(venda, { clienteId })
}

/**
 * Lança uma parcela. Não conclui a Venda, mesmo quando fecha a conta: quem conclui é concluir.
 * O troco sai do que o cliente entregou menos o valor da parcela, e só o dinheiro devolve troco.
 */
export function registrarPagamento(venda: VendaNoDispositivo, dados: DadosDaParcela): VendaNoDispositivo {
  exigirAberta(venda)
  if (dados.forma === 'PIX') throw new Error(PIX_FORA_DA_FILA)
  const forma: FormaSemRede = dados.forma
  const valor = centavos(dados.valor, 'Valor da parcela')
  if (valor === 0) throw new Error('O valor da parcela tem de ser positivo.')
  const valorRecebido = dados.valorRecebido ?? null

  let trocoCentavos = 0
  if (forma === 'DINHEIRO') {
    if (valorRecebido === null) {
      throw new Error('Informe o valor recebido em dinheiro, mesmo quando o cliente paga o valor exato.')
    }
    const recebido = centavos(valorRecebido, 'Valor recebido')
    if (recebido < valor) {
      throw new Error(`O valor recebido em dinheiro, ${emReais(recebido)}, é menor que o valor da `
        + `parcela, ${emReais(valor)}.`)
    }
    trocoCentavos = recebido - valor
  } else if (valorRecebido !== null) {
    throw new Error(`${forma === 'CARTAO' ? 'Cartão' : 'Fiado'} não devolve troco e não aceita valor `
      + 'recebido em espécie.')
  }

  const falta = faltaPagar(venda)
  if (valor > falta) {
    throw new Error(`A parcela de ${emReais(valor)} é maior que o que falta pagar, ${emReais(falta)}.`)
  }
  if (forma === 'FIADO' && venda.parcelas.some((parcela) => parcela.forma === 'FIADO')) {
    throw new Error('A Venda aceita uma única parcela FIADO.')
  }
  const parcela: ParcelaNoDispositivo = {
    id: dados.pagamentoId, forma, valorCentavos: valor,
    status: forma === 'FIADO' ? 'PENDENTE' : 'CONFIRMADO', trocoCentavos,
  }
  return avancar(venda, { parcelas: [...venda.parcelas, parcela] })
}

export function concluir(venda: VendaNoDispositivo, concluidoEm: string): VendaNoDispositivo {
  if (venda.status !== 'ABERTA') throw new Error(`A Venda está ${venda.status} e não conclui de novo.`)
  if (venda.itens.length === 0) {
    throw new Error('A Venda não tem item e não conclui: venda de nada não é venda.')
  }
  if (venda.parcelas.some((parcela) => parcela.forma === 'FIADO') && !venda.clienteId) {
    throw new Error('Venda com FIADO exige Cliente vinculado.')
  }
  const falta = faltaPagar(venda)
  if (falta !== 0) {
    throw new Error(`A Venda tem ${emReais(lancado(venda))} em parcelas para um total de `
      + `${emReais(total(venda))}; faltam ${emReais(falta)}.`)
  }
  return avancar(venda, { status: 'CONCLUIDA', concluidoEm })
}

/**
 * Remonta as Vendas do dispositivo a partir dos gestos, reaplicando as mesmas operações que os
 * conferiram antes de gravar, na ordem das dependências. O gesto recusado na revisão não entra.
 * O usuário vem de quem está autenticado, porque a fila já é dele e o gesto não o repete.
 */
export function projetarVendas(gestos: readonly GestoNaFila[], usuarioId: string): Map<string, VendaNoDispositivo> {
  const vendas = new Map<string, VendaNoDispositivo>()
  const daVenda = ordenarPorDependencia(gestos.filter((gesto) =>
    gesto.tipo.startsWith('venda.') && gestoAplicavel(gesto)))
  for (const gesto of daVenda) {
    if (gesto.tipo === 'venda.iniciar') {
      const dados = gesto.payload as DadosDoInicio
      vendas.set(gesto.registroId, abrirVenda({
        id: gesto.registroId, usuarioId, sessaoCaixaId: dados.sessaoCaixaId, criadoEm: dados.criadoEm,
      }))
      continue
    }
    const atual = vendas.get(gesto.registroId)
    if (atual) vendas.set(gesto.registroId, aplicarGesto(atual, gesto))
  }
  return vendas
}

function aplicarGesto(venda: VendaNoDispositivo, gesto: GestoNaFila): VendaNoDispositivo {
  switch (gesto.tipo) {
    case 'venda.adicionarItem': return adicionarItem(venda, gesto.payload as DadosDoItem)
    case 'venda.removerItem': return removerItem(venda, (gesto.payload as DadosDaRemocao).itemId)
    case 'venda.aplicarDesconto': return aplicarDesconto(venda, (gesto.payload as DadosDoDesconto).valor)
    case 'venda.vincularCliente': return vincularCliente(venda, (gesto.payload as DadosDoVinculo).clienteId)
    case 'venda.registrarPagamento': return registrarPagamento(venda, gesto.payload as DadosDaParcela)
    case 'venda.concluir': return concluir(venda, (gesto.payload as DadosDaConclusao).concluidoEm)
    default: throw new Error(`Gesto de Venda desconhecido: ${gesto.tipo}`)
  }
}
