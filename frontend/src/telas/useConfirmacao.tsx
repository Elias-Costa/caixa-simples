import { useCallback, useRef, useState, type ReactNode } from 'react'
import { DialogoDeConfirmacao, type Pergunta } from './DialogoDeConfirmacao'

export type { Pergunta } from './DialogoDeConfirmacao'

/**
 * A confirmação antes do irreversível, num diálogo do aplicativo no lugar do window.confirm do
 * navegador. O do navegador mostra a origem, tem OK no lugar do verbo, já abre com o foco no OK e
 * pode ser bloqueado pela pessoa, e aí responde não sozinho, sem nada na tela.
 *
 * A tela espera a resposta de confirmar, como esperava a do navegador, e desenha o diálogo devolvido
 * fora de qualquer formulário. Aberto como modal, ele fica por cima de tudo e deixa o resto da tela
 * inerte, onde quer que esteja no que a tela desenha.
 */
export function useConfirmacao(): [(pergunta: Pergunta) => Promise<boolean>, ReactNode] {
  const [pergunta, setPergunta] = useState<Pergunta | null>(null)
  const resposta = useRef<((sim: boolean) => void) | null>(null)

  const confirmar = useCallback((nova: Pergunta) => {
    setPergunta(nova)
    return new Promise<boolean>((resolver) => { resposta.current = resolver })
  }, [])

  const responder = useCallback((sim: boolean) => {
    const resolver = resposta.current
    resposta.current = null
    setPergunta(null)
    resolver?.(sim)
  }, [])

  const dialogo = pergunta ? <DialogoDeConfirmacao pergunta={pergunta} aoResponder={responder} /> : null
  return [confirmar, dialogo]
}
