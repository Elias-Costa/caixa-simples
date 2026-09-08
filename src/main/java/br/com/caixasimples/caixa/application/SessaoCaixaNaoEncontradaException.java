package br.com.caixasimples.caixa.application;

import java.util.UUID;

/**
 * Nao existe sessao de caixa com esse id <strong>nesta conta</strong>.
 *
 * <p>A distincao entre um id que nunca existiu e um id que existe em outra conta nao chega ate
 * aqui, e isso e o desenho funcionando: toda consulta de {@code SessaoCaixaRepository} passa pelo
 * filtro de {@code @TenantId}, entao a sessao de outra conta simplesmente nao volta do banco
 * (RNF05). Nem o caso de uso nem quem o chama tem como diferenciar os dois casos — e nao deve ter.
 *
 * <p>Sem aspas duplas neste javadoc, de proposito: o {@code Documenter} do Modulith le os
 * comentarios de um JSON gerado pelo apt com o parser mais simples do Spring Boot, que trata aspa
 * dentro de string como delimitador e estoura em {@code ModularityTests}. Ver D18.
 */
public class SessaoCaixaNaoEncontradaException extends RuntimeException {

    public SessaoCaixaNaoEncontradaException(UUID id) {
        super("sessao de caixa nao encontrada nesta conta: " + id);
    }
}
