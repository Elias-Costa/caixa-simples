package br.com.caixasimples.cadastro;

/**
 * Distingue o que se estoca do que se presta.
 *
 * <p>A diferenca pratica e uma so: <strong>SERVICO nunca gera MovimentoEstoque</strong> (modelo de
 * dados §3). Nao sao duas tabelas nem duas entidades — um salao e uma cafeteria usam o mesmo
 * cadastro, que e a premissa do nucleo generico (escopo §1).
 *
 * <p>Fica na raiz do modulo, como {@code contas.Plano} e {@code contas.Perfil}: e vocabulario que
 * outros modulos podem precisar nomear.
 */
public enum TipoProduto {
    PRODUTO,
    SERVICO
}
