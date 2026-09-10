package br.com.caixasimples.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Arredondamento de valor monetário. Teste de unidade puro: {@code Money} não conhece framework
 * nenhum, então não precisa de contexto Spring nem de banco.
 */
class MoneyTest {

    @Test
    @DisplayName("arredonda para duas casas com HALF_UP, que é o caso da venda por peso")
    void arredondaMeioParaCima() {
        // 0,750 kg a R$ 39,90 dá R$ 29,925, um valor que não existe em espécie.
        BigDecimal exato = new BigDecimal("39.90").multiply(new BigDecimal("0.750"));

        assertThat(Money.arredondando(exato)).isEqualTo(Money.de("29.93"));
        assertThat(Money.arredondando(new BigDecimal("0.005"))).isEqualTo(Money.de("0.01"));
        assertThat(Money.arredondando(new BigDecimal("0.004"))).isEqualTo(Money.de("0.00"));
    }

    @Test
    @DisplayName("de(...) recusa fração de centavo em vez de arredondar em silêncio")
    void naoArredondaEscondido() {
        assertThatExceptionOfType(ArithmeticException.class)
                .as("arredondar aqui seria uma decisão tomada às escondidas")
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
    @DisplayName("sabe dizer que é negativo, e quem usa a informação é o cadastro de produto")
    void reconheceNegativo() {
        assertThat(Money.de("-0.01").isNegativo()).isTrue();
        assertThat(Money.ZERO.isNegativo()).isFalse();
        assertThat(Money.de("0.01").isNegativo()).isFalse();
    }

    @Test
    @DisplayName("soma e subtração mantêm as duas casas, sem passar por arredondamento")
    void somaESubtraiEmDuasCasas() {
        // As duas operações existem porque o saldo esperado do caixa acompanha os movimentos em
        // tempo real, então a conta é sempre entre valores que já estão em centavos.
        assertThat(Money.de("100.00").somar(Money.de("6.50"))).isEqualTo(Money.de("106.50"));
        assertThat(Money.de("100.00").subtrair(Money.de("30.00"))).isEqualTo(Money.de("70.00"));
        assertThat(Money.de("0.01").somar(Money.de("0.02")).valor().scale()).isEqualTo(2);
    }

    @Test
    @DisplayName("subtrair pode devolver negativo, porque quem julga o saldo é o domínio")
    void subtrairAceitaResultadoNegativo() {
        // Sangria maior que o dinheiro na gaveta é caso de borda do caixa, e é lá que ele tem de
        // aparecer. Recusar aqui obrigaria todo chamador a conferir antes de subtrair.
        assertThat(Money.de("10.00").subtrair(Money.de("30.00"))).isEqualTo(Money.de("-20.00"));
        assertThat(Money.de("10.00").subtrair(Money.de("30.00")).isNegativo()).isTrue();
    }

    @Test
    void zeroEhZeroEmDuasCasas() {
        assertThat(Money.ZERO).isEqualTo(Money.de("0.00"));
        assertThat(Money.ZERO).hasToString("0.00");
    }
}
