package br.com.caixasimples.contas.internal;

/**
 * A lista de senhas vazadas não respondeu, e a senha não pôde ser conferida.
 *
 * <p>É uma recusa de senha como as outras para quem não a distingue, e a política a separa da senha
 * vazada só quando a própria pessoa escolheu a senha: nesse caso a senha entra sem a conferência.
 */
public class ListaDeVazadasIndisponivelException extends SenhaRecusadaException {

    public ListaDeVazadasIndisponivelException(String motivo) {
        super(motivo);
    }

    public ListaDeVazadasIndisponivelException(String motivo, Throwable causa) {
        super(motivo, causa);
    }
}
