package br.com.caixasimples.vendas;

/**
 * Em que ponto a venda está.
 *
 * <p><strong>ABERTA</strong> é a comanda em montagem: itens entrando, desconto sendo aplicado,
 * pagamentos sendo lançados. É também o estado da venda que espera a confirmação de um pagamento
 * que chega depois, como uma cobrança de Pix gerada por provedor. Os dois casos são o mesmo estado
 * porque em ambos a venda existe e ainda não está paga.
 *
 * <p><strong>CONCLUIDA</strong> é a venda paga: a soma dos pagamentos confirmados cobre o total
 * (RF09). É o único estado que produz efeito fora do módulo, no caixa e no estoque.
 *
 * <p><strong>CANCELADA</strong> é o cancelamento de uma venda já registrada (RF12), com estorno
 * de estoque quando houver.
 *
 * <p>As transições entre os três ainda não existem em código: chegam com a conclusão e com o
 * cancelamento. Enquanto não chegam, nenhuma venda muda de estado.
 *
 * <p>Fica na raiz do módulo, como {@code caixa.StatusSessaoCaixa} e
 * {@code pagamentos.FormaPagamento}, porque é vocabulário que outros módulos vão precisar nomear.
 */
public enum StatusVenda {
    ABERTA,
    CONCLUIDA,
    CANCELADA
}
