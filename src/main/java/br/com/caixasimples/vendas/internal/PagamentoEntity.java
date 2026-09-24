package br.com.caixasimples.vendas.internal;

import br.com.caixasimples.pagamentos.FormaPagamento;
import br.com.caixasimples.pagamentos.StatusPagamento;
import br.com.caixasimples.pagamentos.domain.CobrancaPix;
import br.com.caixasimples.shared.ContaId;
import br.com.caixasimples.shared.Money;
import br.com.caixasimples.vendas.domain.Pagamento;
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

/**
 * Mapeamento JPA da tabela {@code pagamento}.
 *
 * <p>Visibilidade de pacote pelo mesmo motivo de {@link ItemVendaEntity}: {@code Pagamento} é
 * membro do agregado Venda, só {@link VendaEntity} a enxerga, e não existe nem pode existir um
 * {@code PagamentoRepository}. Uma parcela alterada por fora deixaria a venda CONCLUIDA com
 * pagamentos que não cobrem o total.
 *
 * <p>Leva {@link TenantId} como qualquer tabela de negócio, mesmo sendo membro.
 */
@Entity
@Table(name = "pagamento")
class PagamentoEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "conta_id", nullable = false, updatable = false)
    private UUID contaId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private FormaPagamento forma;

    /** O valor desta parcela, não o total da venda. */
    @Column(nullable = false)
    private BigDecimal valor;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StatusPagamento status;

    /** Zero fora de dinheiro e quando o cliente pagou o valor exato, nunca nulo. */
    @Column(nullable = false)
    private BigDecimal troco;

    @Column(name = "criado_em", nullable = false, updatable = false)
    private Instant criadoEm;

    @Column(name = "pix_txid", length = 35)
    private String pixTxid;

    @Column(name = "pix_chave_recebedora")
    private String pixChaveRecebedora;

    @Column(name = "pix_expira_em")
    private Instant pixExpiraEm;

    @Column(name = "pix_copia_e_cola", columnDefinition = "text")
    private String pixCopiaECola;

    @Enumerated(EnumType.STRING)
    @Column(name = "pix_estado")
    private CobrancaPix.Estado pixEstado;

    protected PagamentoEntity() {
        // exigido pelo JPA
    }

    private PagamentoEntity(Pagamento pagamento) {
        this.id = pagamento.id();
        this.forma = pagamento.forma();
        this.valor = pagamento.valor().valor();
        this.status = pagamento.status();
        this.troco = pagamento.troco().valor();
        this.criadoEm = pagamento.criadoEm();
        atualizarCobranca(pagamento.cobrancaPix());
    }

    static PagamentoEntity de(Pagamento pagamento) {
        return new PagamentoEntity(pagamento);
    }

    /** Não há {@code atualizarCom} aqui: parcela lançada não se edita. */
    Pagamento paraDominio() {
        CobrancaPix cobranca = pixTxid == null ? null
                : new CobrancaPix(pixTxid, pixChaveRecebedora, pixExpiraEm,
                        pixCopiaECola, pixEstado);
        return new Pagamento(id, forma, Money.de(valor), status, Money.de(troco), criadoEm,
                cobranca);
    }

    void atualizarCobranca(CobrancaPix cobranca) {
        if (cobranca != null) {
            this.pixTxid = cobranca.txid();
            this.pixChaveRecebedora = cobranca.chaveRecebedora();
            this.pixExpiraEm = cobranca.expiraEm();
            this.pixCopiaECola = cobranca.copiaECola();
            this.pixEstado = cobranca.estado();
        }
    }

    void atualizarStatus(br.com.caixasimples.pagamentos.StatusPagamento novoStatus) {
        this.status = novoStatus;
    }

    UUID getId() {
        return id;
    }

    /** Existe para o teste de isolamento poder afirmar que o membro herdou a conta da raiz. */
    ContaId getContaId() {
        return ContaId.de(contaId);
    }
}
