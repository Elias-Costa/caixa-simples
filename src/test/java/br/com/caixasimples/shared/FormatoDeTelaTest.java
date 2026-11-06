package br.com.caixasimples.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Os valores das mensagens como a tela os mostra. Teste de unidade puro, sem contexto nem banco. */
class FormatoDeTelaTest {

    @Test
    @DisplayName("reais com vírgula nos centavos, ponto no milhar e o sinal antes do símbolo")
    void reaisComoATelaMostra() {
        assertThat(FormatoDeTela.reais(Money.de("5.00"))).isEqualTo("R$ 5,00");
        assertThat(FormatoDeTela.reais(Money.de("1234.56"))).isEqualTo("R$ 1.234,56");
        assertThat(FormatoDeTela.reais(Money.de("0.10"))).isEqualTo("R$ 0,10");
        assertThat(FormatoDeTela.reais(Money.de("-2.50"))).isEqualTo("-R$ 2,50");
    }

    @Test
    @DisplayName("quantidade com vírgula e sem os zeros que sobram à direita")
    void quantidadeComoATelaMostra() {
        assertThat(FormatoDeTela.quantidade(new BigDecimal("2.000"))).isEqualTo("2");
        assertThat(FormatoDeTela.quantidade(new BigDecimal("0.350"))).isEqualTo("0,35");
        assertThat(FormatoDeTela.quantidade(new BigDecimal("-2"))).isEqualTo("-2");
        assertThat(FormatoDeTela.quantidade(new BigDecimal("1500"))).isEqualTo("1.500");
    }

    @Test
    @DisplayName("data com dia, mês e ano, como o balcão escreve")
    void dataComoOBalcaoEscreve() {
        assertThat(FormatoDeTela.data(LocalDate.of(2026, 10, 3))).isEqualTo("03/10/2026");
    }

    @Test
    @DisplayName("intervalo arredondado ao minuto, nas duas maiores unidades, com singular e plural")
    void intervaloComoSeDizNoBalcao() {
        assertThat(FormatoDeTela.duracao(Duration.ofMinutes(10).minusMillis(40)))
                .as("o relógio dez minutos adiantado chega uns milissegundos abaixo")
                .isEqualTo("10 minutos");
        assertThat(FormatoDeTela.duracao(Duration.ofSeconds(7 * 60 + 29))).isEqualTo("7 minutos");
        assertThat(FormatoDeTela.duracao(Duration.ofSeconds(7 * 60 + 30))).isEqualTo("8 minutos");
        assertThat(FormatoDeTela.duracao(Duration.ofMinutes(1))).isEqualTo("1 minuto");
        assertThat(FormatoDeTela.duracao(Duration.ofMinutes(60))).isEqualTo("1 hora");
        assertThat(FormatoDeTela.duracao(Duration.ofMinutes(65))).isEqualTo("1 hora e 5 minutos");
        assertThat(FormatoDeTela.duracao(Duration.ofMinutes(121))).isEqualTo("2 horas e 1 minuto");
        assertThat(FormatoDeTela.duracao(Duration.ofHours(24))).isEqualTo("1 dia");
        assertThat(FormatoDeTela.duracao(Duration.ofHours(51).plusMinutes(40)))
                .as("com dias, os minutos ficam de fora").isEqualTo("2 dias e 3 horas");
        assertThat(FormatoDeTela.duracao(Duration.ofDays(365).minusMillis(5))).isEqualTo("365 dias");
    }
}
