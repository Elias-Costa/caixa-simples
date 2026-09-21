export type Perfil = 'ADMIN' | 'OPERADOR'

/** Quem está operando e em que negócio, como o servidor responde em /api/auth/eu. */
export type Identidade = {
  usuarioId: string
  nome: string
  perfil: Perfil
  contaId: string
  nomeNegocio: string
  tipoNegocio?: string
  estoqueHabilitado: boolean
}
