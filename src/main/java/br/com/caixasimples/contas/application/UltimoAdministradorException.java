package br.com.caixasimples.contas.application;

import java.util.UUID;

/**
 * A conta precisa manter ao menos um administrador ativo, e este é o último.
 *
 * <p>Sem ele ninguém mais cadastra produto, lê relatório nem cria usuário, e não há cadastro
 * público nem outra porta pela qual a conta se recupere sozinha.
 */
public class UltimoAdministradorException extends RuntimeException {

    public UltimoAdministradorException(UUID id) {
        super("Este é o último administrador ativo da Conta e não pode ser inativado.");
    }
}
