package br.com.caixasimples.shared;

import java.util.Objects;
import java.util.UUID;

/**
 * Fato usado para esvaziar as cópias do cadastro anterior à edição sem abrir uma dependência
 * circular entre módulos: a correção pedida pelo titular chega também a elas.
 */
public record ClienteEditado(ContaId contaId, UUID clienteId) {

    public ClienteEditado {
        Objects.requireNonNull(contaId, "contaId nao pode ser nulo");
        Objects.requireNonNull(clienteId, "clienteId nao pode ser nulo");
    }
}
