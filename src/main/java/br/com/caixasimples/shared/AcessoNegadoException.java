package br.com.caixasimples.shared;

/**
 * Quem chama está autenticado, mas o perfil dele não alcança a operação (RF30).
 *
 * <p>Diferente de {@link UsuarioNaoResolvidoException}: aqui se sabe quem é, e a resposta é não.
 * A camada HTTP, quando existir, traduz esta em 403 e aquela em 401.
 */
public class AcessoNegadoException extends RuntimeException {

    public AcessoNegadoException(String mensagem) {
        super(mensagem);
    }
}
