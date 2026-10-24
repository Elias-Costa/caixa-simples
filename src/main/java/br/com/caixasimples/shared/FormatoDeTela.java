package br.com.caixasimples.shared;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Valores escritos como a tela os mostra, para as mensagens que o servidor devolve ao balcão.
 *
 * <p>A mensagem de uma recusa chega à tela como veio, e quem lê é o operador: {@code R$ 1.234,56} e
 * {@code 0,35 kg}, nunca {@code 1234.56}. O PWA formata do mesmo jeito os valores que ele mesmo
 * mostra, então uma recusa e a comanda ao lado dela falam a mesma língua.
 *
 * <p>Cada chamada cria o próprio formatador de número, porque o do Java não pode ser dividido entre
 * threads; o de data pode, e é um só.
 */
public final class FormatoDeTela {

    private static final DecimalFormatSymbols PORTUGUES_DO_BRASIL =
            DecimalFormatSymbols.getInstance(Locale.forLanguageTag("pt-BR"));

    private static final DateTimeFormatter DIA = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private FormatoDeTela() {
    }

    /**
     * O valor em reais, com ponto de milhar e vírgula nos centavos, e o sinal antes do símbolo
     * quando negativo, como a tela mostra uma diferença: {@code -R$ 5,00}.
     */
    public static String reais(Money valor) {
        BigDecimal numero = valor.valor();
        String sinal = numero.signum() < 0 ? "-" : "";
        return sinal + "R$ " + new DecimalFormat("#,##0.00", PORTUGUES_DO_BRASIL).format(numero.abs());
    }

    /**
     * A quantidade com vírgula e sem os zeros que sobram à direita: {@code 2} e {@code 0,35}. Até três
     * casas, que é o que a quantidade de um item guarda.
     */
    public static String quantidade(BigDecimal quantidade) {
        return new DecimalFormat("#,##0.###", PORTUGUES_DO_BRASIL).format(quantidade);
    }

    /** O dia como o balcão o escreve: {@code 23/10/2026}. */
    public static String data(LocalDate dia) {
        return DIA.format(dia);
    }
}
