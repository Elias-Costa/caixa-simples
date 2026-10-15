package br.com.caixasimples.contas.application;

/**
 * A troca de senha recebeu uma senha atual que não confere.
 *
 * <p>Vira 400, e não 401: quem pede a troca está autenticado, e o aplicativo trata o 401 como sessão
 * encerrada, o que o mandaria ao login por um erro de digitação. Conta na contenção do login como
 * uma senha errada, para a troca não servir de porta a tentativas sem limite.
 */
public class SenhaAtualIncorretaException extends RuntimeException {

    public SenhaAtualIncorretaException() {
        super("A senha atual não confere.");
    }
}
