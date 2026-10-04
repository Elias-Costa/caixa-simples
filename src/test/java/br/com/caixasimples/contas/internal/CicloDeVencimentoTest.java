package br.com.caixasimples.contas.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import br.com.caixasimples.contas.SituacaoDoPlano;
import br.com.caixasimples.contas.internal.CicloDeVencimento.Periodo;
import br.com.caixasimples.shared.Money;
import java.time.LocalDate;
import java.time.YearMonth;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * As datas do ciclo do plano pago, com datas fixas: a classe não lê relógio, e quem chama informa o
 * dia de hoje.
 */
class CicloDeVencimentoTest {

    private static final Money DIFERENCA = Money.de("40.00");

    @Test
    @DisplayName("mês sem o dia do ciclo vence no último dia, inclusive em fevereiro bissexto")
    void mesCurtoVenceNoUltimoDia() {
        assertThat(CicloDeVencimento.vencimentoNoMes(YearMonth.of(2026, 2), 31))
                .isEqualTo(LocalDate.of(2026, 2, 28));
        assertThat(CicloDeVencimento.vencimentoNoMes(YearMonth.of(2028, 2), 31))
                .isEqualTo(LocalDate.of(2028, 2, 29));
        assertThat(CicloDeVencimento.vencimentoNoMes(YearMonth.of(2026, 4), 31))
                .isEqualTo(LocalDate.of(2026, 4, 30));
        assertThat(CicloDeVencimento.vencimentoNoMes(YearMonth.of(2026, 4), 15))
                .isEqualTo(LocalDate.of(2026, 4, 15));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> CicloDeVencimento.vencimentoNoMes(YearMonth.of(2026, 4), 0));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> CicloDeVencimento.vencimentoNoMes(YearMonth.of(2026, 4), 32));
    }

    @Test
    @DisplayName("adesão em 31 de janeiro vence em 28 de fevereiro e volta ao dia 31 em março")
    void voltaAoDiaOriginal() {
        LocalDate adesao = LocalDate.of(2026, 1, 31);
        int dia = adesao.getDayOfMonth();

        LocalDate fevereiro = CicloDeVencimento.seguinte(adesao, dia);
        LocalDate marco = CicloDeVencimento.seguinte(fevereiro, dia);
        LocalDate abril = CicloDeVencimento.seguinte(marco, dia);
        LocalDate maio = CicloDeVencimento.seguinte(abril, dia);

        assertThat(fevereiro).isEqualTo(LocalDate.of(2026, 2, 28));
        assertThat(marco).isEqualTo(LocalDate.of(2026, 3, 31));
        assertThat(abril).isEqualTo(LocalDate.of(2026, 4, 30));
        assertThat(maio).isEqualTo(LocalDate.of(2026, 5, 31));
        assertThat(CicloDeVencimento.anterior(marco, dia)).isEqualTo(fevereiro);
        assertThat(CicloDeVencimento.anterior(fevereiro, dia)).isEqualTo(adesao);
    }

    @Test
    @DisplayName("adesão em 29 de fevereiro vence no dia 28 do fevereiro seguinte e volta ao 29")
    void adesaoNoDiaBissexto() {
        int dia = 29;

        assertThat(CicloDeVencimento.seguinte(LocalDate.of(2028, 2, 29), dia))
                .isEqualTo(LocalDate.of(2028, 3, 29));
        assertThat(CicloDeVencimento.seguinte(LocalDate.of(2029, 1, 29), dia))
                .isEqualTo(LocalDate.of(2029, 2, 28));
        assertThat(CicloDeVencimento.seguinte(LocalDate.of(2029, 2, 28), dia))
                .isEqualTo(LocalDate.of(2029, 3, 29));
    }

    @Test
    @DisplayName("aviso a partir de sete dias antes, tolerância de sete dias depois, suspensão no oitavo")
    void limitesDeCadaSituacao() {
        LocalDate vencimento = LocalDate.of(2026, 3, 10);

        assertThat(CicloDeVencimento.situacao(vencimento, LocalDate.of(2026, 3, 2)))
                .isEqualTo(SituacaoDoPlano.EM_DIA);
        assertThat(CicloDeVencimento.situacao(vencimento, LocalDate.of(2026, 3, 3)))
                .isEqualTo(SituacaoDoPlano.A_VENCER);
        assertThat(CicloDeVencimento.situacao(vencimento, LocalDate.of(2026, 3, 9)))
                .isEqualTo(SituacaoDoPlano.A_VENCER);
        assertThat(CicloDeVencimento.situacao(vencimento, LocalDate.of(2026, 3, 10)))
                .isEqualTo(SituacaoDoPlano.VENCIDO);
        assertThat(CicloDeVencimento.situacao(vencimento, LocalDate.of(2026, 3, 17)))
                .isEqualTo(SituacaoDoPlano.VENCIDO);
        assertThat(CicloDeVencimento.situacao(vencimento, LocalDate.of(2026, 3, 18)))
                .isEqualTo(SituacaoDoPlano.SUSPENSO);
        assertThat(CicloDeVencimento.situacao(vencimento, LocalDate.of(2026, 9, 1)))
                .isEqualTo(SituacaoDoPlano.SUSPENSO);

        assertThat(CicloDeVencimento.inicioDaSuspensao(vencimento))
                .isEqualTo(LocalDate.of(2026, 3, 18));
    }

    @Test
    @DisplayName("a renovação paga o período vencido, adiantada ou atrasada, sem deslocar o ciclo")
    void renovacaoPagaOPeriodoVencido() {
        LocalDate vencimento = LocalDate.of(2026, 3, 10);
        Periodo marco = new Periodo(vencimento, LocalDate.of(2026, 4, 10));

        assertThat(CicloDeVencimento.periodoDaRenovacao(vencimento, 10, LocalDate.of(2026, 3, 5)))
                .as("adiantada")
                .isEqualTo(marco);
        assertThat(CicloDeVencimento.periodoDaRenovacao(vencimento, 10, LocalDate.of(2026, 3, 25)))
                .as("na tolerância ou já suspensa, dentro do período vencido")
                .isEqualTo(marco);
        assertThat(CicloDeVencimento.periodoDaRenovacao(vencimento, 10, LocalDate.of(2026, 4, 9)))
                .isEqualTo(marco);
    }

    @Test
    @DisplayName("com mais de um período vencido, a renovação paga o corrente e deixa os anteriores")
    void renovacaoDepoisDeVariosMeses() {
        LocalDate vencimento = LocalDate.of(2026, 3, 10);

        assertThat(CicloDeVencimento.periodoDaRenovacao(vencimento, 10, LocalDate.of(2026, 4, 10)))
                .isEqualTo(new Periodo(LocalDate.of(2026, 4, 10), LocalDate.of(2026, 5, 10)));
        assertThat(CicloDeVencimento.periodoDaRenovacao(vencimento, 10, LocalDate.of(2026, 6, 20)))
                .isEqualTo(new Periodo(LocalDate.of(2026, 6, 10), LocalDate.of(2026, 7, 10)));

        // Ciclo do dia 31: o período corrente começa no último dia de março e termina no de abril.
        assertThat(CicloDeVencimento.periodoDaRenovacao(LocalDate.of(2026, 2, 28), 31,
                LocalDate.of(2026, 4, 5)))
                .isEqualTo(new Periodo(LocalDate.of(2026, 3, 31), LocalDate.of(2026, 4, 30)));
    }

    @Test
    @DisplayName("o upgrade cobra a diferença pelos dias que faltam, sobre os dias do período")
    void upgradeProporcional() {
        LocalDate vencimento = LocalDate.of(2026, 4, 10);

        // Período de 10 de março a 10 de abril: 31 dias. De 25 de março faltam 16: 640/31 = 20,645.
        assertThat(CicloDeVencimento.valorDoUpgrade(DIFERENCA, vencimento, 10,
                LocalDate.of(2026, 3, 25))).isEqualTo(Money.de("20.65"));
        // Na véspera, um dia: 40/31 = 1,290.
        assertThat(CicloDeVencimento.valorDoUpgrade(DIFERENCA, vencimento, 10,
                LocalDate.of(2026, 4, 9))).isEqualTo(Money.de("1.29"));
        // No primeiro dia do período, a diferença inteira.
        assertThat(CicloDeVencimento.valorDoUpgrade(DIFERENCA, vencimento, 10,
                LocalDate.of(2026, 3, 10))).isEqualTo(DIFERENCA);
        // Fevereiro tem 28 dias: de 24 de fevereiro faltam 14, a metade.
        assertThat(CicloDeVencimento.valorDoUpgrade(DIFERENCA, LocalDate.of(2026, 3, 10), 10,
                LocalDate.of(2026, 2, 24))).isEqualTo(Money.de("20.00"));
    }

    @Test
    @DisplayName("com renovação adiantada, o upgrade cobra todos os dias já pagos no plano menor")
    void upgradeComRenovacaoAdiantada() {
        // Pago até 10 de maio; o período que termina nele tem 30 dias, e de 25 de março faltam 46.
        assertThat(CicloDeVencimento.valorDoUpgrade(DIFERENCA, LocalDate.of(2026, 5, 10), 10,
                LocalDate.of(2026, 3, 25))).isEqualTo(Money.de("61.33"));
    }

    @Test
    @DisplayName("sem período pago não há upgrade proporcional")
    void upgradeNoVencimentoRecusado() {
        LocalDate vencimento = LocalDate.of(2026, 4, 10);

        assertThatIllegalArgumentException().isThrownBy(
                () -> CicloDeVencimento.valorDoUpgrade(DIFERENCA, vencimento, 10, vencimento));
        assertThatIllegalArgumentException().isThrownBy(() -> CicloDeVencimento.valorDoUpgrade(
                DIFERENCA, vencimento, 10, LocalDate.of(2026, 4, 15)));
    }

    @Test
    @DisplayName("período vazio ou invertido não existe")
    void periodoVazio() {
        LocalDate dia = LocalDate.of(2026, 3, 10);

        assertThatIllegalArgumentException().isThrownBy(() -> new Periodo(dia, dia));
        assertThatIllegalArgumentException().isThrownBy(() -> new Periodo(dia, dia.minusDays(1)));
    }
}
