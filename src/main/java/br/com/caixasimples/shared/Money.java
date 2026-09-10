package br.com.caixasimples.shared;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Valor monetário em reais, sempre com duas casas decimais.
 *
 * <p>A escala é 2 e o arredondamento é {@link RoundingMode#HALF_UP}, aplicado <em>em cada item</em>.
 * O total de uma venda é a soma de valores já arredondados, para que cada linha do comprovante
 * feche com o total impresso.
 *
 * <p><strong>O arredondamento é visível no ponto de uso, de propósito.</strong> O construtor não
 * arredonda: ele <em>recusa</em> um valor que não esteja em duas casas. Quem tem uma fração de
 * centavo de verdade nas mãos, como {@code 0,750 kg} a {@code R$ 39,90} dando {@code R$ 29,925},
 * chama {@link #arredondando(BigDecimal)}, cujo nome diz o que vai acontecer com o valor. Um
 * construtor que reescrevesse o número em silêncio esconderia justamente a decisão que este tipo
 * existe para deixar visível.
 *
 * <p><strong>A aritmética entra por uso, nunca por previsão.</strong> {@link #somar} e
 * {@link #subtrair} existem porque o saldo esperado da sessão de caixa acompanha os movimentos em
 * tempo real, não porque um tipo monetário costuma ter as quatro operações. Multiplicação continua
 * fora: ela entra quando houver o primeiro uso real, que é quantidade vezes preço unitário no
 * total da venda.
 */
public record Money(BigDecimal valor) {

    /** Duas casas, porque é o que existe em espécie. */
    public static final int ESCALA = 2;

    /** Meio para cima; truncar geraria perda sistemática a favor do cliente. */
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
     * Valor que já é exato em centavos. Normaliza a escala, de modo que {@code 39.9} vira
     * {@code 39.90}, o que importa porque {@code BigDecimal.equals} compara escala, e
     * <strong>estoura</strong> se houver fração de centavo. Nesse caso o arredondamento seria uma
     * decisão, e decisão não se toma escondida num construtor.
     *
     * @throws ArithmeticException se {@code valor} tiver fração de centavo
     */
    public static Money de(BigDecimal valor) {
        Objects.requireNonNull(valor, "valor nao pode ser nulo");
        return new Money(valor.setScale(ESCALA, RoundingMode.UNNECESSARY));
    }

    /** Atalho para literal em código e em teste; mesmas regras de {@link #de(BigDecimal)}. */
    public static Money de(String valor) {
        return de(new BigDecimal(valor));
    }

    /**
     * Arredonda para duas casas com {@link RoundingMode#HALF_UP}. É o único ponto do sistema que
     * arredonda dinheiro, e o nome existe para que a chamada denuncie isso na linha em que aparece.
     */
    public static Money arredondando(BigDecimal valor) {
        Objects.requireNonNull(valor, "valor nao pode ser nulo");
        return new Money(valor.setScale(ESCALA, ARREDONDAMENTO));
    }

    /**
     * Soma dois valores. Não arredonda e não precisa: dois números em duas casas somam em duas
     * casas, então o resultado passa direto pela mesma checagem de escala do construtor.
     */
    public Money somar(Money outro) {
        Objects.requireNonNull(outro, "outro nao pode ser nulo");
        return new Money(valor.add(outro.valor));
    }

    /**
     * Subtrai um valor de outro. Pelo mesmo motivo de {@link #somar}, não arredonda.
     *
     * <p><strong>Pode devolver negativo, e isso é permitido de propósito.</strong> Quem sabe se um
     * saldo negativo faz sentido é o domínio que está fazendo a conta: uma sangria maior que o
     * dinheiro na gaveta é um problema do caixa, e um troco negativo é um problema da venda. Um
     * tipo monetário que recusasse negativo obrigaria cada chamador a conferir antes de subtrair, e
     * ainda esconderia o caso de borda em vez de deixá-lo aparecer onde ele importa.
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
