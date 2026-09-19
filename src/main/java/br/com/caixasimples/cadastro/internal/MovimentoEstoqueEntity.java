package br.com.caixasimples.cadastro.internal;

import br.com.caixasimples.cadastro.TipoMovimentoEstoque;
import br.com.caixasimples.cadastro.domain.MovimentoEstoque;
import br.com.caixasimples.shared.ContaId;
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
 * Mapeamento JPA da tabela {@code movimento_estoque}.
 *
 * <p><strong>Esta classe é de visibilidade de pacote, e isso é a regra do agregado escrita em
 * Java.</strong> {@code MovimentoEstoque} é membro, não raiz: só {@link ProdutoEntity} a enxerga,
 * ninguém de fora consegue declarar uma variável deste tipo, e por isso não existe nem pode
 * existir um {@code MovimentoEstoqueRepository}. Um repositório aqui seria uma porta lateral para
 * gravar um movimento sem mover o saldo do produto.
 *
 * <p>Leva {@link TenantId} como qualquer tabela de negócio, mesmo sendo membro. A lista de tabelas
 * fora do filtro de tenant tem exatamente três entradas, e nenhuma delas é esta.
 */
@Entity
@Table(name = "movimento_estoque")
class MovimentoEstoqueEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "conta_id", nullable = false, updatable = false)
    private UUID contaId;

    /** Referência entre agregados, sempre por id. A {@code FOREIGN KEY} está na migration V9. */
    @Column(name = "venda_id")
    private UUID vendaId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TipoMovimentoEstoque tipo;

    /** Positiva em ENTRADA e SAIDA. Quem carrega o sinal é o {@link #tipo}. */
    @Column(nullable = false)
    private BigDecimal quantidade;

    /** Obrigatório em AJUSTE (RF19), pelo CHECK da migration. */
    private String motivo;

    @Column(name = "criado_em", nullable = false, updatable = false)
    private Instant criadoEm;

    protected MovimentoEstoqueEntity() {
        // exigido pelo JPA
    }

    private MovimentoEstoqueEntity(MovimentoEstoque movimento) {
        this.id = movimento.id();
        this.tipo = movimento.tipo();
        this.quantidade = movimento.quantidade();
        this.motivo = movimento.motivo();
        this.vendaId = movimento.vendaId();
        this.criadoEm = movimento.criadoEm();
    }

    static MovimentoEstoqueEntity de(MovimentoEstoque movimento) {
        return new MovimentoEstoqueEntity(movimento);
    }

    /**
     * Não há {@code atualizarCom} aqui, como não há em {@code MovimentoCaixaEntity}: movimento
     * lançado não se edita. Corrigir é lançar o oposto, para o histórico continuar contando o que
     * de fato aconteceu com o estoque.
     */
    MovimentoEstoque paraDominio() {
        return new MovimentoEstoque(id, tipo, quantidade, motivo, vendaId, criadoEm);
    }

    UUID getId() {
        return id;
    }

    /** Existe para o teste de isolamento poder afirmar que o membro herdou a conta da raiz. */
    ContaId getContaId() {
        return ContaId.de(contaId);
    }
}
