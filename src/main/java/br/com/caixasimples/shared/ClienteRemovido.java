package br.com.caixasimples.shared;

import java.util.Objects;
import java.util.UUID;

/** Fato usado para retirar cópias do cadastro sem abrir uma dependência circular entre módulos. */
public record ClienteRemovido(ContaId contaId, UUID clienteId) {

    public ClienteRemovido {
        Objects.requireNonNull(contaId, "contaId nao pode ser nulo");
        Objects.requireNonNull(clienteId, "clienteId nao pode ser nulo");
    }
}
