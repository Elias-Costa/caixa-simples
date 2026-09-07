package br.com.caixasimples.shared;

import java.util.Objects;
import java.util.UUID;

/**
 * Identidade de uma {@code Conta} — o tenant, isto e, o negocio contratante.
 *
 * <p>Existe como tipo proprio para que assinatura de metodo nao aceite qualquer {@code UUID} no
 * lugar de um identificador de conta. "Conta" aqui nunca significa conta a pagar ou a receber
 * (linguagem ubiqua, arquitetura §1).
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

    /** Nova identidade gerada na aplicacao — nunca no banco (RNF01/RNF03). */
    public static ContaId nova() {
        return new ContaId(UUID.randomUUID());
    }

    @Override
    public String toString() {
        return valor.toString();
    }
}
