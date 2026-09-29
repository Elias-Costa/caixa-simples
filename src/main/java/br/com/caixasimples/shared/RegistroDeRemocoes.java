package br.com.caixasimples.shared;

import java.time.Instant;
import java.util.UUID;

/** Registro externo que continua disponível quando uma cópia antiga do banco é restaurada. */
public interface RegistroDeRemocoes {

    enum Tipo { CLIENTE, USUARIO, NOME_USUARIO, CONTA }

    void registrar(Tipo tipo, UUID registroId, Instant instante, UUID solicitadoPor);
}
