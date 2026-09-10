package br.com.caixasimples.cadastro;

/**
 * Distingue o que se estoca do que se presta.
 *
 * <p>A diferença prática é uma só: <strong>SERVICO nunca gera MovimentoEstoque</strong>. Não são
 * duas tabelas nem duas entidades, porque um salão e uma cafeteria usam o mesmo cadastro, que é a
 * premissa do núcleo genérico.
 *
 * <p>Fica na raiz do módulo, como {@code contas.Plano} e {@code contas.Perfil}, porque é
 * vocabulário que outros módulos podem precisar nomear.
 */
public enum TipoProduto {
    PRODUTO,
    SERVICO
}
