package br.com.caixasimples.contas.application;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * O plano inclui o recurso, mas os recursos pagos estão suspensos: a tolerância depois do
 * vencimento acabou sem renovação. Venda, caixa e cadastro continuam; a renovação devolve o resto.
 *
 * <p>Estende {@link IllegalStateException}, que responde 409 com a mensagem, como a recusa por
 * recurso fora do plano.
 */
public class PlanoSuspensoException extends IllegalStateException {

    private static final DateTimeFormatter DATA = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    public PlanoSuspensoException(LocalDate inicioDaSuspensao) {
        super("os recursos pagos do plano estao suspensos desde " + DATA.format(inicioDaSuspensao)
                + ", por falta de renovacao");
    }
}
