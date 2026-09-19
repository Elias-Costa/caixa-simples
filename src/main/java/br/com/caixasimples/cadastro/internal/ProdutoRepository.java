package br.com.caixasimples.cadastro.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repositório da raiz de agregado {@code Produto}.
 *
 * <p>Toda consulta aqui é filtrada automaticamente por {@code conta_id} pelo {@code @TenantId},
 * inclusive {@link #findAll()} e {@link #findById(Object)}. Não escreva {@code WHERE conta_id} à
 * mão, e não use query nativa: o filtro do Hibernate não alcança SQL nativo.
 *
 * <p><strong>Não há consulta por atributo do JSONB aqui</strong>, apesar de o índice GIN existir.
 * Nenhum requisito atual pede filtrar produto por atributo, já que a busca durante a venda é por
 * nome ou código (RF06). Abstração se justifica com uso, não com previsão.
 *
 * <p>{@code MovimentoEstoque} é membro do agregado e nunca terá repositório próprio: é carregado e
 * alterado pela raiz.
 */
public interface ProdutoRepository extends JpaRepository<ProdutoEntity, UUID> {

    List<ProdutoEntity> findByAtivoTrue();

    /**
     * O produto ativo com este código, ignorando maiúsculas (RF06).
     *
     * <p>Devolve {@link Optional}, e não lista, porque a migration V2 garante um código por conta
     * entre os ativos; é o índice único parcial que permite esta assinatura. Casa o código
     * inteiro, nunca prefixo: código é o que o operador digita ou lê da embalagem, e um prefixo
     * devolveria mais de um produto para um valor que deveria ser único.
     */
    Optional<ProdutoEntity> findByAtivoTrueAndCodigoIgnoreCase(String codigo);

    /**
     * Os produtos ativos cujo nome contém o termo, ignorando maiúsculas, em ordem alfabética
     * (RF06).
     *
     * <p>{@code Containing} derivado escapa {@code %} e {@code _} do termo por conta própria, o que
     * é um motivo a mais para não escrever o {@code LIKE} à mão. Sem índice para sustentar a
     * busca: o catálogo de uma conta é pequeno, e o índice de tenant já reduz a varredura ao que é
     * dela.
     */
    List<ProdutoEntity> findByAtivoTrueAndNomeContainingIgnoreCaseOrderByNome(String termo);
}
