package br.com.caixasimples.contas;

/**
 * O que um plano pago libera além do caixa básico. Cadastro, Venda, caixa e a baixa de estoque
 * pela Venda não estão aqui: continuam funcionando em qualquer plano, inclusive suspenso, para
 * não interromper o balcão nem corromper o saldo.
 *
 * <p>Os módulos donos perguntam pelo recurso depois do perfil e antes da própria regra, de modo
 * que o operador recebe a recusa de perfil, e a Conta sem o recurso, a recusa do plano.
 */
public enum RecursoDoPlano {

    /** Faturamento, mais vendidos e fluxo de caixa (RF21 a RF24). */
    RELATORIOS,

    /** Ajuste, estoque mínimo e consulta de saldo, e ligar o controle de estoque (RF17, RF19, RF20). */
    ESTOQUE,

    /** Criar mais usuários na Conta (RF29). Os que já existem continuam entrando. */
    MULTIUSUARIO
}
