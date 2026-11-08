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

/**
 * No lugar da lista de movimentos do caixa fechado que o aparelho conhece só pelo resumo: ele não guarda
 * o extrato, e uma lista vazia diria que o caixa não teve movimento.
 */
export function extratoSemServidor(conexao: SemServidor): string {
  return conexao === 'sem-rede'
    ? 'Sem conexão. O extrato deste caixa aparece quando a rede voltar.'
    : 'Sem resposta do servidor. O extrato deste caixa aparece quando ele responder.'
}
