import { useId, useState } from 'react'

type Props = {
  titulo: string
  /** Até três linhas, do registro em textosDeAjuda.ts. */
  ajuda: readonly string[]
}

/**
 * O título da tela com o botão de ajuda ao lado: um toque abre as linhas, outro as fecha. Sem
 * biblioteca e sem diálogo; a ajuda fica logo abaixo do título, onde a pessoa já está olhando.
 */
export function TituloComAjuda({ titulo, ajuda }: Props) {
  const [aberta, setAberta] = useState(false)
  const idDaAjuda = useId()

  return <>
    <div className="titulo-com-ajuda">
      <h2 className="titulo">{titulo}</h2>
      <button type="button" className="botao botao--secundario titulo-com-ajuda__botao"
        aria-label="Ajuda desta tela" aria-expanded={aberta} aria-controls={aberta ? idDaAjuda : undefined}
        onClick={() => setAberta(!aberta)}>?</button>
    </div>
    {aberta && <div id={idDaAjuda} className="mensagem-info titulo-com-ajuda__texto">
      {ajuda.map((linha) => <p key={linha}>{linha}</p>)}
    </div>}
  </>
}
