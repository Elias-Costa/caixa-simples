package br.com.caixasimples.vendas.internal;

import br.com.caixasimples.shared.ContaId;
import br.com.caixasimples.shared.Money;
import br.com.caixasimples.vendas.domain.ItemVenda;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

/**
 * Mapeamento JPA da tabela {@code item_venda}.
 *
 * <p><strong>Esta classe é de visibilidade de pacote, e isso é a regra do agregado escrita em
 * Java.</strong> {@code ItemVenda} é membro, não raiz: só {@link VendaEntity} a enxerga, ninguém
 * de fora consegue declarar uma variável deste tipo, e por isso não existe nem pode existir um
 * {@code ItemVendaRepository}. Um repositório aqui seria uma porta lateral para alterar um item
 * sem recalcular o total da venda.
 *
 * <p>Leva {@link TenantId} como qualquer tabela de negócio, mesmo sendo membro. A lista de tabelas
 * fora do filtro de tenant tem exatamente três entradas, e nenhuma delas é esta. Na prática isso
 * significa que a coleção da raiz já vem filtrada por conta, sem depender de JOIN.
 */
@Entity
@Table(name = "item_venda")
class ItemVendaEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "conta_id", nullable = false, updatable = false)
    private UUID contaId;

    /** Referência entre agregados, sempre por id. */
    @Column(name = "produto_id", nullable = false, updatable = false)
    private UUID produtoId;

    @Column(nullable = false)
    private BigDecimal quantidade;

    /** Cópia do preço no momento da venda, nunca leitura viva do produto. */
    @Column(name = "preco_unitario", nullable = false)
    private BigDecimal precoUnitario;

    @Column(nullable = false)
    private BigDecimal desconto;

    @Column(name = "criado_em", nullable = false, updatable = false)
    private Instant criadoEm;

    protected ItemVendaEntity() {
        // exigido pelo JPA
    }

    private ItemVendaEntity(ItemVenda item) {
        this.id = item.id();
        this.produtoId = item.produtoId();
        this.quantidade = item.quantidade();
        this.precoUnitario = item.precoUnitario().valor();
        this.desconto = item.desconto().valor();
        this.criadoEm = item.criadoEm();
    }

    static ItemVendaEntity de(ItemVenda item) {
        return new ItemVendaEntity(item);
    }

    /**
     * Não há {@code atualizarCom} aqui: item lançado não se edita. Corrigir um item é removê-lo e
     * lançar outro pela raiz, que é quem mantém o total em dia.
     */
    ItemVenda paraDominio() {
        return new ItemVenda(id, produtoId, quantidade, Money.de(precoUnitario), Money.de(desconto),
                criadoEm);
    }

    UUID getId() {
        return id;
    }

    /** Existe para o teste de isolamento poder afirmar que o membro herdou a conta da raiz. */
    ContaId getContaId() {
        return ContaId.de(contaId);
    }
}
