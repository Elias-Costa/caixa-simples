import { describe, expect, it } from 'vitest'
import type { RevisaoDaConta } from '../api/sincronizacao'
import type { GestoNaFila, ResultadoDoGesto } from './fila'
import { chaveDoRegistro, registrosDaConta, registrosDaFila, vendasDaFila } from './registrosDaFila'

const VENDA = '00000000-0000-4000-8000-000000000011'
const OUTRA = '00000000-0000-4000-8000-000000000012'
const SESSAO = '00000000-0000-4000-8000-000000000021'
const INICIO = '2026-11-04T23:42:00.000Z'
const aplicada: ResultadoDoGesto = { aplicada: true }

type Parcial = Partial<Omit<GestoNaFila, 'tipo' | 'payload'>>

let proximo = 0
function gesto(tipo: string, payload: GestoNaFila['payload'], parcial: Parcial = {}): GestoNaFila {
  proximo++
  return {
    operacaoId: `op-${proximo}`, registroId: VENDA, tipo, payload, dependeDe: [], estado: 'sent',
    criadoEm: new Date(Date.parse(INICIO) + proximo * 1000).toISOString(), resultado: aplicada, ...parcial,
  }
}

function inicio(parcial: Parcial = {}) {
  return gesto('venda.iniciar', { sessaoCaixaId: SESSAO, criadoEm: INICIO }, parcial)
}

function item(preco: number, parcial: Parcial = {}) {
  return gesto('venda.adicionarItem', { itemId: `item-${proximo}`, produtoId: 'produto-1', nome: 'Sonho',
    quantidade: 1, precoUnitario: preco, desconto: 0, versaoProduto: 1 }, parcial)
}

function dinheiro(valor: number, recebido: number, parcial: Parcial = {}) {
  return gesto('venda.registrarPagamento', { pagamentoId: `parcela-${proximo}`, forma: 'DINHEIRO', valor,
    valorRecebido: recebido }, parcial)
}

function conclusao(parcial: Parcial = {}) {
  return gesto('venda.concluir', { concluidoEm: INICIO }, parcial)
}

const recusado = (detalhe: string): Parcial => ({ estado: 'needs_review', resultado: { aplicada: false, detalhe } })

