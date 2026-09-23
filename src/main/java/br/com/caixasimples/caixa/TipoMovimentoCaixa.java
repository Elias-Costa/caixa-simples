package br.com.caixasimples.caixa;

/**
 * O que fez o dinheiro entrar ou sair do caixa.
 *
 * <p>Uma tabela só para os quatro, e não quatro tabelas: assim o fechamento do caixa é uma soma
 * sobre um lugar único, em vez de uma junção.
 *
 * <p>Vocabulário do domínio, e vale repetir porque eles se confundem no dia a dia.
 * <strong>SANGRIA</strong> é retirada de dinheiro do caixa, não é estorno nem despesa.
 * <strong>SUPRIMENTO</strong> é reforço de troco, não é venda nem receita.
 * <strong>VENDA</strong> é o dinheiro que entra por uma venda concluída.
 * <strong>ESTORNO</strong> é o mesmo dinheiro saindo quando a venda é cancelada (RF12): espelha
 * o valor da VENDA que desfaz, e por isso nunca é lançado à mão. VENDA e ESTORNO são os dois que
 * apontam para uma {@code venda}, e os dois que dispensam motivo, porque a venda já os explica.
 *
 * <p>Um cancelamento gera um ESTORNO, e não apaga a VENDA: movimento lançado não se edita, o que
 * se faz é lançar o oposto, para o histórico continuar contando que o dinheiro entrou e saiu.
 *
 * <p>O enum <strong>não carrega o sinal</strong> do movimento. Quem decide se soma ou subtrai é o
 * {@code switch} exaustivo dentro de {@code SessaoCaixa}, para que acrescentar um valor aqui vire
 * erro de compilação lá, e não uma soma silenciosamente errada.
 */
public enum TipoMovimentoCaixa {
    VENDA,
    SANGRIA,
    SUPRIMENTO,
    ESTORNO,
    RECEBIMENTO
}
