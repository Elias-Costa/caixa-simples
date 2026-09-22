package br.com.caixasimples.contas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import br.com.caixasimples.contas.internal.Conta;
import org.junit.jupiter.api.Test;

/** A marca da raiz só avança uma vez; o serviço trata login repetido antes de chamá-la. */
class ContaPrimeiroAcessoTest {

    @Test
    void raizRecusaMarcarOPrimeiroAcessoDuasVezes() {
        Conta conta = new Conta("Cafeteria", "cafeteria", Plano.GRATIS);
        conta.marcarCatalogoInicialAplicado();

        assertThat(conta.isCatalogoInicialAplicado()).isTrue();
        assertThatIllegalStateException().isThrownBy(conta::marcarCatalogoInicialAplicado)
                .withMessageContaining("ja foi aplicado");
    }
}
