package br.com.caixasimples.cadastro.application;

import java.util.UUID;

/**
 * Nao existe produto com esse id <strong>nesta conta</strong>.
 *
 * <p>A distincao entre um id que nunca existiu e um id que existe em outra conta nao chega ate
 * aqui, e isso e o desenho funcionando: toda consulta de {@code ProdutoRepository} passa pelo
 * filtro de {@code @TenantId}, entao o produto de outra conta simplesmente nao volta do banco
 * (RNF05). Nem o caso de uso nem quem o chama tem como diferenciar os dois casos — e nao deve ter.
 *
 * <p>Sem aspas duplas neste javadoc, de proposito: o {@code Documenter} do Modulith le os
 * comentarios de um JSON gerado pelo apt com o parser mais simples do Spring Boot, que trata aspa
 * dentro de string como delimitador e estoura em {@code ModularityTests}. Ver D18.
 */
public class ProdutoNaoEncontradoException extends RuntimeException {

    public ProdutoNaoEncontradoException(UUID id) {
        super("produto nao encontrado nesta conta: " + id);
    }
}
