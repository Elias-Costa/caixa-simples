package br.com.caixasimples.contas.application;

/**
 * O administrador só redefine a senha de um operador ativo.
 *
 * <p>Um administrador que redefinisse a senha de outro tomaria o acesso dele, e o usuário inativo já
 * não tem login para redefinir. O administrador que esqueceu a própria senha é socorrido pelo
 * mantenedor, fora do aplicativo.
 */
public class RedefinicaoRecusadaException extends RuntimeException {

    public RedefinicaoRecusadaException() {
        super("Só a senha de um operador ativo pode ser redefinida.");
    }
}
