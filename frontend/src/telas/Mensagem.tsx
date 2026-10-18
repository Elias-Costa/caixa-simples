import { useEffect, useRef } from 'react'

type Props = {
  tipo: 'erro' | 'sucesso'
  texto: string | null | undefined
}

/**
 * A resposta a uma ação, posta perto de onde ela foi tocada. Quando o texto muda, a tela rola só o
 * bastante para a mensagem aparecer: num formulário no fim de uma lista longa, ela ficaria acima da
 * área visível, e quem tocou acharia que nada aconteceu. O foco continua onde estava, para não tirar
 * o leitor de código de barras da busca.
 */
export function Mensagem({ tipo, texto }: Props) {
  const ref = useRef<HTMLParagraphElement>(null)
  useEffect(() => {
    if (texto) ref.current?.scrollIntoView?.({ block: 'nearest' })
  }, [texto])
  if (!texto) return null
  return tipo === 'erro'
    ? <p ref={ref} className="mensagem-erro" role="alert">{texto}</p>
    : <p ref={ref} className="mensagem-sucesso" role="status">{texto}</p>
}

/** A mensagem do servidor para um campo, ligada a ele pelo aria-describedby do próprio campo. */
export function ErroDoCampo({ id, texto }: { id: string; texto: string | undefined }) {
  return texto ? <span id={id} className="campo__erro">{texto}</span> : null
}
