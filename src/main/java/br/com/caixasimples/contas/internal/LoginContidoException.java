package br.com.caixasimples.contas.internal;

import java.time.Duration;

/**
 * Login recusado antes de conferir a senha, porque a origem, ou o e-mail vindo dela, já errou
 * demais na janela atual.
 *
 * <p>Não diz qual das contagens se esgotou nem se o e-mail existe: a recusa vem antes de qualquer
 * consulta, e nem sabe. Carrega só a espera até a janela acabar, que a resposta mostra a quem tenta.
 */
public class LoginContidoException extends RuntimeException {

    private final Duration espera;

    public LoginContidoException(Duration espera) {
        super("tentativas de login demais na janela atual");
        this.espera = espera;
    }

    /** Quanto falta para a contagem esgotada zerar, sempre mais que zero. */
    public Duration espera() {
        return espera;
    }
}
