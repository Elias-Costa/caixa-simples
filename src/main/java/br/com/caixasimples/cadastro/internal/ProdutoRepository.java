package br.com.caixasimples.cadastro.internal;

import java.util.List;
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
}
