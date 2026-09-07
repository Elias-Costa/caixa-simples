package br.com.caixasimples;

import org.junit.jupiter.api.Test;

/**
 * Prova que o contexto sobe inteiro contra um Postgres real, com as migrations aplicadas e o
 * {@code ddl-auto: validate} conferindo que o schema bate com as entidades JPA.
 */
class CaixaSimplesApplicationTests extends TesteDeIntegracao {

    @Test
    void contextLoads() {
    }
}
