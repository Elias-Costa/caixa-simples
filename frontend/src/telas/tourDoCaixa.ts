import { useEffect, useState } from 'react'
import type { SessaoCaixa } from '../api/caixa'
import type { ResumoDaVenda } from '../api/vendas'
import type { Identidade } from '../sessao/Identidade'
import { marcarTourVisto, tourVisto } from './marcaDoTour'

type Entrada = {
  identidade: Pick<Identidade, 'contaId' | 'usuarioId'> | null
  /** O caixa aberto de quem entrou, como a tela o leu. */
  aberta: SessaoCaixa | undefined
  /** Os caixas do dia que a tela mostra; para o administrador, os de todos. */
  historico: SessaoCaixa[]
  carregando: boolean
  /** Uma função que não muda de uma renderização para outra: ela é dependência da leitura. */
  vendasDoCaixa: (sessaoId: string) => Promise<ResumoDaVenda[]>
}

/** Se o caixa aberto já vendeu, e de qual caixa é a resposta: nulo quando não havia caixa aberto. */
type Vendeu = { caixaId: string | null; vendeu: boolean }

/**
 * Os primeiros passos da tela Caixa, aonde a entrada leva quem está sem caixa aberto. Cada passo
 * conta como feito pelo estado do caixa de quem entrou, e não pelo cartão que a pessoa passou: Abra o
 * caixa, com um caixa aberto; Venda, com uma venda dela concluída nele; Feche o caixa, com um caixa dessa
 * pessoa fechado no dia que a tela mostra, e aí o tour termina sozinho e não volta.
 *
 * As vendas do caixa aberto têm leitura própria, e só enquanto o tour não terminou, porque a lista
 * da tela é a do caixa escolhido, que o administrador troca. O cartão espera a primeira resposta
 * para abrir no passo certo; depois dela, a resposta guardada só é trocada, nunca apagada, e abrir o
 * caixa na própria tela não desmonta o cartão nem tira a pessoa do passo em que está.
 */
export function useTourDoCaixa({ identidade, aberta, historico, carregando, vendasDoCaixa }: Entrada) {
  const [fechadoAgora, setFechadoAgora] = useState(false)
  const [vendeu, setVendeu] = useState<Vendeu>()
  const meuCaixaId = aberta?.id ?? null
  const pendente = identidade !== null && !fechadoAgora && !tourVisto(identidade)
  const fechou = identidade !== null && historico.some((sessao) =>
    sessao.usuarioId === identidade.usuarioId && sessao.status === 'FECHADA')

  const usuarioId = identidade?.usuarioId
  useEffect(() => {
    if (!pendente || carregando) return
    let ativo = true
    // A venda que o administrador fez no caixa desta pessoa não conta: o passo é ela vender.
    const pergunta = meuCaixaId === null ? Promise.resolve(false)
      : vendasDoCaixa(meuCaixaId).then((lista) => lista.some((venda) => venda.status === 'CONCLUIDA'
        && venda.usuarioId === usuarioId))
    // Sem a resposta, o passo da venda fica por fazer: o cartão é um aviso, e o erro já aparece na tela.
    void pergunta.catch(() => false).then((resposta) => {
      if (ativo) setVendeu({ caixaId: meuCaixaId, vendeu: resposta })
    })
    return () => { ativo = false }
  }, [pendente, carregando, meuCaixaId, vendasDoCaixa, usuarioId])

  useEffect(() => {
    if (fechou && pendente && identidade) marcarTourVisto(identidade)
  }, [fechou, pendente, identidade])

  function fechar() {
    if (identidade) marcarTourVisto(identidade)
    setFechadoAgora(true)
  }

  const feitos = [
    meuCaixaId !== null,
    meuCaixaId !== null && vendeu?.caixaId === meuCaixaId && vendeu.vendeu,
    fechou,
  ]
  return { mostrar: pendente && !carregando && vendeu !== undefined && !fechou, feitos, fechar }
}
