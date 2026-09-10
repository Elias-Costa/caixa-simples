package br.com.caixasimples.vendas.internal;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repositório da raiz de agregado {@code Venda}.
 *
 * <p>Toda consulta aqui é filtrada automaticamente por {@code conta_id} pelo {@code @TenantId},
 * inclusive {@link #findAll()} e {@link #findById(Object)}. Não escreva {@code WHERE conta_id} à
 * mão, e não use query nativa: o filtro do Hibernate não alcança SQL nativo.
 *
 * <p><strong>Nenhum método derivado ainda, e cada um nasce junto do caso de uso que o usa, nunca
 * antes.</strong> O que o módulo faz hoje cabe em {@code save}, {@code findById} e
 * {@code findAll}. Quando os relatórios por dia chegarem, a consulta deve sair como projeção, e
 * não como lista de agregados inteiros: as duas coleções da entidade são {@code EAGER}, e listar
 * vendas traria os itens e os pagamentos de todas.
 *
 * <p>{@code ItemVenda} e {@code Pagamento} são membros do agregado e nunca terão repositório
 * próprio: são carregados e alterados pela raiz. As entidades deles nem sequer são visíveis fora
 * deste pacote, então a regra não depende apenas de disciplina.
 */
public interface VendaRepository extends JpaRepository<VendaEntity, UUID> {
}
