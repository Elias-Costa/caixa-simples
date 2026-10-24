import { useEffect, useState } from 'react'
import type { SessaoCaixa } from '../api/caixa'
import { abertaAntesDeHoje, FUSO_DO_BALCAO } from '../dataDoBalcao'
import { criarCaixaLocal } from '../offline/caixaLocal'

const caixa = criarCaixaLocal()

const diaEHora = new Intl.DateTimeFormat('pt-BR', {
  timeZone: FUSO_DO_BALCAO, day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit',
})

/** 23/10 às 18:02, no fuso do balcão. */
function quando(abertaEm: string): string {
  const partes = diaEHora.formatToParts(new Date(abertaEm))
  const campo = (tipo: string) => partes.find((parte) => parte.type === tipo)?.value ?? ''
  return `${campo('day')}/${campo('month')} às ${campo('hour')}:${campo('minute')}`
}

/** O texto da faixa, ou nada quando o caixa de quem está operando abriu hoje ou não está aberto. */
export function avisoDoCaixaDeOutroDia(sessao: SessaoCaixa | undefined): string | null {
  if (!sessao || sessao.status !== 'ABERTA' || !abertaAntesDeHoje(sessao.abertaEm)) return null
  return `Seu caixa está aberto desde ${quando(sessao.abertaEm)}. As vendas de hoje entram nele até você fechá-lo.`
}

/**
 * O aviso do caixa de quem está operando aberto desde um dia anterior. A sessão vem do caixa do
 * aparelho, que responde também sem rede, e é relida a cada troca de tela, a cada rodada da fila e na
 * volta à tela: o tablet aberto o expediente inteiro passa da meia-noite sem trocar de tela. Nada é
 * bloqueado; a venda continua na sessão aberta.
 */
export function useAvisoDoCaixaDeOutroDia(quem: string | null, rodada: number, caminho: string): string | null {
  const [sessao, setSessao] = useState<SessaoCaixa>()
  const [voltas, setVoltas] = useState(0)

  useEffect(() => {
    const aoVoltarATela = () => { if (document.visibilityState === 'visible') setVoltas((atual) => atual + 1) }
    document.addEventListener('visibilitychange', aoVoltarATela)
    return () => document.removeEventListener('visibilitychange', aoVoltarATela)
  }, [])

  useEffect(() => {
    if (!quem) return
    let ativo = true
    caixa.abertaDoOperadorAtual().then((lida) => { if (ativo) setSessao(lida) })
      // Sem a leitura, fica o último aviso: a próxima tela tenta de novo.
      .catch(() => undefined)
    return () => { ativo = false }
  }, [quem, rodada, caminho, voltas])

  return avisoDoCaixaDeOutroDia(sessao)
}
