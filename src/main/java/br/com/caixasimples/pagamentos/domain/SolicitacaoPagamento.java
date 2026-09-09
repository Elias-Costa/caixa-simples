package br.com.caixasimples.pagamentos.domain;

import br.com.caixasimples.pagamentos.FormaPagamento;
import br.com.caixasimples.shared.Money;
import java.util.Objects;

/**
 * O pedido de pagamento de uma parcela de venda: qual forma, quanto, e quanto o cliente entregou
 * em espécie.
 *
 * <p><strong>É um record, e não três parâmetros soltos</strong>, porque as formas de pagamento
 * precisam de dados diferentes umas das outras. Dinheiro precisa saber o que veio na mão do
 * cliente para calcular o troco; Pix e cartão não têm o que fazer com esse número. Com os dados
 * num tipo só, uma forma nova que precise de mais um campo não muda a assinatura das outras
 * implementações de {@link PaymentStrategy}.
 *
 * <p>Estas guardas valem para toda forma de pagamento, e é por isso que ficam aqui e não na
 * estratégia:
 *
 * <ul>
 *   <li>{@code valor} tem de ser <strong>positivo</strong>. Zero não é pagamento, e negativo seria
 *       devolução, que é outro assunto e não existe no sistema.</li>
 *   <li>{@code valorRecebido} é livre aqui e só faz sentido na forma DINHEIRO. Quem sabe se ele é
 *       obrigatório ou proibido é cada estratégia, porque a resposta muda com a forma.</li>
 * </ul>
 *
 * @param valorRecebido o que o cliente entregou em espécie; nulo em Pix e cartão
 */
public record SolicitacaoPagamento(FormaPagamento forma, Money valor, Money valorRecebido) {

    public SolicitacaoPagamento {
        Objects.requireNonNull(forma, "forma de pagamento nao pode ser nula");
        Objects.requireNonNull(valor, "valor do pagamento nao pode ser nulo");
        if (valor.valor().signum() <= 0) {
            throw new IllegalArgumentException(
                    "valor do pagamento tem de ser positivo, e nao " + valor);
        }
    }

    /** Atalho para as formas que não têm o que fazer com o valor recebido em espécie. */
    public static SolicitacaoPagamento de(FormaPagamento forma, Money valor) {
        return new SolicitacaoPagamento(forma, valor, null);
    }

    /** Atalho para a forma que tem: o valor da parcela e o que veio na mão do cliente. */
    public static SolicitacaoPagamento emDinheiro(Money valor, Money valorRecebido) {
        return new SolicitacaoPagamento(FormaPagamento.DINHEIRO, valor, valorRecebido);
    }
}
