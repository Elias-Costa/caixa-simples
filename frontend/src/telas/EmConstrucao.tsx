type Props = {
  titulo: string
}

/** Lugar de uma tela que ainda não existe: o item do menu já leva até aqui. */
export function EmConstrucao({ titulo }: Props) {
  return (
    <section>
      <h2 className="titulo">{titulo}</h2>
      <p>Esta tela ainda não está disponível.</p>
    </section>
  )
}