describe('as vendas da fila', () => {
  it('diz a venda recusada como o balcão a registrou: hora, total, forma, dinheiro e o motivo do primeiro recusado', () => {
    const [venda] = vendasDaFila([
      inicio(),
      item(12.4),
      dinheiro(12.4, 20, recusado('Esta venda já está cancelada e não aceita mais alteração.')),
      conclusao(recusado('A venda parou no passo anterior, que foi recusado.')),
    ], 'ana')

    expect(venda).toEqual({
      vendaId: VENDA, criadoEm: INICIO, totalCentavos: 1240, formas: [{ forma: 'DINHEIRO', valor: 12.4 }],
      dinheiroCentavos: 1240, situacao: 'recusada', recusadaNoInicio: false, conferida: false,
      motivo: 'Esta venda já está cancelada e não aceita mais alteração.',
    })
  })

  it('reconhece a recusa já no início, quando a venda não existe no servidor', () => {
    const [venda] = vendasDaFila([
      inicio(recusado('O caixa foi fechado antes de esta venda chegar ao servidor.')),
      item(7, recusado('A venda parou no passo anterior, que foi recusado.')),
    ], 'ana')

    expect(venda).toMatchObject({ situacao: 'recusada', recusadaNoInicio: true, totalCentavos: 700,
      motivo: 'O caixa foi fechado antes de esta venda chegar ao servidor.' })
  })

  it('o gesto feito depois de um recusado, que não cabe na venda como foi registrada, não derruba o resumo', () => {
    // O item recusado ficou de fora da conta quando o balcão recebeu e concluiu: remontado com ele,
    // os pagamentos não cobrem o total, e a conclusão não cabe.
    const [venda] = vendasDaFila([
      inicio(),
      item(5),
      item(3, recusado('Este produto foi inativado.')),
      dinheiro(5, 5, recusado('A venda parou no passo anterior, que foi recusado.')),
      conclusao(recusado('A venda parou no passo anterior, que foi recusado.')),
    ], 'ana')

    expect(venda).toMatchObject({ situacao: 'recusada', totalCentavos: 800, dinheiroCentavos: 500,
      motivo: 'Este produto foi inativado.' })
  })

  it('enquanto algum gesto não teve resultado, a venda ainda não é recusada', () => {
    const [venda] = vendasDaFila([
      inicio(),
      item(5, recusado('Este produto foi inativado.')),
      conclusao({ estado: 'failed', resultado: undefined, falha: 'Sem conexão' }),
    ], 'ana')

    expect(venda.situacao).toBe('por-enviar')
  })

  it('separa a aplicada com revisão e a enviada sem pendência da recusada', () => {
    const revisao: Parcial = { estado: 'needs_review', resultado: { aplicada: true, detalhe: 'Relógio' } }
    const vendas = vendasDaFila([
      inicio(),
      item(5, revisao),
      inicio({ registroId: OUTRA }),
      item(5, { registroId: OUTRA }),
    ], 'ana')

    expect(vendas.map((venda) => [venda.vendaId, venda.situacao, venda.motivo])).toEqual([
      [VENDA, 'em-revisao', undefined], [OUTRA, 'enviada', undefined],
    ])
  })

  it('só está conferida quando tudo o que está em revisão nela foi conferido', () => {
    const conferido = { conferidoEm: '2026-11-05T10:00:00.000Z' }
    const motivo = recusado('Este produto foi inativado.')
    expect(vendasDaFila([inicio(), item(5, { ...motivo, ...conferido }),
      conclusao(recusado('A venda parou no passo anterior, que foi recusado.'))], 'ana')[0].conferida).toBe(false)
    expect(vendasDaFila([inicio(), item(5, { ...motivo, ...conferido })], 'ana')[0].conferida).toBe(true)
  })

  it('a venda sem o gesto de início na fila fica de fora, sem hora nem total para dizer', () => {
    expect(vendasDaFila([item(5, recusado('Este produto foi inativado.'))], 'ana')).toEqual([])
  })

  it('vem da mais antiga para a mais nova, e ignora o que não é venda', () => {
    const vendas = vendasDaFila([
      gesto('venda.iniciar', { sessaoCaixaId: SESSAO, criadoEm: '2026-11-04T12:00:00.000Z' }, { registroId: OUTRA }),
      gesto('venda.iniciar', { sessaoCaixaId: SESSAO, criadoEm: '2026-11-04T11:00:00.000Z' }),
      gesto('caixa.sangrar', { valor: 5, motivo: 'Cofre' }, { registroId: SESSAO }),
    ], 'ana')

    expect(vendas.map((venda) => venda.vendaId)).toEqual([VENDA, OUTRA])
  })
})

