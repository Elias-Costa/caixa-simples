package br.com.caixasimples.contas.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import br.com.caixasimples.contas.Plano;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * O código de ativação do pedido de plano.
 *
 * <p>O vetor fixo é o mesmo do teste do script de operação que o mantenedor usa para gerar o
 * código: se a aplicação e o script divergirem, um dos dois testes falha antes de um código
 * entregue ser recusado. O segredo é montado por repetição, sem literal no repositório.
 */
class AssinaturaDePedidoTest {

    private static final String SEGREDO = "x".repeat(40);

    private static final UUID PEDIDO = UUID.fromString("8d5b2c1e-4f3a-4b6c-9d7e-1a2b3c4d5e6f");

    private final AssinaturaDePedido assinatura = new AssinaturaDePedido(SEGREDO);

    @Test
    @DisplayName("o código do pedido e do plano é o do vetor fixo, em quatro grupos de quatro")
    void vetorFixo() {
        assertThat(assinatura.codigo(PEDIDO, Plano.COMPLETO)).isEqualTo("2JSY-PFPP-BNA2-YXNN");
        assertThat(assinatura.codigo(PEDIDO, Plano.CAIXA_SIMPLES)).isEqualTo("69SK-7TK8-C7XY-4TAD");
        assertThat(assinatura.codigo(
                UUID.fromString("0f1e2d3c-4b5a-4968-8776-655443322110"), Plano.COMPLETO))
                .isEqualTo("6P97-S1Z4-SG64-YWJB");
    }

    @Test
    @DisplayName("o código usa só o alfabeto de Crockford, sem I, L, O e U")
    void alfabetoSemLetrasAmbiguas() {
        for (int i = 0; i < 200; i++) {
            assertThat(assinatura.codigo(UUID.randomUUID(), Plano.COMPLETO))
                    .matches("[0-9A-HJKMNP-TV-Z]{4}(-[0-9A-HJKMNP-TV-Z]{4}){3}");
        }
    }

    @Test
    @DisplayName("a conferência ignora maiúsculas, espaços e hífens")
    void conferenciaNormaliza() {
        assertThat(assinatura.confere(PEDIDO, Plano.COMPLETO, "2JSY-PFPP-BNA2-YXNN")).isTrue();
        assertThat(assinatura.confere(PEDIDO, Plano.COMPLETO, "2jsypfppbna2yxnn")).isTrue();
        assertThat(assinatura.confere(PEDIDO, Plano.COMPLETO, " 2JSY PFPP BNA2 YXNN ")).isTrue();
    }

    @Test
    @DisplayName("código de outro plano, de outro pedido ou com um caractere trocado não confere")
    void codigoDeOutroPedidoOuPlano() {
        assertThat(assinatura.confere(PEDIDO, Plano.COMPLETO, "69SK-7TK8-C7XY-4TAD")).isFalse();
        assertThat(assinatura.confere(PEDIDO, Plano.COMPLETO, "6P97-S1Z4-SG64-YWJB")).isFalse();
        assertThat(assinatura.confere(PEDIDO, Plano.COMPLETO, "2JSY-PFPP-BNA2-YXNM")).isFalse();
        assertThat(assinatura.confere(PEDIDO, Plano.COMPLETO, "2JSY-PFPP-BNA2")).isFalse();
        assertThat(assinatura.confere(PEDIDO, Plano.COMPLETO, "")).isFalse();
        assertThat(assinatura.confere(PEDIDO, Plano.COMPLETO, null)).isFalse();
    }

    @Test
    @DisplayName("outro segredo dá outro código para o mesmo pedido")
    void outroSegredo() {
        AssinaturaDePedido outra = new AssinaturaDePedido("y".repeat(40));

        assertThat(outra.confere(PEDIDO, Plano.COMPLETO, "2JSY-PFPP-BNA2-YXNN")).isFalse();
    }

    @Test
    @DisplayName("segredo com menos de 32 bytes impede a subida, como a chave do token")
    void segredoCurto() {
        assertThatIllegalStateException()
                .isThrownBy(() -> new AssinaturaDePedido("x".repeat(31)))
                .withMessageContaining("CAIXA_SIMPLES_PLANO_SECRET");
    }
}
