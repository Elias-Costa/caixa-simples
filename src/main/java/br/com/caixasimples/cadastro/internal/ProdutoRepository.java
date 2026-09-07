package br.com.caixasimples.cadastro.internal;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repositorio da raiz de agregado {@code Produto}.
 *
 * <p>Toda consulta aqui e filtrada automaticamente por {@code conta_id} pelo {@code @TenantId} —
 * inclusive {@link #findAll()} e {@link #findById(Object)}. Nao escreva {@code WHERE conta_id} a
 * mao, e nao use query nativa: o filtro do Hibernate nao alcanca SQL nativo.
 *
 * <p><strong>Nao ha consulta por atributo do JSONB aqui</strong>, apesar do indice GIN existir:
 * nenhum requisito da Fase 1 pede filtrar produto por atributo — RF06 busca por nome ou codigo, e
 * isso nasce na etapa 1.6. Abstracao se justifica com uso, nao com previsao.
 *
 * <p>{@code MovimentoEstoque} e membro do agregado e nunca tera repositorio proprio: e carregado e
 * alterado pela raiz (regra 3 do CLAUDE.md).
 */
public interface ProdutoRepository extends JpaRepository<ProdutoEntity, UUID> {

    List<ProdutoEntity> findByAtivoTrue();
}
