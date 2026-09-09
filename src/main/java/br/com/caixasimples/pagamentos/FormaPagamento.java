package br.com.caixasimples.pagamentos;

/**
 * Como o dinheiro de uma venda entra.
 *
 * <p>Uma venda pode ser dividida entre mais de uma forma (RF09), então isto nomeia a parcela e não
 * a venda: metade em dinheiro e metade no cartão são dois pagamentos da mesma venda, cada um com o
 * seu valor.
 *
 * <p>Os três valores existem desde já, mesmo antes de haver uma implementação para cada um. Pedir
 * uma forma que ainda não tem estratégia estoura em {@code PaymentService}, e essa falha alta é
 * preferível a um enum que cresce junto com o código: o vocabulário do domínio não depende de
 * quanto dele já foi implementado.
 *
 * <p>Fica na raiz do módulo, como {@code cadastro.TipoProduto} e {@code caixa.StatusSessaoCaixa},
 * porque é vocabulário que o módulo de vendas precisa nomear ao registrar um pagamento.
 */
public enum FormaPagamento {
    DINHEIRO,
    PIX,
    CARTAO
}
