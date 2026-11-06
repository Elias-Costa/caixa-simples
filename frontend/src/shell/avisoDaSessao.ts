import { useEffect, useState } from 'react'
import type { EstadoDaConexao } from '../api/conexao'
import { lerToken } from '../sessao/armazenamento'
import { restanteDoToken } from '../sessao/token'

const UMA_HORA = 60 * 60 * 1000

/**
 * A partir de quando o aviso aparece. Com menos que um dia, quem fecha à noite e abre de manhã só
 * descobriria já vencido; assim, ele vê o aviso no expediente anterior e ainda pode conectar.
 */
const AVISO_A_PARTIR_DE = 24 * UMA_HORA

/** A hora do aviso só desce uma vez por hora, e um minuto de atraso nela não muda nada. */
const INTERVALO_DA_LEITURA = 60_000

/**
 * O aviso de que a sessão está para vencer, ou nulo quando não há o que avisar. A hora é arredondada
 * para baixo, para nunca prometer o tempo que não há.
 */
export function avisoDaSessao(restante: number): string | null {
  if (restante <= 0 || restante > AVISO_A_PARTIR_DE) return null
  return `Sessão vence em ${prazo(Math.floor(restante / UMA_HORA))}. Qualquer conexão renova.`
}

function prazo(horas: number): string {
  if (horas === 0) return 'menos de 1 hora'
  return horas === 1 ? '1 hora' : `${horas} horas`
}

function restanteAgora(): number {
  const token = lerToken()
  return token ? restanteDoToken(token) : 0
}

/**
 * O aviso só vale sem resposta do servidor: com ela, a própria chamada já renovou, e um aviso pedindo
 * conexão com a rede ligada não faria sentido.
 */
export function useAvisoDaSessao(conexao: EstadoDaConexao): string | null {
  const [restante, definirRestante] = useState(restanteAgora)

  useEffect(() => {
    const leitura = window.setInterval(() => definirRestante(restanteAgora()), INTERVALO_DA_LEITURA)
    return () => window.clearInterval(leitura)
  }, [])

  return conexao === 'com-servidor' ? null : avisoDaSessao(restante)
}
