package br.com.caixasimples.pagamentos.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import br.com.caixasimples.pagamentos.FormaPagamento;
import br.com.caixasimples.pagamentos.StatusPagamento;
import br.com.caixasimples.pagamentos.domain.PaymentStrategy;
import br.com.caixasimples.pagamentos.domain.ResultadoPagamento;
import br.com.caixasimples.pagamentos.domain.SolicitacaoPagamento;
import br.com.caixasimples.shared.Money;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A resolução por forma de pagamento, e a promessa que ela existe para cumprir: acrescentar uma
 * forma nova não altera este serviço.
 *
 * <p>Teste de unidade puro, sem contexto Spring e sem banco. O serviço recebe a lista de
 * estratégias pelo construtor, então o teste monta a lista que quiser, o que é justamente o que
 * permite provar a promessa acima com uma estratégia que só existe aqui dentro.
 */
class PaymentServiceTest {

    /**
     * Uma forma de pagamento inventada pelo teste, que o código de produção não conhece.
     *
     * <p>Ela usa PIX porque é um valor do enum sem estratégia de produção neste momento. O que
     * importa não é a forma escolhida: é que esta classe nasceu depois de {@code PaymentService} e
     * passa a ser resolvida por ele sem que uma linha dele mudasse.
     */
    private static class EstrategiaInventada implements PaymentStrategy {

        @Override
        public FormaPagamento getTipo() {
            return FormaPagamento.PIX;
        }

        @Override
        public ResultadoPagamento pagar(SolicitacaoPagamento solicitacao) {
            return new ResultadoPagamento(FormaPagamento.PIX, solicitacao.valor(),
                    StatusPagamento.PENDENTE, Money.ZERO);
        }
    }

    private static class OutraEstrategiaDePix extends EstrategiaInventada {
    }

    @Test
    @DisplayName("resolve a forma pedida pela estratégia que a declara")
    void resolvePelaForma() {
        PaymentService pagamentos = new PaymentService(List.of(new EstrategiaInventada()));

        ResultadoPagamento resultado =
                pagamentos.pagar(SolicitacaoPagamento.de(FormaPagamento.PIX, Money.de("30.00")));

        assertThat(resultado.forma()).isEqualTo(FormaPagamento.PIX);
        assertThat(resultado.status()).isEqualTo(StatusPagamento.PENDENTE);
    }

    @Test
    @DisplayName("uma forma de pagamento nova não altera o serviço")
    void formaNovaNaoAlteraOServico() {
        // A prova do desenho inteiro (RF09): EstrategiaInventada é uma classe que PaymentService
        // não conhece e nunca conhecerá, e mesmo assim é encontrada. Se algum dia a escolha da
        // forma virar uma decisão dentro do serviço, este teste passa a falhar.
        PaymentService semAEstrategia = new PaymentService(List.of());
        PaymentService comAEstrategia = new PaymentService(List.of(new EstrategiaInventada()));

        SolicitacaoPagamento solicitacao =
                SolicitacaoPagamento.de(FormaPagamento.PIX, Money.de("30.00"));

        assertThatExceptionOfType(FormaDePagamentoNaoSuportadaException.class)
                .isThrownBy(() -> semAEstrategia.pagar(solicitacao));

        assertThat(comAEstrategia.pagar(solicitacao).forma()).isEqualTo(FormaPagamento.PIX);
    }

    @Test
    @DisplayName("forma sem estratégia registrada estoura, em vez de devolver pagamento recusado")
    void formaSemEstrategiaEstoura() {
        // Forma não implementada é defeito de software; cartão negado pela operadora é desfecho de
        // negócio. Confundir os dois num status RECUSADO esconderia o primeiro.
        PaymentService pagamentos = new PaymentService(List.of());

        assertThatExceptionOfType(FormaDePagamentoNaoSuportadaException.class)
                .isThrownBy(() -> pagamentos.pagar(
                        SolicitacaoPagamento.emDinheiro(Money.de("10.00"), Money.de("10.00"))));
    }

    @Test
    @DisplayName("duas estratégias para a mesma forma quebram na construção")
    void duasEstrategiasParaAMesmaFormaFalham() {
        // Uma sobrescrever a outra em silêncio deixaria o balcão vendendo com metade das regras
        // daquela forma valendo e a outra metade não.
        List<PaymentStrategy> duplicadas =
                List.of(new EstrategiaInventada(), new OutraEstrategiaDePix());

        assertThatIllegalStateException().isThrownBy(() -> new PaymentService(duplicadas));
    }
}
