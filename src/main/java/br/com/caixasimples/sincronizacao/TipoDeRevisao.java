package br.com.caixasimples.sincronizacao;

/**
 * Por que um gesto aplicado foi para revisão, para o dispositivo saber quem pode agir sobre ela.
 *
 * <p>O motivo escrito continua sendo o que a tela mostra; o tipo existe porque nem toda revisão é
 * de quem operou. O estoque que ficou negativo é conferido por quem ajusta estoque, e o operador não
 * ajusta (RF29): o aparelho dele não precisa mostrá-la, e a lista da Conta, do administrador, a
 * mostra. Uma operação pode ter mais de um tipo, como a conclusão que deixou o estoque negativo e
 * chegou com o relógio adiantado.
 */
public enum TipoDeRevisao {

    /** O item foi cobrado pelo preço visto no balcão, diferente do preço atual. */
    PRECO_DIFERENTE,

    /** A parcela em cartão chegou sem o NSU que a Conta exige. */
    NSU_AUSENTE,

    /** A baixa da venda deixou o saldo de algum produto abaixo de zero. */
    ESTOQUE_NEGATIVO,

    /** O instante do gesto passou da tolerância do relógio do servidor. */
    RELOGIO_ADIANTADO
}
