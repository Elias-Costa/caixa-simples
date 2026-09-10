package br.com.caixasimples.pagamentos.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import br.com.caixasimples.pagamentos.FormaPagamento;
import br.com.caixasimples.pagamentos.StatusPagamento;
import br.com.caixasimples.shared.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A regra do troco (RF10), a do pagamento lançado à mão (RF26) e as guardas da solicitação de
 * pagamento.
 *
 * <p>Teste de unidade puro, sem contexto Spring e sem banco: o troco é conta de domínio e não
 * depende de persistência para estar certo.
 */
class ResultadoPagamentoTest {

    @Test
    @DisplayName("troco é a diferença entre o que veio na mão e o que se ia pagar")
    void calculaOTroco() {
        ResultadoPagamento resultado =
                ResultadoPagamento.emDinheiro(Money.de("29.93"), Money.de("50.00"));

        assertThat(resultado.troco()).isEqualTo(Money.de("20.07"));
        assertThat(resultado.forma()).isEqualTo(FormaPagamento.DINHEIRO);
        assertThat(resultado.valor()).isEqualTo(Money.de("29.93"));
    }

    @Test
    @DisplayName("pagamento em dinheiro nasce CONFIRMADO")
    void nasceConfirmado() {
        // O dinheiro já está na gaveta quando o pagamento é registrado; não há confirmação
        // posterior a esperar, ao contrário do Pix.
        ResultadoPagamento resultado =
                ResultadoPagamento.emDinheiro(Money.de("10.00"), Money.de("10.00"));

        assertThat(resultado.status()).isEqualTo(StatusPagamento.CONFIRMADO);
    }

    @Test
    @DisplayName("valor exato dá troco zero, e não um resultado sem troco")
    void valorExatoDaTrocoZero() {
        ResultadoPagamento resultado =
                ResultadoPagamento.emDinheiro(Money.de("42.50"), Money.de("42.50"));

        assertThat(resultado.troco()).isEqualTo(Money.ZERO);
    }

    @Test
    @DisplayName("receber menos que o valor a pagar é recusado")
    void recusaRecebidoMenorQueOValor() {
        // Pagar menos em dinheiro se registra reduzindo o valor da parcela e completando o resto
        // em outra forma (RF09). Aceitar aqui gravaria um troco negativo, que não existe.
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ResultadoPagamento.emDinheiro(Money.de("50.00"),
                        Money.de("49.99")));
    }

    @Test
    @DisplayName("valor recebido é obrigatório mesmo quando o cliente paga certo")
    void recusaRecebidoNulo() {
        // Nulo não significa pagou o valor exato: quem pagou certo repete o valor da parcela.
        assertThatNullPointerException()
                .isThrownBy(() -> ResultadoPagamento.emDinheiro(Money.de("50.00"), null));
    }

    @Test
    @DisplayName("solicitação recusa valor zero e valor negativo")
    void solicitacaoRecusaValorNaoPositivo() {
        // Zero não é pagamento, e negativo seria devolução, que é outro assunto.
        assertThatIllegalArgumentException()
                .isThrownBy(() -> SolicitacaoPagamento.emDinheiro(Money.ZERO, Money.de("10.00")));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> SolicitacaoPagamento.emDinheiro(Money.de("-1.00"),
                        Money.de("10.00")));
    }

    @Test
    @DisplayName("solicitação de forma sem troco aceita valor recebido nulo")
    void solicitacaoSemValorRecebido() {
        // Pix e cartão não têm o que fazer com o valor entregue em espécie. Quem exige o campo é a
        // estratégia de dinheiro, não a solicitação.
        assertThatNoException()
                .isThrownBy(() -> SolicitacaoPagamento.de(FormaPagamento.PIX, Money.de("10.00")));
    }

    @Test
    @DisplayName("pagamento lançado à mão nasce CONFIRMADO e sem troco")
    void registradoAMaoNasceConfirmadoESemTroco() {
        // Quem lança já viu o dinheiro entrar, seja a notificação de recebimento do Pix ou o
        // comprovante da maquininha. Não há confirmação posterior a esperar.
        ResultadoPagamento pix = ResultadoPagamento.registradoAMao(
                SolicitacaoPagamento.de(FormaPagamento.PIX, Money.de("30.00")));
        ResultadoPagamento cartao = ResultadoPagamento.registradoAMao(
                SolicitacaoPagamento.de(FormaPagamento.CARTAO, Money.de("30.00")));

        assertThat(pix.status()).isEqualTo(StatusPagamento.CONFIRMADO);
        assertThat(pix.troco()).isEqualTo(Money.ZERO);
        assertThat(cartao.status()).isEqualTo(StatusPagamento.CONFIRMADO);
        assertThat(cartao.troco()).isEqualTo(Money.ZERO);
    }

    @Test
    @DisplayName("forma que não devolve troco recusa o valor recebido em espécie")
    void registradoAMaoRecusaValorRecebido() {
        // Aceitar calado um campo que não produz efeito nenhum esconderia o engano de quem chamou.
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ResultadoPagamento.registradoAMao(new SolicitacaoPagamento(
                        FormaPagamento.CARTAO, Money.de("30.00"), Money.de("50.00"))));
    }

    @Test
    @DisplayName("dinheiro não passa pelo caminho do lançamento à mão")
    void registradoAMaoRecusaDinheiro() {
        // Passar por aqui devolveria o pagamento confirmado sem conferir o que o cliente entregou,
        // e sem calcular o troco que ele tem a receber.
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ResultadoPagamento.registradoAMao(
                        SolicitacaoPagamento.emDinheiro(Money.de("30.00"), Money.de("50.00"))));
    }
}
