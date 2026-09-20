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
 * <p><strong>CANCELADA</strong> é a venda desfeita (RF12), e é estado final. Tanto a comanda
 * ABERTA abandonada quanto a venda CONCLUIDA cancelada chegam aqui; a diferença é que só a segunda
 * tinha produzido efeito fora do módulo, e só ela publica o evento que devolve o dinheiro ao caixa
 * e os itens ao estoque.
 *
 * <p>As transições que existem em código: de ABERTA para CONCLUIDA, por {@code Venda.concluir},
 * um passo à parte de registrar a parcela que fecha a conta; e de ABERTA ou CONCLUIDA para
 * CANCELADA, por {@code Venda.cancelar}. Nenhuma venda sai de CANCELADA.
 *
 * <p>Fica na raiz do módulo, como {@code caixa.StatusSessaoCaixa} e
 * {@code pagamentos.FormaPagamento}, porque é vocabulário que outros módulos vão precisar nomear.
 */
public enum StatusVenda {
    ABERTA,
    CONCLUIDA,
    CANCELADA
}
