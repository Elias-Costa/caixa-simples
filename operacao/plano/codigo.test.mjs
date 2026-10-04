// O script do mantenedor e a aplicação precisam dar o mesmo código para o mesmo pedido: o vetor
// fixo abaixo é o mesmo que a suíte Java confere na assinatura da aplicação. O segredo é montado
// por repetição, sem literal no repositório.
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { codigoDoPedido } from './codigo.mjs'

const SEGREDO = 'x'.repeat(40)
const PEDIDO = '8d5b2c1e-4f3a-4b6c-9d7e-1a2b3c4d5e6f'

test('dá o código do vetor fixo, o mesmo da aplicação', () => {
  assert.equal(codigoDoPedido(SEGREDO, PEDIDO, 'COMPLETO'), '2JSY-PFPP-BNA2-YXNN')
  assert.equal(codigoDoPedido(SEGREDO, PEDIDO, 'CAIXA_SIMPLES'), '69SK-7TK8-C7XY-4TAD')
  assert.equal(codigoDoPedido(SEGREDO, '0f1e2d3c-4b5a-4968-8776-655443322110', 'COMPLETO'),
    '6P97-S1Z4-SG64-YWJB')
})

test('aceita o plano como no texto do pedido e o id com maiúsculas e espaços', () => {
  assert.equal(codigoDoPedido(SEGREDO, PEDIDO, 'Caixa Simples'), '69SK-7TK8-C7XY-4TAD')
  assert.equal(codigoDoPedido(SEGREDO, PEDIDO, 'completo'), '2JSY-PFPP-BNA2-YXNN')
  assert.equal(codigoDoPedido(SEGREDO, ` ${PEDIDO.toUpperCase()} `, 'Completo'),
    '2JSY-PFPP-BNA2-YXNN')
})

test('recusa segredo curto, id que não é de pedido e plano sem pedido', () => {
  assert.throws(() => codigoDoPedido('x'.repeat(31), PEDIDO, 'COMPLETO'), /32 bytes/)
  assert.throws(() => codigoDoPedido(undefined, PEDIDO, 'COMPLETO'), /32 bytes/)
  assert.throws(() => codigoDoPedido(SEGREDO, 'pedido-123', 'COMPLETO'), /id de pedido/)
  assert.throws(() => codigoDoPedido(SEGREDO, PEDIDO, 'GRATIS'), /plano invalido/)
})
