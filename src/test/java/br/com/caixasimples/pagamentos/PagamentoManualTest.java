package br.com.caixasimples.pagamentos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import br.com.caixasimples.TesteDeIntegracao;
import br.com.caixasimples.pagamentos.application.PaymentService;
import br.com.caixasimples.pagamentos.domain.ResultadoPagamento;
import br.com.caixasimples.pagamentos.domain.SolicitacaoPagamento;
import br.com.caixasimples.shared.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Pix e cartão lançados à mão pela porta pública do módulo, com a aplicação de pé (RF26).
 *
 * <p>Prova a fiação, que nenhum teste de unidade alcança: as duas estratégias têm visibilidade de
 * pacote e só chegam ao serviço porque o Spring as registrou. A conta que elas fazem já está
 * coberta por teste de unidade sobre o domínio.
 *
 * <p>Não toca em banco e não precisa de tenant: nada deste módulo é persistido ainda. A parcela de
 * pagamento vira linha quando o agregado Venda, que é o dono dela, existir.
 */
class PagamentoManualTest extends TesteDeIntegracao {

    @Autowired
    private PaymentService pagamentos;

    @Test
    @DisplayName("as três formas de pagamento resolvem pela mesma interface")
    void asTresFormasResolvemPelaMesmaInterface() {
        // O critério do desenho (RF09): quem paga chama sempre o mesmo método, e a forma escolhe a
        // estratégia sem que nenhuma condição sobre ela exista em código de negócio.
        Money valor = Money.de("30.00");

        assertThat(pagamentos.pagar(SolicitacaoPagamento.emDinheiro(valor, valor)).forma())
                .isEqualTo(FormaPagamento.DINHEIRO);
        assertThat(pagamentos.pagar(SolicitacaoPagamento.de(FormaPagamento.PIX, valor)).forma())
                .isEqualTo(FormaPagamento.PIX);
        assertThat(pagamentos.pagar(SolicitacaoPagamento.de(FormaPagamento.CARTAO, valor)).forma())
                .isEqualTo(FormaPagamento.CARTAO);
    }

    @Test
    @DisplayName("Pix lançado à mão nasce CONFIRMADO, sem esperar provedor")
    void pixManualNasceConfirmado() {
        ResultadoPagamento resultado =
                pagamentos.pagar(SolicitacaoPagamento.de(FormaPagamento.PIX, Money.de("42.50")));

        assertThat(resultado.status()).isEqualTo(StatusPagamento.CONFIRMADO);
        assertThat(resultado.valor()).isEqualTo(Money.de("42.50"));
        assertThat(resultado.troco()).isEqualTo(Money.ZERO);
    }

    @Test
    @DisplayName("cartão lançado à mão nasce CONFIRMADO, sem maquininha")
    void cartaoManualNasceConfirmado() {
        ResultadoPagamento resultado =
                pagamentos.pagar(SolicitacaoPagamento.de(FormaPagamento.CARTAO, Money.de("42.50")));

        assertThat(resultado.status()).isEqualTo(StatusPagamento.CONFIRMADO);
        assertThat(resultado.valor()).isEqualTo(Money.de("42.50"));
        assertThat(resultado.troco()).isEqualTo(Money.ZERO);
    }

    @Test
    @DisplayName("informar dinheiro entregue numa forma sem troco é recusado pela porta pública")
    void recusaValorRecebidoEmFormaSemTroco() {
        assertThatIllegalArgumentException().isThrownBy(() -> pagamentos.pagar(
                new SolicitacaoPagamento(FormaPagamento.PIX, Money.de("42.50"),
                        Money.de("50.00"))));
    }
}
