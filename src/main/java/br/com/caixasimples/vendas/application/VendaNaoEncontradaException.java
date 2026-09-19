package br.com.caixasimples.vendas.application;

import java.util.UUID;

/**
 * Não existe venda com esse id <strong>nesta conta</strong>.
 *
 * <p>A distinção entre um id que nunca existiu e um id que existe em outra conta não chega até
 * aqui, e isso é o desenho funcionando: toda consulta de {@code VendaRepository} passa pelo filtro
 * de {@code @TenantId}, então a venda de outra conta simplesmente não volta do banco (RNF05). Nem
 * o caso de uso nem quem o chama tem como diferenciar os dois casos, e nem deve ter.
 *
 * <p>Sem aspas duplas neste javadoc, de propósito: o {@code Documenter} do Modulith lê os
 * comentários de um JSON gerado pelo processador de anotações com um parser simples, que trata
 * aspa dentro de string como delimitador e faz o teste de fronteiras estourar com um erro que não
 * menciona javadoc.
 */
public class VendaNaoEncontradaException extends RuntimeException {

    public VendaNaoEncontradaException(UUID id) {
        super("venda nao encontrada nesta conta: " + id);
    }
}