describe('os registros da fila', () => {
  const naFila: Parcial = { estado: 'queued', resultado: undefined }

  it('agrupa a venda, o cliente e o produto pelo registro, e cada gesto do caixa é um registro', () => {
    const cliente = { registroId: '00000000-0000-4000-8000-000000000031' }
    const registros = registrosDaFila([
      inicio(naFila), item(5, naFila), conclusao(naFila),
      gesto('cliente.criar', { nome: 'Maria' }, { ...naFila, ...cliente }),
      gesto('cliente.editar', { nome: 'Maria Souza' }, { ...naFila, ...cliente }),
      gesto('caixa.sangrar', { valor: 5 }, { ...naFila, registroId: SESSAO }),
      gesto('caixa.suprir', { valor: 9 }, { ...naFila, registroId: SESSAO }),
    ], 'ana')

    expect(registros.map((registro) => [registro.gestos[0].tipo, registro.gestos.length])).toEqual([
      ['venda.iniciar', 3], ['cliente.criar', 2], ['caixa.sangrar', 1], ['caixa.suprir', 1],
    ])
  })

  it('o registro com gesto sem resultado fica por enviar, mesmo com outro gesto já em revisão', () => {
    const [registro] = registrosDaFila([
      inicio(recusado('O caixa foi fechado.')),
      item(5, { estado: 'failed', resultado: undefined, falha: 'Sem resposta do servidor.' }),
    ], 'ana')

    expect(registro.situacao).toBe('por-enviar')
  })

  it('separa o que falta conferir do que já foi conferido e do que foi enviado sem revisão', () => {
    const revisao: Parcial = { estado: 'needs_review', resultado: { aplicada: true, detalhe: 'Relógio' } }
    const situacoes = (conferidoEm?: string) => registrosDaFila(
      [inicio(), item(5, { ...revisao, conferidoEm })], 'ana').map((registro) => registro.situacao)

    expect(situacoes()).toEqual(['em-revisao'])
    expect(situacoes('2026-11-05T10:00:00.000Z')).toEqual(['conferido'])
    expect(registrosDaFila([inicio(), item(5)], 'ana').map((registro) => registro.situacao)).toEqual(['enviado'])
  })

  it('diz o motivo do primeiro recusado, ou as revisões sem repetir a frase de cada gesto', () => {
    const [recusada] = registrosDaFila([
      inicio(),
      item(5, recusado('Este produto foi inativado.')),
      conclusao(recusado('A venda parou no passo anterior, que foi recusado.')),
    ], 'ana')
    expect(recusada).toMatchObject({ recusado: true, motivo: 'Este produto foi inativado.' })

    const relogio: Parcial = { estado: 'needs_review', resultado: { aplicada: true, detalhe: 'Relógio adiantado.' } }
    const preco: Parcial = { estado: 'needs_review', resultado: { aplicada: true, detalhe: 'Preço diferente.' } }
    const [comRevisao] = registrosDaFila([inicio(relogio), item(5, relogio), item(3, preco)], 'ana')
    expect(comRevisao).toMatchObject({ recusado: false, motivo: 'Relógio adiantado. Preço diferente.' })

    // A conclusão com o estoque negativo traz a frase do relógio junto com a dela.
    const estoque: Parcial = { estado: 'needs_review',
      resultado: { aplicada: true, detalhe: 'Estoque negativo. Relógio adiantado.' } }
    const [comAsDuas] = registrosDaFila([inicio(relogio), item(5, relogio), conclusao(estoque)], 'ana')
    expect(comAsDuas.motivo).toBe('Estoque negativo. Relógio adiantado.')
  })

  it('a venda sem o início entra, sem total, com a hora do primeiro gesto e o valor do pagamento', () => {
    const pagamento = dinheiro(5, 5, recusado('Este produto foi inativado.'))
    const [registro] = registrosDaFila([
      pagamento,
      conclusao(recusado('A venda parou no passo anterior, que foi recusado.')),
    ], 'ana')

    expect(registro.criadoEm).toBe(pagamento.criadoEm)
    expect(registro.venda).toEqual({ formas: [{ forma: 'DINHEIRO', valor: 5 }] })
    expect(registro.gestos).toHaveLength(2)
  })

  it('o gesto que não cabe na venda como foi registrada continua entre os passos', () => {
    const [registro] = registrosDaFila([
      inicio(),
      item(5),
      item(3, recusado('Este produto foi inativado.')),
      dinheiro(5, 5, recusado('A venda parou no passo anterior, que foi recusado.')),
      conclusao(recusado('A venda parou no passo anterior, que foi recusado.')),
    ], 'ana')

    expect(registro.venda?.totalCentavos).toBe(800)
    expect(registro.gestos.map((um) => um.tipo)).toContain('venda.concluir')
  })

  it('sai do mais antigo para o mais novo, com a venda pela hora do início', () => {
    const registros = registrosDaFila([
      gesto('venda.iniciar', { sessaoCaixaId: SESSAO, criadoEm: '2026-11-04T12:00:00.000Z' }, naFila),
      gesto('caixa.sangrar', { valor: 5 }, { ...naFila, registroId: SESSAO, criadoEm: '2026-11-04T11:00:00.000Z' }),
    ], 'ana')

    expect(registros.map((registro) => registro.gestos[0].tipo)).toEqual(['caixa.sangrar', 'venda.iniciar'])
  })
})

