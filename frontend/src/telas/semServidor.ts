import type { EstadoDaConexao } from '../api/conexao'

type SemServidor = Exclude<EstadoDaConexao, 'com-servidor'>

/**
 * O começo das frases que dizem que a lista é só a do aparelho, igual ao aviso do cabeçalho: com o
 * Wi-Fi ligado e o servidor fora, dizer sem conexão confundiria quem vê a rede funcionando.
 */
export function motivoSemServidor(conexao: SemServidor): string {
  return conexao === 'sem-rede' ? 'Sem conexão' : 'Sem resposta do servidor'
}

/**
 * No lugar de dizer que o caixa não tem venda, quando o servidor não foi lido e o aparelho não
 * guarda nenhuma: as feitas com rede estão lá, e a tela não sabe quantas são.
 */
export function vendasSemServidor(conexao: SemServidor): string {
  return conexao === 'sem-rede'
    ? 'Sem conexão. As vendas deste caixa aparecem quando a rede voltar.'
    : 'Sem resposta do servidor. As vendas deste caixa aparecem quando ele responder.'
}
