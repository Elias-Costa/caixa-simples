import type { Identidade } from '../sessao/Identidade'

/**
 * O aviso de vencimento que o administrador vê em toda tela, ou nulo quando não há o que avisar.
 *
 * Começa sete dias antes do vencimento, simétrico aos sete dias de tolerância depois dele, e
 * depende só da identidade que a abertura do aplicativo já busca: não espera e-mail nem outra
 * tela. O operador não vê, porque renovar é do administrador.
 */
export function avisoDoPlano(identidade: Identidade): string | null {
  if (identidade.perfil !== 'ADMIN') return null
  const vencimento = identidade.vencimentoDoPlano
  const suspensao = identidade.inicioDaSuspensao
  if (!vencimento || !suspensao) return null

  switch (identidade.situacaoDoPlano) {
    case 'A_VENCER':
      return `O plano vence em ${mostrarData(vencimento)}.`
    case 'VENCIDO':
      return `O plano venceu em ${mostrarData(vencimento)}. Sem a renovação, os recursos pagos `
        + `param em ${mostrarData(suspensao)}.`
    case 'SUSPENSO':
      return `Os recursos pagos do plano estão suspensos desde ${mostrarData(suspensao)}. `
        + 'Venda, caixa e cadastro continuam.'
    default:
      return null
  }
}

function mostrarData(dia: string): string {
  const [ano, mes, data] = dia.split('-')
  return `${data}/${mes}/${ano}`
}
