package br.com.caixasimples.pagamentos;

/**
 * Como o dinheiro de uma venda entra.
 *
 * <p>Uma venda pode ser dividida entre mais de uma forma (RF09), então isto nomeia a parcela e não
 * a venda: metade em dinheiro e metade no cartão são dois pagamentos da mesma venda, cada um com o
 * seu valor.
 *
 * <p>Este é o vocabulário do domínio e não depende de quanto dele já foi implementado: um valor
 * existe aqui porque o negócio o reconhece, não porque haja uma estratégia atendendo-o. Pedir uma
 * forma sem estratégia registrada estoura em {@code PaymentService}, e essa falha alta é preferível
 * a confundir forma não implementada com pagamento recusado pela operadora.
 *
 * <p>Fica na raiz do módulo, como {@code cadastro.TipoProduto} e {@code caixa.StatusSessaoCaixa},
 * porque é vocabulário que o módulo de vendas precisa nomear ao registrar um pagamento.
 */
public enum FormaPagamento {
    DINHEIRO,
    PIX,
    CARTAO
}
