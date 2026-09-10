package br.com.caixasimples.shared;

import java.util.Objects;
import java.util.UUID;

/**
 * Identidade de uma {@code Conta}, isto é, do tenant: o negócio contratante.
 *
 * <p>Existe como tipo próprio para que assinatura de método não aceite qualquer {@code UUID} no
 * lugar de um identificador de conta. Conta, na linguagem deste sistema, nunca significa conta a
 * pagar ou a receber.
 */
public record ContaId(UUID valor) {

    public ContaId {
        Objects.requireNonNull(valor, "contaId nao pode ser nulo");
    }

    public static ContaId de(UUID valor) {
        return new ContaId(valor);
    }

    public static ContaId de(String valor) {
        return new ContaId(UUID.fromString(valor));
    }

    /**
     * Nova identidade gerada na aplicação, nunca no banco. É o que permite criar um registro sem
     * conexão com identidade definitiva e sincronizar depois sem renumerar nada (RNF01, RNF03).
     */
    public static ContaId nova() {
        return new ContaId(UUID.randomUUID());
    }

    @Override
    public String toString() {
        return valor.toString();
    }
}
