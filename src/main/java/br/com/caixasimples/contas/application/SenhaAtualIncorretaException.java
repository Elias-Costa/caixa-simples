package br.com.caixasimples.contas.application;

/**
 * A troca da senha, do e-mail ou da chave Pix da Conta recebeu uma senha atual que não confere.
 *
 * <p>Vira 400, e não 401: quem pede a troca está autenticado, e o aplicativo trata o 401 como sessão
 * encerrada, o que o mandaria ao login por um erro de digitação. Conta na contenção do login como
 * uma senha errada, para a troca não servir de porta a tentativas sem limite.
 *
 * <p>Diz de qual troca veio porque o log registra cada uma com o próprio nome de evento.
 */
public class SenhaAtualIncorretaException extends RuntimeException {

    /** O que a senha atual errada tentava trocar. */
    public enum Troca {
        SENHA,
        EMAIL,
        CHAVE_PIX
    }

    private final Troca troca;

    public SenhaAtualIncorretaException(Troca troca) {
        super("A senha atual não confere.");
        this.troca = troca;
    }

    public Troca troca() {
        return troca;
    }
}
