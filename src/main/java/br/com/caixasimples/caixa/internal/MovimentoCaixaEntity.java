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
 * <p><strong>Esta classe e de visibilidade de pacote, e isso e a regra do agregado escrita em
 * Java.</strong> {@code MovimentoCaixa} e membro, nao raiz (modelo de dados §4): so
 * {@link SessaoCaixaEntity} a enxerga, ninguem de fora consegue declarar uma variavel deste tipo, e
 * por isso nao existe nem pode existir um {@code MovimentoCaixaRepository}. Um repositorio aqui
 * seria uma porta lateral para lancar sangria sem atualizar o esperado da sessao.
 *
 * <p>Leva {@link TenantId} como qualquer tabela de negocio, mesmo sendo membro: o
 * {@code .claude/rules/multi-tenancy.md} fecha a lista de excecoes em tres, e nenhuma e esta. Na
 * pratica isso significa que a colecao da raiz ja vem filtrada por conta, sem depender de JOIN.
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
     * Referencia entre agregados, sempre por id (modelo de dados §4) — e sem {@code FOREIGN KEY} no
     * banco, porque a tabela {@code venda} so nasce no R11.
     */
    @Column(name = "venda_id")
    private UUID vendaId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TipoMovimentoCaixa tipo;

    /** D21b — sempre positivo. Quem carrega o sinal e o {@link #tipo}. */
    @Column(nullable = false)
    private BigDecimal valor;

    /** RF14 — obrigatorio em SANGRIA e SUPRIMENTO, pelo CHECK da migration. */
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
     * Nao ha {@code atualizarCom} aqui, ao contrario de {@code ProdutoEntity}: movimento de caixa
     * lancado nao se edita. Corrigir um lancamento e lancar o oposto, para o historico continuar
     * contando o que de fato aconteceu na gaveta.
     */
    MovimentoCaixa paraDominio() {
        return new MovimentoCaixa(id, tipo, Money.de(valor), motivo, vendaId, criadoEm);
    }

    /** Existe para o teste de isolamento poder afirmar que o membro herdou a conta da raiz. */
    ContaId getContaId() {
        return ContaId.de(contaId);
    }
}
