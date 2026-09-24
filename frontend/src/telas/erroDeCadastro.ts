import { ErroDaApi, SemConexao } from '../api/cliente'

export function erroDeCadastro(falha: unknown): string {
  if (falha instanceof SemConexao) return 'Não foi possível confirmar a resposta do servidor. Reabra a lista antes de repetir.'
  if (falha instanceof ErroDaApi) {
    if (falha.status === 401) return 'A sessão expirou. Entre novamente.'
    if (falha.status === 403) return 'Seu perfil não permite esta operação.'
    return falha.detalhe ?? falha.titulo
  }
  if (falha instanceof Error) return falha.message
  return 'Não foi possível concluir a operação. Tente de novo.'
}
