package br.com.caixasimples.caixa;

/**
 * O que fez o dinheiro entrar ou sair do caixa.
 *
 * <p>Uma tabela só para os três, e não três tabelas: assim o fechamento do caixa é uma soma sobre
 * um lugar único, em vez de uma junção.
 *
 * <p>Vocabulário do domínio, e vale repetir porque os três se confundem no dia a dia.
 * <strong>SANGRIA</strong> é retirada de dinheiro do caixa, não é estorno nem despesa.
 * <strong>SUPRIMENTO</strong> é reforço de troco, não é venda nem receita.
 * <strong>VENDA</strong> é o dinheiro que entra por uma venda, e é o único dos três que aponta
 * para uma {@code venda}.
 *
 * <p>O enum <strong>não carrega o sinal</strong> do movimento. Quem decide se soma ou subtrai é o
 * {@code switch} exaustivo dentro de {@code SessaoCaixa}, para que acrescentar um valor aqui vire
 * erro de compilação lá, e não uma soma silenciosamente errada.
 */
public enum TipoMovimentoCaixa {
    VENDA,
    SANGRIA,
    SUPRIMENTO
}
