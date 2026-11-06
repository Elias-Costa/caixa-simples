/**
 * Diz se um token JWT já expirou, lendo o claim exp do payload.
 *
 * A assinatura não é conferida aqui, e não precisa ser: quem valida o token é o servidor, a cada
 * requisição. O cliente só quer saber se vale a pena abrir o shell sem rede ou ir direto ao
 * login. Token que não se consegue ler conta como expirado.
 */
export function tokenExpirado(token: string, agora: Date = new Date()): boolean {
  return restanteDoToken(token, agora) === 0
}

/** Quanto falta para o token vencer, em milissegundos; do vencido ou ilegível, não falta nada. */
export function restanteDoToken(token: string, agora: Date = new Date()): number {
  const expiraEmSegundos = expiracaoDe(token)
  if (expiraEmSegundos === undefined) return 0
  return Math.max(0, expiraEmSegundos * 1000 - agora.getTime())
}

/**
 * O servidor acabou de emitir o token e o aparelho já o lê como vencido: a validade é de dias, então
 * o relógio do aparelho está adiantado pelo menos isso. Entrar assim só levaria de volta à entrada,
 * dizendo que a sessão venceu.
 */
export class RelogioAdiantado extends Error {
  constructor() {
    super('O relógio do aparelho está adiantado')
    this.name = 'RelogioAdiantado'
  }
}

function expiracaoDe(token: string): number | undefined {
  const partes = token.split('.')
  if (partes.length !== 3) return undefined
  try {
    const payload: unknown = JSON.parse(decodificarBase64Url(partes[1]))
    if (typeof payload !== 'object' || payload === null || !('exp' in payload)) return undefined
    return typeof payload.exp === 'number' ? payload.exp : undefined
  } catch {
    return undefined
  }
}

function decodificarBase64Url(texto: string): string {
  const base64 = texto.replace(/-/g, '+').replace(/_/g, '/')
  const comPreenchimento = base64 + '='.repeat((4 - (base64.length % 4)) % 4)
  const binario = atob(comPreenchimento)
  return new TextDecoder().decode(Uint8Array.from(binario, (caractere) => caractere.charCodeAt(0)))
}
