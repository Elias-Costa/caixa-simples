import { useEffect, useId, useRef } from 'react'

/** A pergunta é o título; o texto diz o que muda e o que não se desfaz. */
export type Pergunta = {
  titulo: string
  texto: string
  /** O verbo da ação, como Cancelar venda ou Fechar caixa: o botão diz o que o toque faz. */
  acao: string
}

type Props = {
  pergunta: Pergunta
  aoResponder: (sim: boolean) => void
}

const SIM = 'sim'

/**
 * O diálogo modal da confirmação, aberto ao montar. Toda confirmação do aplicativo é de algo que
 * destrói ou não se desfaz, então a ação tem sempre a borda de perigo.
 */
export function DialogoDeConfirmacao({ pergunta, aoResponder }: Props) {
  const dialogo = useRef<HTMLDialogElement>(null)
  const voltar = useRef<HTMLButtonElement>(null)
  const quemAbriu = useRef<HTMLElement | null>(null)
  const titulo = useId()
  const texto = useId()

  useEffect(() => {
    // Já aberto é a segunda execução do efeito no modo estrito do React: o foco já está no Voltar,
    // e quem abriu, guardado na primeira, seria trocado por ele.
    const elemento = dialogo.current
    if (!elemento || elemento.open) return
    quemAbriu.current = document.activeElement instanceof HTMLElement ? document.activeElement : null
    elemento.showModal()
    // O foco começa no lado seguro: o Enter, inclusive o que o leitor de código manda, não confirma.
    voltar.current?.focus()
  }, [])

  // Toda resposta passa pelo fechamento do diálogo: os dois botões fecham com o seu valor, e o Esc
  // fecha sem valor, que vale como Voltar. O foco volta a quem abriu, para a pessoa não se perder.
  function aoFechar() {
    const sim = dialogo.current?.returnValue === SIM
    if (quemAbriu.current?.isConnected) quemAbriu.current.focus()
    aoResponder(sim)
  }

  return <dialog ref={dialogo} className="confirmacao" role="alertdialog" aria-labelledby={titulo}
    aria-describedby={texto} onClose={aoFechar}>
    <h2 id={titulo} className="confirmacao__titulo">{pergunta.titulo}</h2>
    <p id={texto} className="confirmacao__texto">{pergunta.texto}</p>
    <div className="confirmacao__acoes">
      <button ref={voltar} type="button" className="botao botao--secundario"
        onClick={() => dialogo.current?.close()}>
        Voltar
      </button>
      <button type="button" className="botao botao--perigo" onClick={() => dialogo.current?.close(SIM)}>
        {pergunta.acao}
      </button>
    </div>
  </dialog>
}
