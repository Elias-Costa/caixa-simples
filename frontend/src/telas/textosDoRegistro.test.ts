import { describe, expect, it } from 'vitest'
import type { GestoNaFila } from '../offline/fila'
import { registrosDaFila, type RegistroDaFila } from '../offline/registrosDaFila'
import { conteudoDoPasso, linhasDoRegistro, situacaoDaLinha, situacaoDoPasso, tituloDoRegistro, type NomesDoCadastro } from './textosDoRegistro'

const HOJE = '2026-11-05'
// 23:42 no balcão, que fica a três horas de Greenwich.
const DE_HOJE = '2026-11-06T02:42:00.000Z'
const DE_ONTEM = '2026-11-04T02:42:00.000Z'
const SESSAO = '00000000-0000-4000-8000-000000000021'
const nomes: NomesDoCadastro = { cliente: () => 'Maria Souza', produto: () => 'Broa de milho' }

function gesto(tipo: string, payload: GestoNaFila['payload'], parcial: Partial<GestoNaFila> = {}): GestoNaFila {
  return { operacaoId: crypto.randomUUID(), registroId: '00000000-0000-4000-8000-000000000011', tipo, payload,
    dependeDe: [], estado: 'queued', criadoEm: DE_HOJE, ...parcial }
}

function registro(...gestos: GestoNaFila[]): RegistroDaFila {
  return registrosDaFila(gestos, 'ana')[0]
}

const titulo = (...gestos: GestoNaFila[]) =>
  tituloDoRegistro(registro(...gestos), HOJE, nomes).replaceAll(' ', ' ')

const recusado = (detalhe: string): Partial<GestoNaFila> => ({ estado: 'needs_review', resultado: { aplicada: false, detalhe } })
const comRevisao = (detalhe: string): Partial<GestoNaFila> => ({ estado: 'needs_review', resultado: { aplicada: true, detalhe } })

function vendaInteira(parcial: Partial<GestoNaFila> = {}, criadoEm = DE_HOJE): GestoNaFila[] {
  return [
    gesto('venda.iniciar', { sessaoCaixaId: SESSAO, criadoEm }, parcial),
    gesto('venda.adicionarItem', { itemId: 'i', produtoId: 'p', nome: 'Sonho', quantidade: 1, precoUnitario: 12.4,
      desconto: 0, versaoProduto: 1 }, parcial),
    gesto('venda.registrarPagamento', { pagamentoId: 'd', forma: 'DINHEIRO', valor: 5, valorRecebido: 5 }, parcial),
    gesto('venda.registrarPagamento', { pagamentoId: 'c', forma: 'CARTAO', valor: 7.4, valorRecebido: null }, parcial),
  ]
}

describe('o título da linha', () => {
  it('diz a venda pela hora e pelo total, com o dia quando é de outro dia', () => {
    expect(titulo(...vendaInteira())).toBe('Venda das 23:42, R$ 12,40')
    expect(titulo(...vendaInteira({}, DE_ONTEM))).toBe('Venda de 03/11 às 23:42, R$ 12,40')
  })

  it('sem o início da venda, fica só a hora', () => {
    expect(titulo(gesto('venda.concluir', {}, comRevisao('Estoque.')))).toBe('Venda das 23:42')
  })

  it('diz o movimento de caixa pelo valor', () => {
    expect(titulo(gesto('caixa.sangrar', { valor: 20 }))).toBe('Sangria das 23:42, R$ 20,00')
    expect(titulo(gesto('caixa.suprir', { valor: 50 }))).toBe('Suprimento das 23:42, R$ 50,00')
    expect(titulo(gesto('caixa.abrir', { valorAbertura: 150 }))).toBe('Caixa aberto às 23:42, R$ 150,00')
    expect(titulo(gesto('caixa.fechar', { valorContado: 151.62 }))).toBe('Caixa fechado às 23:42, contado R$ 151,62')
    expect(titulo(gesto('caixa.abrir', { valorAbertura: 150 }, { criadoEm: DE_ONTEM })))
      .toBe('Caixa aberto em 03/11 às 23:42, R$ 150,00')
  })

  it('diz o cadastro pelo nome mais recente, e o gesto sem nome o busca no cadastro do aparelho', () => {
    expect(titulo(gesto('cliente.criar', { nome: 'Maria' }), gesto('cliente.editar', { nome: 'Maria Lima' })))
      .toBe('Cliente cadastrado às 23:42: Maria Lima')
    expect(titulo(gesto('produto.editar', { nome: 'Broa', preco: 4.8 }))).toBe('Produto editado às 23:42: Broa')
    expect(titulo(gesto('cliente.inativar', {}))).toBe('Cliente inativado às 23:42: Maria Souza')
    expect(titulo(gesto('produto.reativar', {}))).toBe('Produto reativado às 23:42: Broa de milho')
  })
})

