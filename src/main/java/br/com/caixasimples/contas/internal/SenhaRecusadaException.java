package br.com.caixasimples.contas.internal;

/**
 * Senha rejeitada ao ser definida: curta demais, vazada, ou impossível de verificar.
 *
 * <p>Nunca é lançada no login. Lá a resposta é sempre a mesma, independentemente do motivo, para
 * não revelar se o e-mail existe.
 */
public class SenhaRecusadaException extends RuntimeException {

    public SenhaRecusadaException(String motivo) {
        super(motivo);
    }

    public SenhaRecusadaException(String motivo, Throwable causa) {
        super(motivo, causa);
    }
}
