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
 * Pagar em dinheiro pela porta pública do módulo, com a aplicação de pé (RF10).
 *
 * <p>Existe separado dos testes de unidade porque prova outra coisa: lá a conta do troco está certa
 * e a resolução por forma funciona com estratégias montadas à mão; aqui a estratégia de dinheiro é
 * a de verdade, encontrada pelo serviço porque o Spring a registrou. É a única prova de que a
 * fiação existe, já que a estratégia tem visibilidade de pacote e nenhum teste de unidade alcança.
 *
 * <p>Não toca em banco e não precisa de tenant: nada deste módulo é persistido ainda. A parcela de
 * pagamento vira linha quando o agregado Venda, que é o dono dela, existir.
 *
 * <p>Fica no pacote {@code pagamentos} e enxerga só o que um controller enxergaria: o serviço, o
 * domínio e os enums da raiz.
 */
class PagamentoEmDinheiroTest extends TesteDeIntegracao {

    @Autowired
    private PaymentService pagamentos;

    @Test
    @DisplayName("a estratégia de dinheiro está registrada e devolve o troco")
    void pagaEmDinheiroComTroco() {
        ResultadoPagamento resultado = pagamentos.pagar(
                SolicitacaoPagamento.emDinheiro(Money.de("29.93"), Money.de("50.00")));

        assertThat(resultado.forma()).isEqualTo(FormaPagamento.DINHEIRO);
        assertThat(resultado.troco()).isEqualTo(Money.de("20.07"));
        assertThat(resultado.status()).isEqualTo(StatusPagamento.CONFIRMADO);
    }

    @Test
    @DisplayName("receber menos que o valor a pagar é recusado também pela porta pública")
    void recusaRecebidoAMenos() {
        assertThatIllegalArgumentException().isThrownBy(() -> pagamentos.pagar(
                SolicitacaoPagamento.emDinheiro(Money.de("50.00"), Money.de("49.99"))));
    }
}
