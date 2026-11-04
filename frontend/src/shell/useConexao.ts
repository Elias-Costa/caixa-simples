import { useSyncExternalStore } from 'react'
import { aoMudarConexao, estadoDaConexao, type EstadoDaConexao } from '../api/conexao'

/**
 * Se o aparelho alcança o servidor: com servidor, sem rede ou sem resposta do servidor. A tela que
 * decide entre chamar o servidor e usar o que está no aparelho pergunta isto, e não só se o
 * navegador tem rede, porque o Wi-Fi ligado não diz que o servidor responde.
 */
export function useConexao(): EstadoDaConexao {
  return useSyncExternalStore(aoMudarConexao, estadoDaConexao)
}
