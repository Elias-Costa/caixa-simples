package br.com.caixasimples.pagamentos;

/**
 * Em que ponto está a parcela de pagamento de uma venda.
 *
 * <p><strong>PENDENTE</strong> existe por causa da cobrança de Pix gerada por um provedor, cuja
 * confirmação chega depois, por notificação dele. Tudo o que o operador lança à mão nasce
 * <strong>CONFIRMADO</strong>: dinheiro na gaveta, comprovante da maquininha ou notificação de Pix
 * recebido já conferida na tela, e nenhum segundo momento a esperar.
 *
 * <p><strong>RECUSADO</strong> é desfecho vindo de fora, como um cartão negado pela operadora ou
 * uma cobrança Pix expirada. Erro de digitação não vira RECUSADO: valor recebido menor que a
 * parcela em dinheiro, por exemplo, é recusado na entrada, com exceção, antes de existir resultado
 * nenhum.
 *
 * <p>Fica na raiz do módulo, junto de {@link FormaPagamento}, porque é a mesma coisa: vocabulário
 * que o módulo de vendas nomeia, já que uma venda só se conclui quando a soma dos pagamentos
 * CONFIRMADO fecha com o total (RF09).
 */
public enum StatusPagamento {
    PENDENTE,
    CONFIRMADO,
    RECUSADO
}
