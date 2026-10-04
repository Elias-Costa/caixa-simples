export type Perfil = 'ADMIN' | 'OPERADOR'

export type Plano = 'GRATIS' | 'CAIXA_SIMPLES' | 'COMPLETO'

/** Onde a Conta está no ciclo mensal do plano pago, no dia de hoje do balcão. */
export type SituacaoDoPlano = 'SEM_MENSALIDADE' | 'EM_DIA' | 'A_VENCER' | 'VENCIDO' | 'SUSPENSO'

export type RecursoDoPlano = 'RELATORIOS' | 'ESTOQUE' | 'MULTIUSUARIO'

/**
 * Quem está operando e em que negócio, como o servidor responde em /api/auth/eu.
 *
 * Os campos do plano são opcionais porque a identidade guardada no aparelho por uma versão
 * anterior não os tem; a abertura do aplicativo pergunta de novo ao servidor e a substitui. As
 * datas vêm como AAAA-MM-DD e faltam no plano gratuito.
 */
export type Identidade = {
  usuarioId: string
  nome: string
  perfil: Perfil
  contaId: string
  nomeNegocio: string
  tipoNegocio?: string
  estoqueHabilitado: boolean
  plano?: Plano
  situacaoDoPlano?: SituacaoDoPlano
  vencimentoDoPlano?: string
  inicioDaSuspensao?: string
  /** Os recursos pagos que valem hoje: o plano os inclui e não estão suspensos. */
  recursos?: RecursoDoPlano[]
}

/**
 * Se o plano dá o recurso hoje, para o menu e as rotas decidirem o que oferecer.
 *
 * Sem a lista, que a identidade guardada antes dela não tem, responde que sim: quem recusa de
 * verdade é o servidor, e a resposta da abertura do aplicativo traz a lista logo em seguida.
 */
export function temRecurso(identidade: Identidade, recurso: RecursoDoPlano): boolean {
  return identidade.recursos === undefined || identidade.recursos.includes(recurso)
}
