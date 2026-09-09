package br.com.caixasimples.pagamentos;

/**
 * Em que ponto está a parcela de pagamento de uma venda.
 *
 * <p>O estado existe por causa do Pix, que é a única forma cuja confirmação chega depois, por
 * notificação do provedor. Dinheiro e cartão registrado à mão nascem <strong>CONFIRMADO</strong>:
 * quem registra já tem o dinheiro na gaveta ou o comprovante da maquininha na mão, e não há
 * segundo momento a esperar.
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
