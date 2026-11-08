import { useId, useState, type KeyboardEvent, type Ref } from 'react'
import { ErroDoCampo } from './Mensagem'

type Props = {
  rotulo: string
  valor: string
  aoMudar: (valor: string) => void
  autoComplete?: 'current-password' | 'new-password'
  name?: string
  minLength?: number
  /** O que impede enviar, dito embaixo do campo, como a entrada faz com o campo em branco. */
  erro?: string
  entrada?: Ref<HTMLInputElement>
}

/**
 * Campo de senha com o botão de mostrar o que foi digitado. A senha tem ao menos quinze caracteres,
 * e uma frase desse tamanho digitada no teclado de um tablet erra fácil; conferir com os olhos evita
 * a recusa sem saber onde foi o erro. O botão é um olho dentro do campo, para o campo de senha ter a
 * largura dos outros, e fica fora do rótulo, para o nome do campo continuar sendo só o rótulo. O nome
 * dele não muda com o estado: quem diz se a senha está à vista é o aria-pressed, e um nome que virasse
 * Ocultar seria lido como Ocultar, pressionado.
 *
 * O aviso do Caps Lock fica sempre montado, e só o texto muda, para o leitor de tela anunciá-lo. É uma
 * região ao vivo sem o papel status: esse papel é o da mensagem da tela, que o leitor de tela lê como a
 * barra de status, e cada campo de senha somaria um status vazio a ela. O navegador só conta o estado
 * da tecla num toque de teclado; no teclado virtual, sem a tecla, o aviso não aparece.
 */
export function CampoDeSenha({ rotulo, valor, aoMudar, autoComplete, name, minLength, erro, entrada }: Props) {
  const id = useId()
  const idDoErro = `${id}-erro`
  const idDoAviso = `${id}-aviso`
  const [visivel, setVisivel] = useState(false)
  const [capsLock, setCapsLock] = useState(false)

  function lerCapsLock(evento: KeyboardEvent<HTMLInputElement>) {
    setCapsLock(evento.getModifierState('CapsLock'))
  }

  // O campo é descrito só pelo que está escrito embaixo dele, sem o aviso vazio.
  const descricao = [erro ? idDoErro : null, capsLock ? idDoAviso : null].filter((parte) => parte !== null).join(' ')

  return <div className="campo">
    <label className="campo__rotulo" htmlFor={id}>{rotulo}</label>
    <div className="campo__senha">
      <input ref={entrada} id={id} className="campo__entrada" type={visivel ? 'text' : 'password'} name={name}
        autoComplete={autoComplete} minLength={minLength} value={valor} required
        aria-invalid={erro ? true : undefined}
        aria-describedby={descricao || undefined}
        onChange={(evento) => aoMudar(evento.target.value)}
        onKeyDown={lerCapsLock} onKeyUp={lerCapsLock} onBlur={() => setCapsLock(false)} />
      <button type="button" className="campo__olho" aria-label="Mostrar a senha" aria-controls={id}
        aria-pressed={visivel} onClick={() => setVisivel((antes) => !antes)}>
        <IconeDoOlho riscado={visivel} />
      </button>
    </div>
    <ErroDoCampo id={idDoErro} texto={erro} />
    <span id={idDoAviso} className="campo__aviso" aria-live="polite">{capsLock ? 'Caps Lock ligado.' : ''}</span>
  </div>
}

/**
 * Desenhado aqui, sem biblioteca de ícones: é o único ícone dentro das telas. Riscado quando a senha
 * está à vista, porque o traço diz que o próximo toque esconde.
 */
function IconeDoOlho({ riscado }: { riscado: boolean }) {
  return <svg className="campo__olho-icone" viewBox="0 0 24 24" width="24" height="24" aria-hidden="true"
    focusable="false" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round"
    strokeLinejoin="round">
    <path d="M2 12s3.6-7 10-7 10 7 10 7-3.6 7-10 7S2 12 2 12Z" />
    <circle cx="12" cy="12" r="3" />
    {riscado && <path d="M4 4l16 16" />}
  </svg>
}
