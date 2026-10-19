export type Perfil = 'ADMIN' | 'OPERADOR'

export type Plano = 'GRATIS' | 'COMPLETO'

/** Onde a Conta está no ciclo mensal do plano pago, no dia de hoje do balcão. */
export type SituacaoDoPlano = 'SEM_MENSALIDADE' | 'EM_DIA' | 'A_VENCER' | 'VENCIDO' | 'SUSPENSO'

export type RecursoDoPlano = 'RELATORIOS' | 'ESTOQUE' | 'MULTIUSUARIO' | 'PIX_INTEGRADO'

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
  /**
   * Se o cartão exige o NSU do comprovante da maquininha. Guardado para o aparelho recusar a parcela
   * sem ele antes de gravá-la sem rede; falta na identidade guardada antes dele, e então não exige.
   */
  nsuObrigatorio?: boolean
  plano?: Plano
  situacaoDoPlano?: SituacaoDoPlano
  vencimentoDoPlano?: string
  inicioDaSuspensao?: string
  /** Os recursos pagos que valem hoje: o plano os inclui e não estão suspensos. */
  recursos?: RecursoDoPlano[]
  /**
   * A senha foi escolhida por outra pessoa, que a conhece, e o aplicativo só mostra a tela de troca
   * até a pessoa escolher a dela. Falta na identidade guardada antes da marca, e então não pede.
   */
  senhaProvisoria?: boolean
  /**
   * A Conta ainda não aceitou a versão vigente dos termos de uso, e o aplicativo só mostra a tela dos
   * termos ao administrador até o aceite; o operador entra normalmente. Falta na identidade guardada
   * antes da marca, e então não pede: a abertura do aplicativo pergunta de novo ao servidor.
   */
  termosPendentes?: boolean
  /**
   * A chave Pix da Conta, que o aparelho põe no QR estático do Pix manual, inclusive sem rede. Falta
   * enquanto o administrador não cadastra uma, e então o Pix manual é lançado sem QR.
   */
  chavePix?: string
  /** A cidade do recebedor no QR; falta junto com a chave. */
  cidadePix?: string
  /**
   * A Conta pode cobrar pelo Pix integrado hoje: o plano o inclui, não está suspenso e o provedor
   * está configurado. Falta na identidade guardada antes da marca, e então não oferece: o Pix manual
   * vale em todo caso.
   */
  pixIntegrado?: boolean
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
