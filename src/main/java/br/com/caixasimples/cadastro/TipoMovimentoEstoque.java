package br.com.caixasimples.cadastro;

/**
 * O que fez o estoque de um produto subir ou descer.
 *
 * <p>Uma tabela só para os três, como no caixa: o saldo do produto é uma soma assinada sobre um
 * lugar único.
 *
 * <p><strong>SAIDA</strong> é o que a venda concluída faz (RF18): subtrai. <strong>ENTRADA</strong>
 * é o que o cancelamento dessa venda faz (RF12): devolve a mesma quantidade, e soma. Os dois
 * apontam para a venda, e a SAIDA e a ENTRADA de uma mesma venda convivem no histórico, porque
 * movimento lançado não se edita, o que se faz é lançar o oposto. <strong>AJUSTE</strong> é o
 * acerto manual com motivo obrigatório (RF19): perda, quebra, contagem.
 *
 * <p>O enum <strong>não carrega o sinal</strong> do movimento. ENTRADA e SAIDA gravam quantidade
 * positiva, e quem soma ou subtrai é a raiz {@code Produto}. AJUSTE é a exceção: serve para os
 * dois sentidos, então o sinal vai na quantidade, negativa numa perda, positiva numa contagem que
 * achou mais do que o registrado.
 *
 * <p>Fica na raiz do módulo, como {@link TipoProduto}, porque o módulo de estoque precisa
 * nomeá-lo.
 */
public enum TipoMovimentoEstoque {
    ENTRADA,
    SAIDA,
    AJUSTE
}