describe('as linhas abaixo do título', () => {
  const linhas = (...gestos: GestoNaFila[]) =>
    linhasDoRegistro(registro(...gestos), nomes).map((linha) => linha.replaceAll(' ', ' '))

  it('por enviar, cabe tudo numa só', () => {
    expect(linhas(...vendaInteira())).toEqual(['Dinheiro e Cartão · Na fila · 4 passos'])
    expect(linhas(gesto('caixa.sangrar', { valor: 20, motivo: 'Cofre' }))).toEqual(['Cofre · Na fila'])
    expect(linhas(gesto('cliente.criar', { nome: 'Maria', contato: '75 9999' }), gesto('cliente.editar', { nome: 'Maria' })))
      .toEqual(['Na fila · 2 passos'])
    expect(linhas(gesto('cliente.criar', { nome: 'Maria', contato: '75 9999' }))).toEqual(['75 9999 · Na fila'])
    expect(linhas(gesto('produto.editar', { nome: 'Broa', preco: 4.8 }))).toEqual(['R$ 4,80 · Na fila'])
  })

  it('em revisão, o motivo tem a linha dele', () => {
    expect(linhas(...vendaInteira(recusado('O caixa foi fechado.')))).toEqual(
      ['Dinheiro e Cartão · 4 passos', 'Recusada: O caixa foi fechado.'])
    expect(linhas(gesto('venda.concluir', {}, comRevisao('Estoque negativo.')))).toEqual(
      ['Aplicada, com revisão: Estoque negativo.'])
  })

  it('sem total, a forma leva o valor; a venda de um gesto só diz o conteúdo dele', () => {
    expect(linhas(gesto('venda.registrarPagamento', { pagamentoId: 'f', forma: 'FIADO', valor: 12.4, valorRecebido: null },
      recusado('Parou.')), gesto('venda.concluir', {}, recusado('Parou.')))).toEqual(
      ['Fiado R$ 12,40 · 2 passos', 'Não concluída: Parou.'])
    expect(linhas(gesto('venda.adicionarItem', { nome: 'Café', unidade: 'un', quantidade: 1, precoUnitario: 5 },
      comRevisao('Preço.')))).toEqual(['1 un × Café · R$ 5,00', 'Aplicada, com revisão: Preço.'])
  })
})

describe('a situação', () => {
  it('concorda com o registro', () => {
    expect(situacaoDaLinha(registro(gesto('caixa.sangrar', {}, recusado('Parou.'))))).toBe('Recusada: Parou.')
    expect(situacaoDaLinha(registro(gesto('caixa.suprir', {}, recusado('Parou.'))))).toBe('Recusado: Parou.')
    expect(situacaoDaLinha(registro(gesto('caixa.sangrar', {}, comRevisao('Ok.'))))).toBe('Aplicada, com revisão: Ok.')
    expect(situacaoDaLinha(registro(gesto('produto.editar', {}, comRevisao('Ok.'))))).toBe('Aplicado, com revisão: Ok.')
    expect(situacaoDaLinha(registro(gesto('caixa.sangrar', {}, { estado: 'failed', falha: 'Sem resposta do servidor.' }))))
      .toBe('Com falha, será repetida: Sem resposta do servidor.')
    expect(situacaoDaLinha(registro(gesto('cliente.criar', {}, { estado: 'failed', falha: 'Sem resposta do servidor.' }))))
      .toBe('Com falha, será repetido: Sem resposta do servidor.')
    expect(situacaoDaLinha(registro(gesto('caixa.abrir', {}, { estado: 'syncing' })))).toBe('Enviando')
  })

  it('separa a venda recusada no início, que nem existe no servidor, da que ficou aberta', () => {
    const noInicio = registro(gesto('venda.iniciar', { sessaoCaixaId: SESSAO, criadoEm: DE_HOJE }, recusado('Fechado.')))
    expect(situacaoDaLinha(noInicio)).toBe('Recusada: Fechado.')
    const depois = registro(gesto('venda.iniciar', { sessaoCaixaId: SESSAO, criadoEm: DE_HOJE },
      { estado: 'sent', resultado: { aplicada: true } }), gesto('venda.concluir', {}, recusado('Parou.')))
    expect(situacaoDaLinha(depois)).toBe('Não concluída: Parou.')
  })

  it('o passo diz o próprio estado, inclusive o que não tem pendência', () => {
    expect(situacaoDoPasso(gesto('venda.iniciar', {}, { estado: 'sent' }))).toBe('Aplicado')
    expect(situacaoDoPasso(gesto('venda.iniciar', {}))).toBe('Na fila')
    expect(situacaoDoPasso(gesto('venda.iniciar', {}, recusado('Fechado.')))).toBe('Não aplicado: Fechado.')
  })
})

describe('o conteúdo do passo', () => {
  it('usa a quantidade com a unidade, e o item sem unidade fica só com o número', () => {
    expect(conteudoDoPasso('venda.adicionarItem', { nome: 'Pão francês', unidade: 'kg', quantidade: 0.35, precoUnitario: 12 }, nomes)
      .replaceAll(' ', ' ')).toBe('0,35 kg × Pão francês · R$ 12,00')
    expect(conteudoDoPasso('venda.adicionarItem', { nome: 'Serviço', quantidade: 2, precoUnitario: 30 }, nomes)
      .replaceAll(' ', ' ')).toBe('2 × Serviço · R$ 30,00')
  })

  it('diz o cliente da venda pelo cadastro do aparelho', () => {
    expect(conteudoDoPasso('venda.vincularCliente', { clienteId: 'c-1' }, nomes)).toBe('Maria Souza')
  })
})
