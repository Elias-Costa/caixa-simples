package br.com.caixasimples.vendas.internal;

import java.util.UUID;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import br.com.caixasimples.vendas.StatusVenda;
import br.com.caixasimples.pagamentos.StatusPagamento;

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

    List<VendaEntity> findByClienteIdAndStatus(UUID clienteId, StatusVenda status);

    List<VendaEntity> findByStatus(StatusVenda status);

    /**
     * Se algum item desta conta já usa este id. O item lançado no dispositivo sem rede chega com o
     * id que ele gerou, e a pergunta vai pela raiz, sem repositório para o membro: salvar um item
     * com id já usado mesclaria o novo sobre o existente, porque a linha não tem versão.
     */
    boolean existsByItensId(UUID itemId);

    /** A mesma pergunta para a parcela lançada no dispositivo sem rede. */
    boolean existsByPagamentosId(UUID pagamentoId);

    /** Serializa recebimentos parciais da mesma dívida antes de conferir o saldo. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<VendaEntity> findLockedById(UUID id);

    /** Encontra somente a raiz da Conta atual pela correlação persistida da parcela. */
    @Query("select v from VendaEntity v join v.pagamentos p where p.pixTxid = :txid")
    Optional<VendaEntity> findByPixTxid(String txid);

    @Query("select distinct v from VendaEntity v join v.pagamentos p "
            + "where p.pixTxid is not null and p.status = :status")
    List<VendaEntity> findComPixIntegrado(StatusPagamento status);
}
