import { useEffect, useRef, useState } from 'react'
import { hojeNoBalcao } from '../dataDoBalcao'
import { conferirRegistro } from '../offline/fila'
import type { VendaDaFila } from '../offline/registrosDaFila'
import { erroDeCadastro } from '../telas/erroDeCadastro'
import { textoDasOutrasRecusadas, textoDaVendaRecusada } from './vendaRecusada'

/**
 * A venda feita sem rede que o servidor recusou, dita na tela em que ela faria falta: sem isto, ela
 * some da lista, ou volta aberta, e a sobra na gaveta aparece no fechamento sem nada que a explique.
 *
 * Mostra uma por vez, a mais antiga que falta conferir. Conferir aqui é o mesmo da tela Sincronização:
 * registra que quem operou viu, e não desfaz nem refaz nada; por isso o número do cabeçalho baixa
 * junto. O servidor continua guardando a recusa na lista da Conta.
 */
export function FaixaDaVendaRecusada({ vendas }: { vendas: readonly VendaDaFila[] }) {
  const porConferir = vendas.filter((venda) => !venda.conferida)
  const venda = porConferir.at(0)
  const botao = useRef<HTMLButtonElement>(null)
  const [conferindo, setConferindo] = useState(false)
  const [erro, setErro] = useState<string>()
  const voltarOFoco = useRef(false)

  // Quem acabou de conferir continua no teclado: o foco passa ao botão da venda seguinte.
  useEffect(() => {
    if (voltarOFoco.current) botao.current?.focus()
    voltarOFoco.current = false
  }, [venda?.vendaId])

  if (!venda) return null

  async function conferir(vendaId: string) {
    setConferindo(true)
    setErro(undefined)
    try {
      voltarOFoco.current = true
      await conferirRegistro(vendaId)
      // Sem outra faixa, o botão some: o foco vai ao título da tela, em vez de cair no fim da página.
      if (porConferir.length === 1) document.querySelector<HTMLElement>('.conteudo .titulo')?.focus()
    } catch (falha) {
      voltarOFoco.current = false
      setErro(erroDeCadastro(falha))
    } finally {
      setConferindo(false)
    }
  }

  const outras = textoDasOutrasRecusadas(porConferir.length - 1)
  return (
    <div className="faixa faixa--alerta faixa--longa" role="status">
      <span>
        {textoDaVendaRecusada(venda, hojeNoBalcao())}{outras && ` ${outras}`}
        {erro && ` ${erro}`}
      </span>
      <button ref={botao} type="button" className="botao" disabled={conferindo}
        onClick={() => void conferir(venda.vendaId)}>
        {conferindo ? 'Conferindo' : 'Conferir'}
      </button>
    </div>
  )
}
