package br.com.caixasimples.contas.application;

/**
 * O login conferiu a senha, mas o acesso da Conta está suspenso por uso ilegal ou ataque ao serviço.
 *
 * <p>Vira 403 com a mensagem, e não o 401 da senha errada: quem tem a senha certa precisa saber por
 * que não entra, em vez de achar que a errou e cair na espera da contenção. A mensagem só aparece
 * depois da senha conferida, então não diz a ninguém quais e-mails existem. O motivo da suspensão não
 * vai nela, porque a tela de entrada é vista também pelo funcionário; ele vai ao administrador, pelo
 * aviso por e-mail.
 *
 * <p>Não conta na contenção do login: a senha estava certa, e a recusa não é uma tentativa de
 * adivinhá-la.
 */
public class ContaSuspensaException extends RuntimeException {

    public ContaSuspensaException() {
        super("O acesso desta Conta está suspenso. O administrador recebeu o aviso por e-mail.");
    }
}