describe('os registros da lista da Conta', () => {
  function revisao(parcial: GestoNaFila, extra: Partial<RevisaoDaConta> = {}): RevisaoDaConta {
    return {
      operacaoId: parcial.operacaoId, usuarioId: 'bruno', tipo: parcial.tipo, registroId: parcial.registroId,
      payload: parcial.payload, criadaEm: parcial.criadoEm, recebidaEm: parcial.criadoEm,
      resultado: 'NAO_APLICADA', detalhe: 'O caixa foi fechado.', ...extra,
    }
  }

  const vendaInteira = () => [inicio(), item(12.4), dinheiro(12.4, 20), conclusao()].map((um) => revisao(um))

  it('traz o total quando a lista traz a venda inteira e a conta fecha', () => {
    const [registro] = registrosDaConta({ pendentes: vendaInteira(), conferidas: [] }, 'ana')

    expect(registro.venda).toEqual({ totalCentavos: 1240, formas: [{ forma: 'DINHEIRO', valor: 12.4 }] })
    expect(registro.situacao).toBe('em-revisao')
    expect(registro.motivo).toBe('O caixa foi fechado.')
  })

  it('não diz o total quando o que a lista traz não fecha a conta, porque falta algum gesto', () => {
    const [comInicio, comItem, , comConclusao] = vendaInteira()
    const pagamentoMenor = revisao(dinheiro(5, 5))
    const [registro] = registrosDaConta(
      { pendentes: [comInicio, comItem, pagamentoMenor, comConclusao], conferidas: [] }, 'ana')

    expect(registro.venda).toEqual({ formas: [{ forma: 'DINHEIRO', valor: 5 }] })
  })

  it('sem o início, não há total, e a linha fica com a forma e o valor que vieram', () => {
    const [registro] = registrosDaConta({ pendentes: [revisao(dinheiro(7, 10)), revisao(conclusao())], conferidas: [] }, 'ana')

    expect(registro.venda).toEqual({ formas: [{ forma: 'DINHEIRO', valor: 7 }] })
  })

  it('a venda conferida pela metade é uma linha só, em revisão, com todos os gestos', () => {
    const [a, b, c, d] = vendaInteira()
    const feita = { conferidaEm: '2026-11-05T10:00:00Z', conferidaPor: 'ana' }
    const registros = registrosDaConta({ pendentes: [c, d], conferidas: [{ ...a, ...feita }, { ...b, ...feita }] }, 'ana')

    expect(registros).toHaveLength(1)
    expect(registros[0]).toMatchObject({ situacao: 'em-revisao', venda: { totalCentavos: 1240 } })
    expect(registros[0].gestos.filter((um) => !um.conferidoEm).map((um) => um.operacaoId)).toEqual([c.operacaoId, d.operacaoId])
  })

  it('a venda toda conferida fica como conferida, e a chave é a mesma do número do cabeçalho', () => {
    const feita = { conferidaEm: '2026-11-05T10:00:00Z', conferidaPor: 'ana' }
    const [registro] = registrosDaConta({ pendentes: [], conferidas: vendaInteira().map((um) => ({ ...um, ...feita })) }, 'ana')

    expect(registro.situacao).toBe('conferido')
    expect(chaveDoRegistro(vendaInteira()[0])).toBe(registro.chave)
  })

  it('a aplicada com revisão não conta como recusa', () => {
    const [registro] = registrosDaConta({ conferidas: [], pendentes: [revisao(conclusao(), {
      resultado: 'APLICADA_COM_REVISAO', detalhe: 'A baixa desta venda deixou o estoque negativo: Coxinha.',
    })] }, 'ana')

    expect(registro).toMatchObject({ recusado: false, motivo: 'A baixa desta venda deixou o estoque negativo: Coxinha.' })
  })
})
