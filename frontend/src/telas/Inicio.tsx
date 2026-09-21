import { useSessao } from '../sessao/useSessao'

export function Inicio() {
  const { identidade } = useSessao()

  return (
    <section>
      <h2 className="titulo">Olá, {identidade?.nome}</h2>
      <p>Escolha pelo menu o que fazer.</p>
    </section>
  )
}
