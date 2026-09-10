package br.com.caixasimples.caixa.internal;

import br.com.caixasimples.caixa.TipoMovimentoCaixa;
import br.com.caixasimples.caixa.domain.MovimentoCaixa;
import br.com.caixasimples.shared.ContaId;
import br.com.caixasimples.shared.Money;
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
 * Mapeamento JPA da tabela {@code movimento_caixa}.
 *
 * <p><strong>Esta classe é de visibilidade de pacote, e isso é a regra do agregado escrita em
 * Java.</strong> {@code MovimentoCaixa} é membro, não raiz: só {@link SessaoCaixaEntity} a
 * enxerga, ninguém de fora consegue declarar uma variável deste tipo, e por isso não existe nem
 * pode existir um {@code MovimentoCaixaRepository}. Um repositório aqui seria uma porta lateral
 * para lançar sangria sem atualizar o esperado da sessão.
 *
 * <p>Leva {@link TenantId} como qualquer tabela de negócio, mesmo sendo membro. A lista de tabelas
 * fora do filtro de tenant tem exatamente três entradas, e nenhuma delas é esta. Na prática isso
 * significa que a coleção da raiz já vem filtrada por conta, sem depender de JOIN.
 */
@Entity
@Table(name = "movimento_caixa")
class MovimentoCaixaEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "conta_id", nullable = false, updatable = false)
    private UUID contaId;

    /**
     * Referência entre agregados, sempre por id, e sem {@code FOREIGN KEY} no banco porque a tabela
     * {@code venda} ainda não existe.
     */
    @Column(name = "venda_id")
    private UUID vendaId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TipoMovimentoCaixa tipo;

    /** Sempre positivo. Quem carrega o sinal é o {@link #tipo}. */
    @Column(nullable = false)
    private BigDecimal valor;

    /** Obrigatório em SANGRIA e SUPRIMENTO (RF14), pelo CHECK da migration. */
    private String motivo;

    @Column(name = "criado_em", nullable = false, updatable = false)
    private Instant criadoEm;

    protected MovimentoCaixaEntity() {
        // exigido pelo JPA
    }

    private MovimentoCaixaEntity(MovimentoCaixa movimento) {
        this.id = movimento.id();
        this.tipo = movimento.tipo();
        this.valor = movimento.valor().valor();
        this.motivo = movimento.motivo();
        this.vendaId = movimento.vendaId();
        this.criadoEm = movimento.criadoEm();
    }

    static MovimentoCaixaEntity de(MovimentoCaixa movimento) {
        return new MovimentoCaixaEntity(movimento);
    }

    /**
     * Não há {@code atualizarCom} aqui, ao contrário de {@code ProdutoEntity}: movimento de caixa
     * lançado não se edita. Corrigir um lançamento é lançar o oposto, para o histórico continuar
     * contando o que de fato aconteceu na gaveta.
     */
    MovimentoCaixa paraDominio() {
        return new MovimentoCaixa(id, tipo, Money.de(valor), motivo, vendaId, criadoEm);
    }

    /**
     * Existe só para {@code SessaoCaixaEntity.atualizarCom} saber o que já está gravado e não
     * reinserir o histórico inteiro a cada sangria.
     */
    UUID getId() {
        return id;
    }

    /** Existe para o teste de isolamento poder afirmar que o membro herdou a conta da raiz. */
    ContaId getContaId() {
        return ContaId.de(contaId);
    }
}
