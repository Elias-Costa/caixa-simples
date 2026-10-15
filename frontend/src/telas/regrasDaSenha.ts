/** As mesmas medidas que o servidor confere, para recusar antes de enviar. */
export const TAMANHO_MINIMO_DA_SENHA = 15
export const MAXIMO_DE_BYTES_DA_SENHA = 72

/**
 * O que impede a senha nova de ser aceita, ou nulo se o tamanho serve. O máximo conta bytes em
 * UTF-8, e não letras, porque é o limite do servidor: a letra acentuada ocupa dois. A verificação de
 * vazamentos fica com o servidor, que é quem consulta a lista.
 */
export function problemaDoTamanhoDaSenha(senha: string): string | null {
  if (senha.length < TAMANHO_MINIMO_DA_SENHA) {
    return `A senha precisa ter ao menos ${TAMANHO_MINIMO_DA_SENHA} caracteres`
  }
  if (new TextEncoder().encode(senha).length > MAXIMO_DE_BYTES_DA_SENHA) {
    return `A senha pode ter no máximo ${MAXIMO_DE_BYTES_DA_SENHA} letras sem acento; cada letra com acento ou cedilha conta como duas`
  }
  return null
}
