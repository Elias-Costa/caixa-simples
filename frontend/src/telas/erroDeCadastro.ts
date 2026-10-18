import { ErroDaApi, SemConexao } from '../api/cliente'

/** O nome que o campo tem na tela, quando o do corpo da requisição não é a mesma palavra. */
const NOME_NA_TELA: Record<string, string> = {
  preco: 'preço',
  codigo: 'código',
  email: 'e-mail',
  valorAbertura: 'valor inicial',
  valorContado: 'valor contado',
  valorRecebido: 'valor recebido',
}

export function nomeDoCampo(campo: string): string {
  return NOME_NA_TELA[campo] ?? campo
}

export function erroDeCadastro(falha: unknown): string {
  if (falha instanceof SemConexao) return 'Não foi possível confirmar a resposta do servidor. Reabra a lista antes de repetir.'
  if (falha instanceof ErroDaApi) {
    if (falha.status === 401) return 'A sessão expirou. Entre novamente.'
    if (falha.status === 403) return 'Seu perfil não permite esta operação.'
    // A recusa da validação traz o detalhe genérico do framework, em inglês; o que diz o que
    // corrigir é a mensagem de cada campo.
    const campos = Object.entries(falha.campos)
    if (falha.status === 400 && campos.length > 0) {
      return `Confira: ${campos.map(([campo, mensagem]) => `${nomeDoCampo(campo)} ${mensagem}`).join('; ')}.`
    }
    return falha.detalhe ?? falha.titulo
  }
  if (falha instanceof Error) return falha.message
  return 'Não foi possível concluir a operação. Tente de novo.'
}

/** As mensagens por campo de uma recusa da validação, para o formulário marcar cada campo. */
export function errosDosCampos(falha: unknown): Record<string, string> {
  return falha instanceof ErroDaApi && falha.status === 400 ? falha.campos : {}
}
