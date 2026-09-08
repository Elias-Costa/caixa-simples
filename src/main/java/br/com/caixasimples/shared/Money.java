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
 * <p><strong>A aritmetica entra por uso, nunca por previsao.</strong> {@link #somar} e
 * {@link #subtrair} nasceram no R06, quando {@code SessaoCaixa.valorFechamentoEsperado} passou a
 * acompanhar os movimentos do caixa em tempo real (D21a) — antes disso o tipo so sabia nascer e se
 * comparar com zero. Multiplicacao continua fora: o primeiro uso real dela e quantidade vezes preco
 * unitario, no total da venda (R12).
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

    /**
     * Soma dois valores. Nao arredonda e nao precisa: dois numeros em duas casas somam em duas
     * casas, entao o resultado passa direto pela mesma checagem de escala do construtor.
     */
    public Money somar(Money outro) {
        Objects.requireNonNull(outro, "outro nao pode ser nulo");
        return new Money(valor.add(outro.valor));
    }

    /**
     * Subtrai um valor de outro. Pelo mesmo motivo de {@link #somar}, nao arredonda.
     *
     * <p><strong>Pode devolver negativo, e isso e permitido de proposito.</strong> Quem sabe se um
     * saldo negativo faz sentido e o dominio que esta fazendo a conta — uma sangria maior que o
     * dinheiro na gaveta e um problema do caixa, e um troco negativo e um problema da venda. Um
     * tipo monetario que recusasse negativo obrigaria cada chamador a conferir antes de subtrair, e
     * ainda esconderia o caso de borda em vez de deixa-lo aparecer onde ele importa.
     */
    public Money subtrair(Money outro) {
        Objects.requireNonNull(outro, "outro nao pode ser nulo");
        return new Money(valor.subtract(outro.valor));
    }

    public boolean isNegativo() {
        return valor.signum() < 0;
    }

    @Override
    public String toString() {
        return valor.toPlainString();
    }
}
