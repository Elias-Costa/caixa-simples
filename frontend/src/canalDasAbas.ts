/**
 * O aviso que uma aba do aplicativo manda às outras abas do mesmo navegador. Só o fato, sem dado: nada de
 * token, identidade, dono ou conteúdo de gesto. Quem recebe relê o que é dele, pela própria sessão, e uma aba
 * com a sessão trocada não lê nada a mais por causa do aviso.
 *
 * - fila: a fila deste aparelho mudou.
 * - rodada: uma rodada de envio começou ou terminou, e se gravou algum resultado.
 * - conexao: uma chamada mudou o que se sabe do servidor, e em que momento ela terminou.
 * - sessao-encerrada: o servidor recusou o token da sessão aberta neste navegador.
 */
export type AvisoEntreAbas =
  | { tipo: 'fila' }
  | { tipo: 'rodada'; fase: 'inicio' }
  | { tipo: 'rodada'; fase: 'fim'; gravou: boolean }
  | { tipo: 'conexao'; semResposta: boolean; em: number }
  | { tipo: 'sessao-encerrada' }

const NOME_DO_CANAL = 'ecrx-caixa-abas'
const ouvintes = new Set<(aviso: AvisoEntreAbas) => void>()
// Indefinido até a primeira vez; nulo quando o navegador não tem o canal, e aí cada aba fica por conta própria.
let canal: BroadcastChannel | null | undefined

function abrirCanal(): BroadcastChannel | null {
  if (canal !== undefined) return canal
  try {
    canal = typeof BroadcastChannel === 'function' ? new BroadcastChannel(NOME_DO_CANAL) : null
  } catch {
    canal = null
  }
  canal?.addEventListener('message', (evento) => {
    const aviso = lerAviso(evento.data)
    if (!aviso) return
    for (const ouvinte of [...ouvintes]) ouvinte(aviso)
  })
  return canal
}

/**
 * Duas abas podem rodar versões diferentes do aplicativo, uma já atualizada e a outra não. O aviso que esta
 * versão não conhece, ou com um campo fora do esperado, é ignorado, e o que segue adiante é remontado só com
 * os campos conhecidos.
 */
function lerAviso(dados: unknown): AvisoEntreAbas | null {
  if (typeof dados !== 'object' || dados === null) return null
  const aviso = dados as Record<string, unknown>
  switch (aviso.tipo) {
    case 'fila':
      return { tipo: 'fila' }
    case 'sessao-encerrada':
      return { tipo: 'sessao-encerrada' }
    case 'rodada':
      if (aviso.fase === 'inicio') return { tipo: 'rodada', fase: 'inicio' }
      if (aviso.fase === 'fim' && typeof aviso.gravou === 'boolean') {
        return { tipo: 'rodada', fase: 'fim', gravou: aviso.gravou }
      }
      return null
    case 'conexao':
      if (typeof aviso.semResposta === 'boolean' && typeof aviso.em === 'number' && Number.isFinite(aviso.em)) {
        return { tipo: 'conexao', semResposta: aviso.semResposta, em: aviso.em }
      }
      return null
    default:
      return null
  }
}

/** Avisa as outras abas; esta não recebe o próprio aviso. Sem o canal no navegador, não faz nada. */
export function avisarAsOutrasAbas(aviso: AvisoEntreAbas): void {
  try {
    abrirCanal()?.postMessage(aviso)
  } catch {
    // O canal fechado na saída da página não tem mais a quem avisar.
  }
}

/** Ouve o que as outras abas avisam. Devolve o que desliga o ouvinte. */
export function aoAvisoDeOutraAba(ouvinte: (aviso: AvisoEntreAbas) => void): () => void {
  abrirCanal()
  ouvintes.add(ouvinte)
  return () => { ouvintes.delete(ouvinte) }
}
