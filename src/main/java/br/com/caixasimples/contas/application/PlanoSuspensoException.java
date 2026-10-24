package br.com.caixasimples.contas.application;

import br.com.caixasimples.shared.FormatoDeTela;
import java.time.LocalDate;

/**
 * O plano inclui o recurso, mas os recursos pagos estão suspensos: a tolerância depois do
 * vencimento acabou sem renovação. Venda, caixa e cadastro continuam; a renovação devolve o resto.
 *
 * <p>Estende {@link IllegalStateException}, que responde 409 com a mensagem, como a recusa por
 * recurso fora do plano.
 */
public class PlanoSuspensoException extends IllegalStateException {

    public PlanoSuspensoException(LocalDate inicioDaSuspensao) {
        super("Os recursos pagos do plano estão suspensos desde "
                + FormatoDeTela.data(inicioDaSuspensao) + ", por falta de renovação.");
    }
}
