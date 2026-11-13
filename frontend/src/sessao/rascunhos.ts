import type { Identidade } from './Identidade'

/**
 * O que a pessoa começou a preencher numa tela e deixou ao ir a outra, para voltar como estava quando ela volta. Quem
 * sai no meio do gesto quase sempre volta para terminá-lo.
 *
 * Fica só na memória desta aba, por Conta, usuário e tela: some ao recarregar e quando a sessão termina, que o provedor
 * da sessão avisa. Nada vai para o armazenamento do navegador, porque o rascunho tem texto livre, como o motivo de uma
 * sangria, e só serve a quem está no meio do gesto.
 */
const rascunhos = new Map<string, unknown>()

/** As telas que guardam rascunho. Um por tela: o último formulário começado nela. */
export type TelaComRascunho = 'caixa' | 'produtos'

/** A chave do rascunho de uma tela para quem está na sessão; sem sessão, nenhuma, e nada se guarda. */
export function chaveDoRascunho(identidade: Identidade | null, tela: TelaComRascunho): string | undefined {
  return identidade ? `${identidade.contaId}:${identidade.usuarioId}:${tela}` : undefined
}

export function lerRascunho<T>(chave: string | undefined): T | undefined {
  return chave === undefined ? undefined : rascunhos.get(chave) as T | undefined
}

export function guardarRascunho<T>(chave: string | undefined, rascunho: T): void {
  if (chave !== undefined) rascunhos.set(chave, rascunho)
}

export function apagarRascunho(chave: string | undefined): void {
  if (chave !== undefined) rascunhos.delete(chave)
}

/** Ao fim da sessão, por qualquer caminho: quem entrar depois neste aparelho não herda nada. */
export function apagarRascunhos(): void {
  rascunhos.clear()
}
