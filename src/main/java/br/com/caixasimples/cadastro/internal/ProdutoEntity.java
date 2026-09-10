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
 * <p>A entidade e o domínio são classes separadas de propósito, e é isso que permite
 * {@link Produto} não conhecer {@code jakarta.persistence}. Este arquivo é a única ponte entre os
 * dois.
 *
 * <p>{@code contaId} é preenchido pelo Hibernate a partir do
 * {@code CurrentTenantIdentifierResolver} e filtra toda consulta automaticamente
 * ({@link TenantId}). Não há construtor nem setter que o receba, porque {@code contaId} nunca vem
 * de fora da aplicação (RNF05).
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

    /** Anulável, e único por conta apenas entre os ativos, pelo índice parcial da migration. */
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
     * Atributos que variam por tipo de negócio (RF02, RNF12), em {@code jsonb} com índice GIN.
     * Colunas fixas para o que é universal, JSONB para o que varia, e nunca EAV.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> atributos;

    /** Soft delete (RF05): preserva o histórico de vendas do item descontinuado. */
    @Column(nullable = false)
    private boolean ativo;

    @Column(name = "criado_em", nullable = false, updatable = false)
    private Instant criadoEm;

    protected ProdutoEntity() {
        // exigido pelo JPA
    }

    /** Só o que nasce com a linha e nunca mais muda; o resto vem de {@link #atualizarCom}. */
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
     * Copia para esta linha o estado mutável do domínio. Na edição (RF04) e na inativação (RF05), a
     * instância é a <strong>gerenciada</strong>, carregada pelo repositório.
     *
     * <p>Por que a edição não é {@code save(ProdutoEntity.de(produto))}: {@link #de} monta uma
     * instância destacada <em>sem</em> {@code contaId}, e depender do que o {@code merge} do
     * Hibernate faz com uma coluna {@link TenantId} marcada {@code updatable = false} é o tipo de
     * comportamento implícito que este projeto evita. Carregar a linha e mutá-la é explícito.
     *
     * <p>Ficam de fora, cada um por um motivo: {@code id} e {@code criadoEm} são identidade;
     * {@code contaId} nunca vem de fora (RNF05), porque quem o preenche é o Hibernate; {@code tipo}
     * é imutável depois do cadastro; e {@code estoqueAtual} só se move por movimento de estoque.
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

    /** Existe para o teste de isolamento poder afirmar de que conta a linha é. */
    public ContaId getContaId() {
        return ContaId.de(contaId);
    }

    public boolean isAtivo() {
        return ativo;
    }
}
