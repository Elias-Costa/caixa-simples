// O script do mantenedor e a aplicação precisam dar o mesmo código para o mesmo pedido: o vetor
// fixo abaixo é o mesmo que a suíte Java confere na assinatura da aplicação. O segredo é montado
// por repetição, sem literal no repositório, e os valores são fictícios.
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { codigoDoPedido } from './codigo.mjs'

const SEGREDO = 'x'.repeat(40)
const PEDIDO = '8d5b2c1e-4f3a-4b6c-9d7e-1a2b3c4d5e6f'

test('dá o código do vetor fixo, o mesmo da aplicação', () => {
  assert.equal(codigoDoPedido(SEGREDO, PEDIDO, 'COMPLETO', '123.45'), 'Q18E-8RDD-QZ4S-3HQH')
  assert.equal(codigoDoPedido(SEGREDO, PEDIDO, 'CAIXA_SIMPLES', '67.89'), '95FG-7CV3-TMWE-NDJV')
  assert.equal(codigoDoPedido(SEGREDO, '0f1e2d3c-4b5a-4968-8776-655443322110', 'COMPLETO', '0.50'),
    'Z667-1W9N-QWRG-9ET4')
})

test('aceita o plano como no texto do pedido e o id com maiúsculas e espaços', () => {
  assert.equal(codigoDoPedido(SEGREDO, PEDIDO, 'Caixa Simples', '67.89'), '95FG-7CV3-TMWE-NDJV')
  assert.equal(codigoDoPedido(SEGREDO, PEDIDO, 'completo', '123.45'), 'Q18E-8RDD-QZ4S-3HQH')
  assert.equal(codigoDoPedido(SEGREDO, ` ${PEDIDO.toUpperCase()} `, 'Completo', '123.45'),
    'Q18E-8RDD-QZ4S-3HQH')
})

test('aceita o valor como no texto do pedido ou como no sistema', () => {
  // O texto do pedido sai do formato de moeda do navegador, com espaço não separável depois do R$.
  const doTexto = new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL' })
  for (const valor of [doTexto.format(123.45), 'R$ 123,45', 'R$123,45', '123,45', '123.45',
    '0123,45', ' 123,45 ']) {
    assert.equal(codigoDoPedido(SEGREDO, PEDIDO, 'COMPLETO', valor), 'Q18E-8RDD-QZ4S-3HQH', valor)
  }
  for (const valor of ['0,5', '0.5', '0,50', 'R$ 0,50']) {
    assert.equal(codigoDoPedido(SEGREDO, '0f1e2d3c-4b5a-4968-8776-655443322110', 'COMPLETO', valor),
      'Z667-1W9N-QWRG-9ET4', valor)
  }
  assert.equal(codigoDoPedido(SEGREDO, PEDIDO, 'COMPLETO', doTexto.format(1234.56)),
    'Z2E5-FVNC-0565-XGWZ')
  assert.equal(codigoDoPedido(SEGREDO, PEDIDO, 'COMPLETO', '1234.56'), 'Z2E5-FVNC-0565-XGWZ')
})

test('outro valor dá outro código para o mesmo pedido e o mesmo plano', () => {
  assert.equal(codigoDoPedido(SEGREDO, PEDIDO, 'COMPLETO', '123,44'), 'RPMX-JK6D-KNQD-0BQ3')
  assert.notEqual(codigoDoPedido(SEGREDO, PEDIDO, 'COMPLETO', '123,44'),
    codigoDoPedido(SEGREDO, PEDIDO, 'COMPLETO', '123,45'))
})

test('recusa segredo curto, id que não é de pedido, plano sem pedido e valor ambíguo', () => {
  assert.throws(() => codigoDoPedido('x'.repeat(31), PEDIDO, 'COMPLETO', '123.45'), /32 bytes/)
  assert.throws(() => codigoDoPedido(undefined, PEDIDO, 'COMPLETO', '123.45'), /32 bytes/)
  assert.throws(() => codigoDoPedido(SEGREDO, 'pedido-123', 'COMPLETO', '123.45'), /id de pedido/)
  assert.throws(() => codigoDoPedido(SEGREDO, PEDIDO, 'GRATIS', '123.45'), /plano invalido/)
  // 1.234 sem vírgula pode ser mil duzentos e trinta e quatro ou um real e vinte e três: recusado.
  for (const valor of [undefined, '', 'R$', 'Simples', '1.234', '123,456', '-123,45', '12a,00',
    '1,2,3']) {
    assert.throws(() => codigoDoPedido(SEGREDO, PEDIDO, 'COMPLETO', valor), /valor invalido/,
      String(valor))
  }
})
