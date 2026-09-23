package br.com.caixasimples.pagamentos;

/**
 * Em que ponto está a parcela de pagamento de uma venda.
 *
 * <p><strong>PENDENTE</strong> vale para FIADO até seus recebimentos quitarem a parcela (RF33) e,
 * futuramente, para cobrança de Pix gerada por provedor. Dinheiro, cartão e Pix lançados à mão
 * nascem <strong>CONFIRMADO</strong> após conferência no balcão.
 *
 * <p><strong>RECUSADO</strong> é desfecho vindo de fora, como um cartão negado pela operadora ou
 * uma cobrança Pix expirada. Erro de digitação não vira RECUSADO: valor recebido menor que a
 * parcela em dinheiro, por exemplo, é recusado na entrada, com exceção, antes de existir resultado
 * nenhum.
 *
 * <p>Fica na raiz do módulo, junto de {@link FormaPagamento}, porque é a mesma coisa: vocabulário
 * que o módulo de vendas nomeia; uma Venda CONCLUIDA é coberta por pagamentos CONFIRMADO e FIADO
 * PENDENTE (RF09, RF33).
 */
public enum StatusPagamento {
    PENDENTE,
    CONFIRMADO,
    RECUSADO
}
