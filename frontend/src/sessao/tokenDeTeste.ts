/** Um JWT de mentira, sem assinatura válida, só com o payload que o cliente lê. */
export function tokenComExpiracao(expiraEm: Date): string {
  const cabecalho = base64Url(JSON.stringify({ alg: 'HS256', typ: 'JWT' }))
  const payload = base64Url(JSON.stringify({ sub: 'alguem', exp: Math.floor(expiraEm.getTime() / 1000) }))
  return `${cabecalho}.${payload}.assinatura`
}

function base64Url(texto: string): string {
  return btoa(texto).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}
