package br.com.caixasimples.caixa;

/**
 * O que fez o dinheiro entrar ou sair do caixa.
 *
 * <p>Uma tabela so para os tres, e nao tres tabelas (modelo de dados §5): assim o fechamento e uma
 * soma sobre um lugar unico.
 *
 * <p>Vocabulario do CLAUDE.md, e vale a pena repetir porque os tres se confundem no dia a dia:
 * <strong>SANGRIA</strong> e retirada de dinheiro do caixa — nao e estorno nem despesa;
 * <strong>SUPRIMENTO</strong> e reforco de troco — nao e venda nem receita; <strong>VENDA</strong> e
 * o dinheiro que entra por uma venda, e e o unico dos tres que aponta para uma {@code venda}.
 *
 * <p>O enum <strong>nao carrega o sinal</strong> do movimento. Quem decide se soma ou subtrai e o
 * {@code switch} exaustivo dentro de {@code SessaoCaixa}, para que acrescentar um valor aqui vire
 * erro de compilacao la, e nao uma soma silenciosamente errada.
 */
public enum TipoMovimentoCaixa {
    VENDA,
    SANGRIA,
    SUPRIMENTO
}
