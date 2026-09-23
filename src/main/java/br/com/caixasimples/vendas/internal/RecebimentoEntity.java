package br.com.caixasimples.vendas.internal;

import br.com.caixasimples.pagamentos.FormaPagamento;
import br.com.caixasimples.shared.Money;
import br.com.caixasimples.vendas.domain.Recebimento;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

/** Membro do agregado Venda; a linha é escrita apenas pela raiz. */
@Entity
@Table(name = "recebimento")
class RecebimentoEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "conta_id", nullable = false, updatable = false)
    private UUID contaId;

    @Column(name = "sessao_caixa_id", nullable = false, updatable = false)
    private UUID sessaoCaixaId;

    @Column(nullable = false, updatable = false)
    private BigDecimal valor;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private FormaPagamento forma;

    @Column(name = "criado_em", nullable = false, updatable = false)
    private Instant criadoEm;

    protected RecebimentoEntity() {
        // exigido pelo JPA
    }

    private RecebimentoEntity(Recebimento recebimento) {
        this.id = recebimento.id();
        this.sessaoCaixaId = recebimento.sessaoCaixaId();
        this.valor = recebimento.valor().valor();
        this.forma = recebimento.forma();
        this.criadoEm = recebimento.criadoEm();
    }

    static RecebimentoEntity de(Recebimento recebimento) {
        return new RecebimentoEntity(recebimento);
    }

    Recebimento paraDominio() {
        return new Recebimento(id, sessaoCaixaId, Money.de(valor), forma, criadoEm);
    }

    UUID getId() {
        return id;
    }
}
