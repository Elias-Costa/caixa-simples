package br.com.caixasimples.cadastro;

import br.com.caixasimples.shared.Money;
import java.util.UUID;

/** Perguntas que o cadastro faz antes de anonimizar um Cliente. */
public interface PendenciasDoCliente {

    Money saldoDevedor(UUID clienteId);

    boolean temComandaAberta(UUID clienteId);
}
