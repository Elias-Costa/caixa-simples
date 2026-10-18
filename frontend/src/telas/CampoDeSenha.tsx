import { useId, useState } from 'react'

type Props = {
  rotulo: string
  valor: string
  aoMudar: (valor: string) => void
  autoComplete?: 'current-password' | 'new-password'
  name?: string
  minLength?: number
}

/**
 * Campo de senha com o botão de mostrar o que foi digitado. A senha tem ao menos quinze caracteres,
 * e uma frase desse tamanho digitada no teclado de um tablet erra fácil; conferir com os olhos evita
 * a recusa sem saber onde foi o erro. O botão fica fora do rótulo, para o nome do campo continuar
 * sendo só o rótulo.
 */
export function CampoDeSenha({ rotulo, valor, aoMudar, autoComplete, name, minLength }: Props) {
  const id = useId()
  const [visivel, setVisivel] = useState(false)
  return <div className="campo">
    <label className="campo__rotulo" htmlFor={id}>{rotulo}</label>
    <div className="campo__senha">
      <input id={id} className="campo__entrada" type={visivel ? 'text' : 'password'} name={name}
        autoComplete={autoComplete} minLength={minLength} value={valor} required
        onChange={(evento) => aoMudar(evento.target.value)} />
      <button type="button" className="botao botao--secundario" aria-controls={id}
        aria-pressed={visivel} onClick={() => setVisivel((antes) => !antes)}>
        {visivel ? 'Ocultar' : 'Mostrar'}
      </button>
    </div>
  </div>
}
