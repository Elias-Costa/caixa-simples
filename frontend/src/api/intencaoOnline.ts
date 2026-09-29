import type { Identidade } from '../sessao/Identidade'

const prefixo = 'caixa-simples.intencao-online.'

export type IntencaoOnline = { id: string; confirmar(): void }

/** A chave não guarda valor nem motivo em texto no armazenamento da aba. */
export async function intencaoOnline(rota: string, conteudo: object,
  identidade: Pick<Identidade, 'contaId' | 'usuarioId'> | null): Promise<IntencaoOnline> {
  if (!identidade) throw new Error('Entre novamente antes de registrar dinheiro.')
  const dados = new TextEncoder().encode(JSON.stringify([rota, conteudo]))
  const resumo = await crypto.subtle.digest('SHA-256', dados)
  const assinatura = Array.from(new Uint8Array(resumo), (byte) => byte.toString(16).padStart(2, '0')).join('')
  const chave = `${prefixo}${identidade.contaId}.${identidade.usuarioId}.${assinatura}`
  const id = sessionStorage.getItem(chave) ?? crypto.randomUUID()
  sessionStorage.setItem(chave, id)
  return {
    id,
    confirmar() { if (sessionStorage.getItem(chave) === id) sessionStorage.removeItem(chave) },
  }
}
