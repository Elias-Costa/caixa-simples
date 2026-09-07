package br.com.caixasimples.cadastro.internal;

import br.com.caixasimples.cadastro.TipoProduto;
import br.com.caixasimples.cadastro.domain.Produto;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.TenantId;
import org.hibernate.type.SqlTypes;

/**
 * Mapeamento JPA da tabela {@code produto} e tradutor de e para {@link Produto}.
 *
 * <p>A entidade e o dominio sao classes separadas de proposito (arquitetura §2, P7): e o que
 * permite {@link Produto} nao conhecer {@code jakarta.persistence}. Este arquivo e a unica ponte
 * entre os dois.
 *
 * <p>{@code contaId} e preenchido pelo Hibernate a partir do {@code CurrentTenantIdentifierResolver}
 * e filtra toda consulta automaticamente ({@link TenantId}). Nao ha construtor nem setter que o
 * receba — {@code contaId} nunca vem de fora da aplicacao (RNF05).
 */
@Entity
@Table(name = "produto")
public class ProdutoEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "conta_id", nullable = false, updatable = false)
    private UUID contaId;

    @Column(nullable = false)
    private String nome;

    /** D11 — anulavel; unico por conta so entre ativos, pelo indice parcial da migration. */
    private String codigo;

    @Column(nullable = false)
    private BigDecimal preco;

    private String categoria;

    private String unidade;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TipoProduto tipo;

    @Column(name = "estoque_atual", nullable = false)
    private BigDecimal estoqueAtual;

    /**
     * RF02/RNF12 — atributos que variam por tipo de negocio, em {@code jsonb} com indice GIN.
     * Colunas fixas para o universal, JSONB para o que varia; nunca EAV (arquitetura §4).
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> atributos;

    /** Soft delete (RF05) — preserva o historico de vendas do item descontinuado. */
    @Column(nullable = false)
    private boolean ativo;

    @Column(name = "criado_em", nullable = false, updatable = false)
    private Instant criadoEm;

    protected ProdutoEntity() {
        // exigido pelo JPA
    }

    /** So o que nasce com a linha e nunca mais muda; o resto vem de {@link #atualizarCom}. */
    private ProdutoEntity(Produto produto) {
        this.id = produto.getId();
        this.tipo = produto.getTipo();
        this.estoqueAtual = produto.getEstoqueAtual();
        this.criadoEm = produto.getCriadoEm();
        atualizarCom(produto);
    }

    public static ProdutoEntity de(Produto produto) {
        return new ProdutoEntity(produto);
    }

    /**
     * Copia para esta linha o estado mutavel do dominio. Na edicao (RF04) e na inativacao (RF05) a
     * instancia e a <strong>gerenciada</strong>, carregada pelo repositorio.
     *
     * <p>Por que a edicao nao e {@code save(ProdutoEntity.de(produto))}: {@link #de} monta uma
     * instancia destacada <em>sem</em> {@code contaId}, e depender do que o {@code merge} do
     * Hibernate faz com uma coluna {@link TenantId} {@code updatable = false} e o tipo de
     * comportamento implicito que este projeto evita. Carregar a linha e muta-la e explicito.
     *
     * <p>Ficam de fora, cada um por um motivo: {@code id} e {@code criadoEm} sao identidade;
     * {@code contaId} nunca vem de fora (RNF05) — quem o preenche e o Hibernate; {@code tipo} e
     * imutavel apos o cadastro (D17a); {@code estoqueAtual} so se move por
     * {@code MovimentoEstoque} (R15).
     */
    public void atualizarCom(Produto produto) {
        this.nome = produto.getNome();
        this.codigo = produto.getCodigo();
        this.preco = produto.getPreco().valor();
        this.categoria = produto.getCategoria();
        this.unidade = produto.getUnidade();
        this.atributos = new LinkedHashMap<>(produto.getAtributos());
        this.ativo = produto.isAtivo();
    }

    public Produto paraDominio() {
        return Produto.reconstituir(id, criadoEm, nome, Money.de(preco), tipo, codigo, categoria,
                unidade, estoqueAtual, atributos, ativo);
    }

    public UUID getId() {
        return id;
    }

    /** Existe para o teste de isolamento poder afirmar de que conta a linha e. */
    public ContaId getContaId() {
        return ContaId.de(contaId);
    }

    public boolean isAtivo() {
        return ativo;
    }
}
