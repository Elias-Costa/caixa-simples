package br.com.caixasimples.shared;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Valor monetario em reais, sempre com duas casas decimais.
 *
 * <p>Implementa a decisao <strong>P1</strong>: escala 2 com {@link RoundingMode#HALF_UP}, aplicado
 * <em>em cada item</em> — o total de uma venda e a soma de valores ja arredondados, para que cada
 * linha do comprovante feche com o total impresso.
 *
 * <p><strong>O arredondamento e visivel no ponto de uso, de proposito.</strong> O construtor nao
 * arredonda: ele <em>recusa</em> um valor que nao esteja em duas casas. Quem tem uma fracao de
 * centavo de verdade nas maos — {@code 0,750 kg} a {@code R$ 39,90} da {@code R$ 29,925} — chama
 * {@link #arredondando(BigDecimal)}, cujo nome diz o que vai acontecer com o valor. Um construtor
 * que reescrevesse o numero em silencio esconderia exatamente a decisao que P1 tomou.
 *
 * <p>Nao tem aritmetica ainda. O unico uso real hoje e {@code Produto.preco} (passo R02); soma e
 * multiplicacao nascem quando houver uso de verdade — troco (R09) e total da venda (R12).
 */
public record Money(BigDecimal valor) {

    /** P1 — duas casas, porque e o que existe em especie. */
    public static final int ESCALA = 2;

    /** P1 — meio para cima; truncar geraria perda sistematica a favor do cliente. */
    public static final RoundingMode ARREDONDAMENTO = RoundingMode.HALF_UP;

    public static final Money ZERO = Money.de("0.00");

    public Money {
        Objects.requireNonNull(valor, "valor nao pode ser nulo");
        if (valor.scale() != ESCALA) {
            throw new IllegalArgumentException(
                    "valor monetario tem de estar em " + ESCALA + " casas decimais, e nao "
                            + valor.scale() + ": " + valor
                            + ". Use Money.arredondando(...) se o arredondamento e intencional.");
        }
    }

    /**
     * Valor que ja e exato em centavos. Normaliza a escala ({@code 39.9} vira {@code 39.90}, o que
     * importa porque {@code BigDecimal.equals} compara escala) e <strong>estoura</strong> se houver
     * fracao de centavo — nesse caso o arredondamento seria uma decisao, e decisao nao se toma
     * escondida num construtor.
     *
     * @throws ArithmeticException se {@code valor} tiver fracao de centavo
     */
    public static Money de(BigDecimal valor) {
        Objects.requireNonNull(valor, "valor nao pode ser nulo");
        return new Money(valor.setScale(ESCALA, RoundingMode.UNNECESSARY));
    }

    /** Atalho para literal em codigo e em teste; mesmas regras de {@link #de(BigDecimal)}. */
    public static Money de(String valor) {
        return de(new BigDecimal(valor));
    }

    /**
     * Arredonda para duas casas com HALF_UP (P1). E o unico ponto do sistema que arredonda dinheiro,
     * e o nome existe para que a chamada denuncie isso na linha em que aparece.
     */
    public static Money arredondando(BigDecimal valor) {
        Objects.requireNonNull(valor, "valor nao pode ser nulo");
        return new Money(valor.setScale(ESCALA, ARREDONDAMENTO));
    }

    public boolean isNegativo() {
        return valor.signum() < 0;
    }

    @Override
    public String toString() {
        return valor.toPlainString();
    }
}
