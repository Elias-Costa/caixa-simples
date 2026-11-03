import { useState } from 'react'

const CARTOES = [
  {
    titulo: 'Abra o caixa',
    texto: 'Antes da primeira venda do dia, vá a Caixa, conte o dinheiro da gaveta e informe o valor '
      + 'inicial. Sem troco, digite 0.',
  },
  {
    titulo: 'Venda',
    texto: 'Em Vender, busque o produto pelo nome ou pelo código, lance os itens e registre o '
      + 'pagamento. Sem internet, a venda fica guardada no aparelho e segue quando a rede voltar.',
  },
  {
    titulo: 'Feche o caixa',
    texto: 'No fim do expediente, volte a Caixa, conte a gaveta e informe o valor contado. O sistema '
      + 'mostra se faltou ou sobrou dinheiro.',
  },
] as const

type Props = {
  /** Pular ou começar a usar: quem mostra o painel decide o que vem depois. */
  aoTerminar: () => void
  /** Os passos que a pessoa já cumpriu, na ordem dos cartões; sem ele, nenhum conta como feito. */
  feitos?: readonly boolean[]
}

/** O primeiro cartão ainda por fazer; com todos feitos, o último. */
function primeiroPorFazer(feitos: readonly boolean[] | undefined): number {
  const indice = feitos ? feitos.findIndex((feito) => !feito) : 0
  return indice < 0 ? CARTOES.length - 1 : indice
}

/**
 * Os primeiros passos do balcão em três cartões, num painel no alto da tela, e não num diálogo: a
 * tela continua à mão embaixo, e quem já sabe pula com um toque. Abre no primeiro passo por fazer e
 * marca os feitos; o menu reabre os três, sem marca.
 */
export function PrimeirosPassos({ aoTerminar, feitos }: Props) {
  // Só na abertura o cartão segue o que está feito; depois, só Anterior e Próximo o trocam, para o
  // passo cumprido na própria tela continuar à vista, já com a marca.
  const [atual, setAtual] = useState(() => primeiroPorFazer(feitos))
  const cartao = CARTOES[atual]
  const ultimo = atual === CARTOES.length - 1

  return <section className="primeiros-passos" aria-label="Primeiros passos">
    <p className="primeiros-passos__contagem">Primeiros passos ({atual + 1} de {CARTOES.length})</p>
    <div className="primeiros-passos__titulo">
      <h2>{cartao.titulo}</h2>
      {feitos?.[atual] && <span className="marca">Feito</span>}
    </div>
    <p>{cartao.texto}</p>
    <div className="primeiros-passos__acoes">
      <button type="button" className="botao botao--secundario" onClick={aoTerminar}>Pular</button>
      {atual > 0 && <button type="button" className="botao botao--secundario"
        onClick={() => setAtual(atual - 1)}>Anterior</button>}
      {ultimo
        ? <button type="button" className="botao" onClick={aoTerminar}>Começar a usar</button>
        : <button type="button" className="botao" onClick={() => setAtual(atual + 1)}>Próximo</button>}
    </div>
  </section>
}
