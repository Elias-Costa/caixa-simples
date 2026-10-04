package br.com.caixasimples.contas.internal;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repositório do pedido de plano, entidade única sem membros.
 *
 * <p>Toda consulta aqui é filtrada automaticamente por {@code conta_id} pelo {@code @TenantId},
 * inclusive {@link #findById(Object)}: o id de um pedido de outra Conta volta vazio, como se não
 * existisse. Não escreva {@code WHERE conta_id} à mão, e não use query nativa: o filtro do
 * Hibernate não alcança SQL nativo.
 */
public interface PedidoDePlanoRepository extends JpaRepository<PedidoDePlano, UUID> {

    /**
     * O pedido aberto, à espera do código, se houver. Só serve para essa situação: o índice único
     * parcial garante um aberto por Conta, e das outras situações a Conta pode ter vários.
     */
    Optional<PedidoDePlano> findBySituacao(PedidoDePlano.Situacao situacao);
}
