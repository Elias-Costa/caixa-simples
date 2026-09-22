import { ErroDaApi, SemConexao } from '../api/cliente'

export function erroDeCadastro(falha: unknown): string {
  if (falha instanceof SemConexao) return 'Sem conexão. Tente de novo quando a rede voltar.'
  if (falha instanceof ErroDaApi) {
    if (falha.status === 401) return 'A sessão expirou. Entre novamente.'
    if (falha.status === 403) return 'Seu perfil não permite esta operação.'
    return falha.detalhe ?? falha.titulo
  }
  return 'Não foi possível concluir a operação. Tente de novo.'
}
