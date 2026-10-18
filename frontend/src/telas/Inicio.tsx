import { useEffect, useState } from 'react'
import { Navigate } from 'react-router'
import { criarCaixaLocal } from '../offline/caixaLocal'

const caixa = criarCaixaLocal()

/**
 * A entrada leva direto ao trabalho: ao PDV com o caixa aberto, e ao caixa sem ele, para abri-lo.
 * A pergunta vai ao caixa do aparelho, que responde também sem rede; se nem ele responde, a tela do
 * caixa é que mostra o erro.
 */
export function Inicio() {
  const [destino, setDestino] = useState<'/vender' | '/caixa'>()

  useEffect(() => {
    let vivo = true
    caixa.abertaDoOperadorAtual()
      .then((aberta) => { if (vivo) setDestino(aberta ? '/vender' : '/caixa') })
      .catch(() => { if (vivo) setDestino('/caixa') })
    return () => { vivo = false }
  }, [])

  return destino ? <Navigate to={destino} replace /> : <p>Carregando...</p>
}
