package br.com.caixasimples.contas.internal;

/**
 * Senha rejeitada ao ser definida: curta demais, vazada, ou impossivel de verificar.
 *
 * <p>Nunca e lancada no login — la a resposta e sempre a mesma, independente do motivo, para nao
 * revelar se o e-mail existe.
 */
public class SenhaRecusadaException extends RuntimeException {

    public SenhaRecusadaException(String motivo) {
        super(motivo);
    }

    public SenhaRecusadaException(String motivo, Throwable causa) {
        super(motivo, causa);
    }
}
