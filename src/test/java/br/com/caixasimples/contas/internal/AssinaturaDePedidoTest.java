package br.com.caixasimples.contas.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import br.com.caixasimples.contas.Plano;
import br.com.caixasimples.shared.Money;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * O código de ativação do pedido de plano.
 *
 * <p>O vetor fixo é o mesmo do teste do script de operação que o mantenedor usa para gerar o
 * código: se a aplicação e o script divergirem, um dos dois testes falha antes de um código
 * entregue ser recusado. O segredo é montado por repetição, sem literal no repositório, e os
 * valores são fictícios.
 */
class AssinaturaDePedidoTest {

    private static final String SEGREDO = "x".repeat(40);

    private static final UUID PEDIDO = UUID.fromString("8d5b2c1e-4f3a-4b6c-9d7e-1a2b3c4d5e6f");

    private static final Money VALOR = Money.de("123.45");

    private final AssinaturaDePedido assinatura = new AssinaturaDePedido(SEGREDO);

    @Test
    @DisplayName("o código do pedido, do plano e do valor é o do vetor fixo, em quatro grupos de quatro")
    void vetorFixo() {
        assertThat(assinatura.codigo(PEDIDO, Plano.COMPLETO, VALOR))
                .isEqualTo("Q18E-8RDD-QZ4S-3HQH");
        assertThat(assinatura.codigo(PEDIDO, Plano.CAIXA_SIMPLES, Money.de("67.89")))
                .isEqualTo("95FG-7CV3-TMWE-NDJV");
        assertThat(assinatura.codigo(
                UUID.fromString("0f1e2d3c-4b5a-4968-8776-655443322110"), Plano.COMPLETO,
                Money.de("0.50")))
                .isEqualTo("Z667-1W9N-QWRG-9ET4");
        assertThat(assinatura.codigo(PEDIDO, Plano.COMPLETO, Money.de("1234.56")))
                .as("sem separador de milhar")
                .isEqualTo("Z2E5-FVNC-0565-XGWZ");
    }

    @Test
    @DisplayName("o código usa só o alfabeto de Crockford, sem I, L, O e U")
    void alfabetoSemLetrasAmbiguas() {
        for (int i = 0; i < 200; i++) {
            assertThat(assinatura.codigo(UUID.randomUUID(), Plano.COMPLETO, VALOR))
                    .matches("[0-9A-HJKMNP-TV-Z]{4}(-[0-9A-HJKMNP-TV-Z]{4}){3}");
        }
    }

    @Test
    @DisplayName("a conferência ignora maiúsculas, espaços e hífens")
    void conferenciaNormaliza() {
        assertThat(assinatura.confere(PEDIDO, Plano.COMPLETO, VALOR, "Q18E-8RDD-QZ4S-3HQH"))
                .isTrue();
        assertThat(assinatura.confere(PEDIDO, Plano.COMPLETO, VALOR, "q18e8rddqz4s3hqh")).isTrue();
        assertThat(assinatura.confere(PEDIDO, Plano.COMPLETO, VALOR, " Q18E 8RDD QZ4S 3HQH "))
                .isTrue();
    }

    @Test
    @DisplayName("código de outro plano, de outro pedido ou com um caractere trocado não confere")
    void codigoDeOutroPedidoOuPlano() {
        assertThat(assinatura.confere(PEDIDO, Plano.COMPLETO, VALOR,
                assinatura.codigo(PEDIDO, Plano.CAIXA_SIMPLES, VALOR))).isFalse();
        assertThat(assinatura.confere(PEDIDO, Plano.COMPLETO, VALOR, "Z667-1W9N-QWRG-9ET4"))
                .isFalse();
        assertThat(assinatura.confere(PEDIDO, Plano.COMPLETO, VALOR, "Q18E-8RDD-QZ4S-3HQJ"))
                .isFalse();
        assertThat(assinatura.confere(PEDIDO, Plano.COMPLETO, VALOR, "Q18E-8RDD-QZ4S")).isFalse();
        assertThat(assinatura.confere(PEDIDO, Plano.COMPLETO, VALOR, "")).isFalse();
        assertThat(assinatura.confere(PEDIDO, Plano.COMPLETO, VALOR, null)).isFalse();
    }

    /**
     * O texto do pedido passa pelo administrador antes de chegar ao mantenedor. O código que o
     * mantenedor gera com um valor diferente do gravado, o de um texto alterado, não confere.
     */
    @Test
    @DisplayName("o código do mesmo pedido e do mesmo plano com outro valor não confere, nem por um centavo")
    void codigoDeOutroValor() {
        String deUmCentavoAMenos = assinatura.codigo(PEDIDO, Plano.COMPLETO, Money.de("123.44"));
        assertThat(deUmCentavoAMenos).isEqualTo("RPMX-JK6D-KNQD-0BQ3");

        assertThat(assinatura.confere(PEDIDO, Plano.COMPLETO, VALOR, deUmCentavoAMenos)).isFalse();
        assertThat(assinatura.confere(PEDIDO, Plano.COMPLETO, Money.de("123.44"),
                assinatura.codigo(PEDIDO, Plano.COMPLETO, VALOR))).isFalse();
    }

    @Test
    @DisplayName("outro segredo dá outro código para o mesmo pedido")
    void outroSegredo() {
        AssinaturaDePedido outra = new AssinaturaDePedido("y".repeat(40));

        assertThat(outra.confere(PEDIDO, Plano.COMPLETO, VALOR, "Q18E-8RDD-QZ4S-3HQH")).isFalse();
    }

    @Test
    @DisplayName("segredo com menos de 32 bytes impede a subida, como a chave do token")
    void segredoCurto() {
        assertThatIllegalStateException()
                .isThrownBy(() -> new AssinaturaDePedido("x".repeat(31)))
                .withMessageContaining("CAIXA_SIMPLES_PLANO_SECRET");
    }
}
