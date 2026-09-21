package br.com.caixasimples.contas.application;

/**
 * Já existe login com esse e-mail, nesta ou em qualquer outra conta.
 *
 * <p>E-mail é único globalmente, porque é ele que descobre a conta no login. A mensagem não diz
 * em qual conta ele está, e nem poderia: a conta que tenta cadastrar não tem como enxergar as
 * outras (RNF05).
 */
public class EmailJaCadastradoException extends RuntimeException {

    public EmailJaCadastradoException(String email) {
        super("ja existe um usuario com o e-mail " + email);
    }
}
