package br.com.caixasimples.contas.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import br.com.caixasimples.contas.Plano;
import br.com.caixasimples.shared.Money;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** A mensalidade vinda do ambiente, recusada na subida quando daria pedido sem sentido. */
class ConfiguracaoDosPlanosTest {

    private static final String SEGREDO = "x".repeat(40);

    @Test
    @DisplayName("devolve a mensalidade do plano pago, e o gratuito não tem")
    void mensalidadePorPlano() {
        ConfiguracaoDosPlanos configuracao = new ConfiguracaoDosPlanos(SEGREDO,
                new BigDecimal("70.5"));

        assertThat(configuracao.mensalidade(Plano.COMPLETO)).isEqualTo(Money.de("70.50"));
        assertThatIllegalArgumentException().isThrownBy(() -> configuracao.mensalidade(Plano.GRATIS));
    }

    @Test
    @DisplayName("mensalidade ausente, não positiva ou com fração de centavo impede a subida")
    void mensalidadeInvalida() {
        assertThatIllegalStateException().isThrownBy(
                () -> new ConfiguracaoDosPlanos(SEGREDO, null))
                .withMessageContaining("CAIXA_SIMPLES_MENSALIDADE_COMPLETO");
        assertThatIllegalStateException().isThrownBy(
                () -> new ConfiguracaoDosPlanos(SEGREDO, BigDecimal.ZERO));
        assertThatIllegalStateException().isThrownBy(
                () -> new ConfiguracaoDosPlanos(SEGREDO, new BigDecimal("-70.00")));
        assertThatIllegalStateException().isThrownBy(
                () -> new ConfiguracaoDosPlanos(SEGREDO, new BigDecimal("70.001")));
    }
}
