import { describe, expect, it } from 'vitest'
import type { Identidade } from '../sessao/Identidade'
import { avisoDoPlano } from './avisoDoPlano'

const admin: Identidade = {
  usuarioId: '1',
  nome: 'Ana',
  perfil: 'ADMIN',
  contaId: '2',
  nomeNegocio: 'Cafeteria Aurora',
  estoqueHabilitado: false,
  plano: 'CAIXA_SIMPLES',
  situacaoDoPlano: 'EM_DIA',
  vencimentoDoPlano: '2026-03-10',
  inicioDaSuspensao: '2026-03-18',
  recursos: ['RELATORIOS'],
}

describe('avisoDoPlano', () => {
  it('não avisa com o plano em dia nem no plano gratuito', () => {
    expect(avisoDoPlano(admin)).toBeNull()
    expect(avisoDoPlano({
      ...admin, plano: 'GRATIS', situacaoDoPlano: 'SEM_MENSALIDADE',
      vencimentoDoPlano: undefined, inicioDaSuspensao: undefined, recursos: [],
    })).toBeNull()
  })

  it('avisa nos sete dias antes do vencimento, na tolerância e na suspensão', () => {
    expect(avisoDoPlano({ ...admin, situacaoDoPlano: 'A_VENCER' }))
      .toBe('O plano vence em 10/03/2026.')
    expect(avisoDoPlano({ ...admin, situacaoDoPlano: 'VENCIDO' }))
      .toBe('O plano venceu em 10/03/2026. Sem a renovação, os recursos pagos param em 18/03/2026.')
    expect(avisoDoPlano({ ...admin, situacaoDoPlano: 'SUSPENSO', recursos: [] }))
      .toBe('Os recursos pagos do plano estão suspensos desde 18/03/2026. Venda, caixa e cadastro'
        + ' continuam.')
  })

  it('o operador não vê o aviso, porque renovar é do administrador', () => {
    expect(avisoDoPlano({ ...admin, perfil: 'OPERADOR', situacaoDoPlano: 'SUSPENSO' })).toBeNull()
  })

  it('identidade guardada antes dos campos do plano não avisa', () => {
    expect(avisoDoPlano({
      ...admin, situacaoDoPlano: undefined, vencimentoDoPlano: undefined,
      inicioDaSuspensao: undefined,
    })).toBeNull()
  })
})
