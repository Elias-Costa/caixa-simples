package br.com.caixasimples.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * P1 — arredondamento de valor monetario. Teste de unidade puro: {@code Money} nao conhece
 * framework nenhum, entao nao precisa de contexto Spring nem de banco.
 */
class MoneyTest {

    @Test
    @DisplayName("arredonda para duas casas com HALF_UP, que e o caso da venda por peso")
    void arredondaMeioParaCima() {
        // 0,750 kg a R$ 39,90 da R$ 29,925 — valor que nao existe em especie.
        BigDecimal exato = new BigDecimal("39.90").multiply(new BigDecimal("0.750"));

        assertThat(Money.arredondando(exato)).isEqualTo(Money.de("29.93"));
        assertThat(Money.arredondando(new BigDecimal("0.005"))).isEqualTo(Money.de("0.01"));
        assertThat(Money.arredondando(new BigDecimal("0.004"))).isEqualTo(Money.de("0.00"));
    }

    @Test
    @DisplayName("de(...) recusa fracao de centavo em vez de arredondar em silencio")
    void naoArredondaEscondido() {
        assertThatExceptionOfType(ArithmeticException.class)
                .as("arredondar aqui seria uma decisao tomada as escondidas")
                .isThrownBy(() -> Money.de(new BigDecimal("29.925")));
    }

    @Test
    @DisplayName("normaliza a escala, porque BigDecimal.equals compara escala")
    void normalizaEscala() {
        assertThat(Money.de("39.9")).isEqualTo(Money.de("39.90"));
        assertThat(Money.de(new BigDecimal("39.900"))).isEqualTo(Money.de("39.90"));
        assertThat(Money.de("39.90").valor().scale()).isEqualTo(2);
    }

    @Test
    @DisplayName("construtor recusa valor fora de duas casas")
    void construtorExigeDuasCasas() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Money(new BigDecimal("39.9")));
    }

    @Test
    @DisplayName("sabe dizer que e negativo — quem usa a informacao e o cadastro de produto")
    void reconheceNegativo() {
        assertThat(Money.de("-0.01").isNegativo()).isTrue();
        assertThat(Money.ZERO.isNegativo()).isFalse();
        assertThat(Money.de("0.01").isNegativo()).isFalse();
    }

    @Test
    @DisplayName("soma e subtracao mantem as duas casas, sem passar por arredondamento")
    void somaESubtraiEmDuasCasas() {
        // Os dois metodos nasceram no R06: valorFechamentoEsperado do caixa acompanha os
        // movimentos em tempo real (D21a), entao a conta e sempre entre valores ja em centavos.
        assertThat(Money.de("100.00").somar(Money.de("6.50"))).isEqualTo(Money.de("106.50"));
        assertThat(Money.de("100.00").subtrair(Money.de("30.00"))).isEqualTo(Money.de("70.00"));
        assertThat(Money.de("0.01").somar(Money.de("0.02")).valor().scale()).isEqualTo(2);
    }

    @Test
    @DisplayName("subtrair pode devolver negativo — quem julga o saldo e o dominio, nao o tipo")
    void subtrairAceitaResultadoNegativo() {
        // Sangria maior que o dinheiro na gaveta e caso de borda do caixa (R07), e e la que ele
        // tem de aparecer. Recusar aqui obrigaria todo chamador a conferir antes de subtrair.
        assertThat(Money.de("10.00").subtrair(Money.de("30.00"))).isEqualTo(Money.de("-20.00"));
        assertThat(Money.de("10.00").subtrair(Money.de("30.00")).isNegativo()).isTrue();
    }

    @Test
    void zeroEhZeroEmDuasCasas() {
        assertThat(Money.ZERO).isEqualTo(Money.de("0.00"));
        assertThat(Money.ZERO).hasToString("0.00");
    }
}
