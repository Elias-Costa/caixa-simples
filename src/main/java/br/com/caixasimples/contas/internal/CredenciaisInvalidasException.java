package br.com.caixasimples.contas.internal;

/**
 * Login recusado.
 *
 * <p>Deliberadamente sem detalhe: e-mail inexistente, senha errada e usuario inativo produzem esta
 * mesma excecao, com esta mesma mensagem. Distinguir os casos deixaria descobrir, tentativa a
 * tentativa, quais e-mails existem no sistema.
 */
public class CredenciaisInvalidasException extends RuntimeException {

    public CredenciaisInvalidasException() {
        super("E-mail ou senha invalidos");
    }
}
