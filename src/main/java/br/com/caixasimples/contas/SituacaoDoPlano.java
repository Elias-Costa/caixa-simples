package br.com.caixasimples.contas;

/**
 * Onde a Conta está no ciclo mensal do plano pago, no dia de hoje do balcão.
 *
 * <p>A tolerância depois do vencimento é igual ao aviso antes dele: sete dias. Com vencimento no
 * dia 10, o aviso começa no dia 3, os recursos pagos seguem até o dia 17 e ficam suspensos a
 * partir do dia 18, se não houver renovação.
 */
public enum SituacaoDoPlano {

    /** Plano gratuito: não há o que vencer. */
    SEM_MENSALIDADE,

    /** Mais de sete dias até o vencimento. */
    EM_DIA,

    /** Do sétimo dia antes do vencimento até a véspera: o administrador vê o aviso. */
    A_VENCER,

    /** Do dia do vencimento ao sétimo dia depois dele, com os recursos pagos ainda ativos. */
    VENCIDO,

    /** A partir do oitavo dia depois do vencimento: os recursos pagos param até a renovação. */
    SUSPENSO
}
