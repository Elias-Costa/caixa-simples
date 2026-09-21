import { describe, expect, it } from 'vitest'
import type { Identidade } from '../sessao/Identidade'
import { itensDoMenu } from './menu'

const base: Identidade = {
  usuarioId: '1',
  nome: 'Ana',
  perfil: 'OPERADOR',
  contaId: '2',
  nomeNegocio: 'Cafeteria Aurora',
  estoqueHabilitado: false,
}

const rotulos = (identidade: Identidade) => itensDoMenu(identidade).map((item) => item.rotulo)

describe('itensDoMenu', () => {
  it('operador vê só venda, caixa, produtos e clientes', () => {
    expect(rotulos(base)).toEqual(['Vender', 'Caixa', 'Produtos', 'Clientes'])
  })

  it('operador não vê estoque nem com o controle ligado na conta', () => {
    expect(rotulos({ ...base, estoqueHabilitado: true })).not.toContain('Estoque')
  })

  it('administrador vê também relatórios, usuários e configuração', () => {
    expect(rotulos({ ...base, perfil: 'ADMIN' })).toEqual([
      'Vender',
      'Caixa',
      'Produtos',
      'Clientes',
      'Relatórios',
      'Usuários',
      'Configuração',
    ])
  })

  it('administrador vê estoque só quando a conta ligou o controle', () => {
    expect(rotulos({ ...base, perfil: 'ADMIN', estoqueHabilitado: true })).toContain('Estoque')
  })
})
