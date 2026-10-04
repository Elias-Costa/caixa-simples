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
  it('operador vê venda, caixa, cadastro, cobrança do fiado e sincronização', () => {
    expect(rotulos(base)).toEqual(['Vender', 'Caixa', 'Produtos', 'Clientes', 'Fiado',
      'Sincronização'])
  })

  it('operador não vê estoque nem com o controle ligado na conta', () => {
    expect(rotulos({ ...base, estoqueHabilitado: true })).not.toContain('Estoque')
  })

  it('administrador vê também relatórios, usuários, configuração e plano', () => {
    expect(rotulos({ ...base, perfil: 'ADMIN', recursos: ['RELATORIOS'] })).toEqual([
      'Vender',
      'Caixa',
      'Produtos',
      'Clientes',
      'Fiado',
      'Sincronização',
      'Relatórios',
      'Usuários',
      'Configuração',
      'Plano',
    ])
  })

  it('administrador vê estoque só quando a conta ligou o controle', () => {
    expect(rotulos({ ...base, perfil: 'ADMIN', estoqueHabilitado: true, recursos: ['ESTOQUE'] }))
      .toContain('Estoque')
  })

  it('sem o recurso no plano, ou com ele suspenso, relatórios e estoque somem do menu', () => {
    const semRecurso = rotulos({ ...base, perfil: 'ADMIN', estoqueHabilitado: true, recursos: [] })
    expect(semRecurso).not.toContain('Relatórios')
    expect(semRecurso).not.toContain('Estoque')
    expect(semRecurso).toContain('Plano')
  })

  it('identidade guardada antes da lista de recursos mantém o menu, e o servidor decide', () => {
    expect(rotulos({ ...base, perfil: 'ADMIN', estoqueHabilitado: true }))
      .toEqual(expect.arrayContaining(['Estoque', 'Relatórios']))
  })

  it('operador não vê o plano', () => {
    expect(rotulos({ ...base, recursos: ['RELATORIOS'] })).not.toContain('Plano')
  })
})
