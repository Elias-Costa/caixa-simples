import type { Identidade } from '../sessao/Identidade'

/**
 * A marca de visto fica no aparelho, por Conta e por pessoa: quem divide o tablet também vê o tour na
 * primeira vez. O prefixo é o do aplicativo, e Apagar os dados deste aparelho a apaga junto.
 */
function chaveDoTour(identidade: Pick<Identidade, 'contaId' | 'usuarioId'>): string {
  return `fechadia.tour-visto.${identidade.contaId}.${identidade.usuarioId}`
}

/** Sem acesso ao armazenamento, o tour conta como visto: aparecer em toda tela seria pior. */
export function tourVisto(identidade: Pick<Identidade, 'contaId' | 'usuarioId'>): boolean {
  try {
    return localStorage.getItem(chaveDoTour(identidade)) !== null
  } catch {
    return true
  }
}

export function marcarTourVisto(identidade: Pick<Identidade, 'contaId' | 'usuarioId'>): void {
  try {
    localStorage.setItem(chaveDoTour(identidade), '1')
  } catch {
    // Sem armazenamento, o tour volta no próximo acesso; nada se perde.
  }
}
