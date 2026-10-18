import { useId } from 'react'
import { lerDinheiro } from '../formatos'

type Props = {
  rotulo: string
  valor: string
  aoMudar: (valor: string) => void
  placeholder?: string
  required?: boolean
  disabled?: boolean
}

/** O separador acabou de entrar e os centavos ainda vêm: não é erro, é digitação em curso. */
function digitandoCentavos(texto: string): boolean {
  return /^\d+[,.]$/.test(texto)
}

/**
 * Campo de dinheiro em texto, com o teclado decimal do aparelho. O campo numérico do navegador
 * esconde a vírgula em parte dos teclados e devolve vazio quando não entende o que foi digitado, e o
 * valor seguiria como zero sem aviso. Aqui o texto é o que a pessoa vê, e a tela o lê com o mesmo
 * critério do preço do produto.
 *
 * Só algarismos, vírgula e ponto entram: o valor colado com o símbolo ou com espaços chega limpo. A
 * terceira casa não é cortada, para 1.500 não virar 1.50 sem aviso; a mensagem embaixo diz o que
 * corrigir. O R$ é desenho do CSS na frente do campo, e o texto do rótulo continua sendo só o nome.
 */
export function CampoDeDinheiro({ rotulo, valor, aoMudar, placeholder, required, disabled }: Props) {
  const idDoErro = useId()
  const invalido = valor !== '' && !digitandoCentavos(valor) && lerDinheiro(valor) === null
  return <>
    <label>{rotulo}
      <span className="dinheiro">
        <input type="text" inputMode="decimal" autoComplete="off" value={valor} placeholder={placeholder}
          required={required} disabled={disabled} aria-invalid={invalido || undefined}
          aria-describedby={invalido ? idDoErro : undefined}
          onChange={(evento) => aoMudar(evento.target.value.replace(/[^\d,.]/g, ''))} />
      </span>
    </label>
    {/* Fora do rótulo, para não entrar no nome do campo; o aria-describedby o liga a ele. */}
    {invalido && <span id={idDoErro} className="campo__erro">Use até duas casas decimais, sem ponto de milhar.</span>}
  </>
}
