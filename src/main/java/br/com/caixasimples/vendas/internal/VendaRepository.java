package br.com.caixasimples.vendas.internal;

import java.util.UUID;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repositório da raiz de agregado {@code Venda}.
 *
 * <p>Toda consulta aqui é filtrada automaticamente por {@code conta_id} pelo {@code @TenantId},
 * inclusive {@link #findAll()} e {@link #findById(Object)}. Não escreva {@code WHERE conta_id} à
 * mão, e não use query nativa: o filtro do Hibernate não alcança SQL nativo.
 *
 * <p>A lista por SessaoCaixa carrega o agregado para aplicar a regra de quem pode ver a Venda.
 * Cada sessão reúne as comandas de um expediente; relatórios por período usam mapeamentos
 * próprios somente leitura e não carregam as coleções da raiz.
 *
 * <p>{@code ItemVenda} e {@code Pagamento} são membros do agregado e nunca terão repositório
 * próprio: são carregados e alterados pela raiz. As entidades deles nem sequer são visíveis fora
 * deste pacote, então a regra não depende apenas de disciplina.
 */
public interface VendaRepository extends JpaRepository<VendaEntity, UUID> {
    List<VendaEntity> findBySessaoCaixaIdOrderByCriadoEmDesc(UUID sessaoCaixaId);
}
