package br.com.caixasimples.relatorios.application;

/**
 * De onde veio uma linha das conferências do cartão e do Pix: a parcela de uma venda ou o
 * recebimento de um fiado. As duas conferências batem o mesmo tipo de extrato, o do dia, e as duas
 * juntam as mesmas duas fontes.
 */
public enum OrigemDoLancamento {
    PARCELA, RECEBIMENTO
}
