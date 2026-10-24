package br.com.caixasimples.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
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
}
