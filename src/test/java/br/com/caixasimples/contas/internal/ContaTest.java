package br.com.caixasimples.contas.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import br.com.caixasimples.contas.Plano;
import br.com.caixasimples.shared.Money;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * O ciclo do plano pago na raiz {@link Conta}: o que cada mudança de plano recusa, e que a recusa
 * não deixa nada pela metade.
 *
 * <p>Teste de unidade puro, com datas fixas: a raiz não lê relógio, e quem chama informa o dia de
 * hoje. O caso de uso recusa antes boa parte destes casos, então a raiz é a segunda linha; a
 * terceira são as restrições do ciclo no banco, provadas em {@code PlanoServiceTest}.
 */
class ContaTest {

    /** A adesão de todos os testes: o ciclo vence todo dia 10. */
    private static final LocalDate ADESAO = LocalDate.of(2026, 3, 10);

    private static final LocalDate VENCIMENTO = LocalDate.of(2026, 4, 10);

    @Test
    @DisplayName("a adesão parte do plano grátis para um plano pago, e só uma vez")
    void adesaoSoDoGratisParaUmPlanoPago() {
        Conta gratis = gratis();
        assertThatIllegalArgumentException()
                .isThrownBy(() -> gratis.ativarPlano(Plano.GRATIS, ADESAO))
                .withMessageContaining("gratuito nao se ativa");
        conferirCiclo(gratis, Plano.GRATIS, null, null);

        // Uma segunda adesão recomeçaria o ciclo no dia dela, apagando o período já pago.
        Conta intermediaria = paga(Plano.CAIXA_SIMPLES);
        assertThatIllegalStateException()
                .isThrownBy(() -> intermediaria.ativarPlano(Plano.COMPLETO, ADESAO.plusDays(5)))
                .withMessageContaining("ja tem o plano");
        conferirCiclo(intermediaria, Plano.CAIXA_SIMPLES, 10, VENCIMENTO);

        Conta completa = paga(Plano.COMPLETO);
        assertThatIllegalStateException()
                .isThrownBy(() -> completa.ativarPlano(Plano.COMPLETO, ADESAO.plusDays(5)))
                .withMessageContaining("ja tem o plano");
        conferirCiclo(completa, Plano.COMPLETO, 10, VENCIMENTO);
    }

    @Test
    @DisplayName("o upgrade vale só a partir do plano intermediário, e não mexe no vencimento")
    void upgradeSoDoIntermediario() {
        Conta gratis = gratis();
        assertThatIllegalStateException()
                .isThrownBy(gratis::subirParaCompleto)
                .withMessageContaining("upgrade vale so");
        conferirCiclo(gratis, Plano.GRATIS, null, null);

        Conta completa = paga(Plano.COMPLETO);
        assertThatIllegalStateException()
                .isThrownBy(completa::subirParaCompleto)
                .withMessageContaining("upgrade vale so");
        conferirCiclo(completa, Plano.COMPLETO, 10, VENCIMENTO);

        Conta intermediaria = paga(Plano.CAIXA_SIMPLES);
        intermediaria.subirParaCompleto();
        conferirCiclo(intermediaria, Plano.COMPLETO, 10, VENCIMENTO);
    }

    @Test
    @DisplayName("a renovação é do plano da Conta e de um período ainda não pago")
    void renovacaoDoProprioPlanoEDeUmPeriodoAindaNaoPago() {
        Conta conta = paga(Plano.CAIXA_SIMPLES);
        LocalDate maio = LocalDate.of(2026, 5, 10);

        assertThatIllegalStateException()
                .isThrownBy(() -> conta.renovar(Plano.COMPLETO, maio))
                .withMessageContaining("a renovacao e do plano");
        assertThatIllegalStateException()
                .isThrownBy(() -> conta.renovar(Plano.CAIXA_SIMPLES, VENCIMENTO))
                .withMessageContaining("ja esta pago");
        assertThatIllegalStateException()
                .isThrownBy(() -> conta.renovar(Plano.CAIXA_SIMPLES, VENCIMENTO.minusDays(1)))
                .withMessageContaining("ja esta pago");
        conferirCiclo(conta, Plano.CAIXA_SIMPLES, 10, VENCIMENTO);

        // O limite: o período seguinte ao pago renova, e o dia do ciclo não muda.
        conta.renovar(Plano.CAIXA_SIMPLES, maio);
        conferirCiclo(conta, Plano.CAIXA_SIMPLES, 10, maio);
    }

    @Test
    @DisplayName("a Conta no plano grátis não tem ciclo: não renova, não tem período nem valor de upgrade")
    void contaGratisNaoTemCiclo() {
        Conta gratis = gratis();

        assertThatIllegalStateException()
                .isThrownBy(() -> gratis.renovar(Plano.CAIXA_SIMPLES, VENCIMENTO))
                .withMessageContaining("gratuito nao tem ciclo");
        assertThatIllegalStateException()
                .isThrownBy(() -> gratis.periodoDaRenovacao(ADESAO))
                .withMessageContaining("gratuito nao tem ciclo");
        assertThatIllegalStateException()
                .isThrownBy(() -> gratis.valorDoUpgrade(Money.de("40.00"), ADESAO))
                .withMessageContaining("gratuito nao tem ciclo");
        conferirCiclo(gratis, Plano.GRATIS, null, null);
    }

    private static Conta gratis() {
        return new Conta("Cafeteria Aurora", null, Plano.GRATIS);
    }

    private static Conta paga(Plano plano) {
        Conta conta = gratis();
        conta.ativarPlano(plano, ADESAO);
        return conta;
    }

    /** O plano e as duas datas do ciclo, que o banco exige juntas no plano pago. */
    private static void conferirCiclo(Conta conta, Plano plano, Integer diaDeVencimento,
            LocalDate proximoVencimento) {
        assertThat(conta)
                .extracting(Conta::getPlano, Conta::getDiaDeVencimento, Conta::getProximoVencimento)
                .containsExactly(plano, diaDeVencimento, proximoVencimento);
    }
}
