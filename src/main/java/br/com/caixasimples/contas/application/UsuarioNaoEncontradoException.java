package br.com.caixasimples.contas.application;

import java.util.UUID;

/**
 * Não existe usuário com esse id <strong>nesta conta</strong>.
 *
 * <p>Um id de outra conta é indistinguível de um id que nunca existiu, porque toda consulta de
 * {@code UsuarioRepository} passa pelo filtro de tenant (RNF05).
 */
public class UsuarioNaoEncontradoException extends RuntimeException {

    public UsuarioNaoEncontradoException(UUID id) {
        super("usuario nao encontrado nesta conta: " + id);
    }
}
