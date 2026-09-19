package br.com.caixasimples.cadastro.internal;

import br.com.caixasimples.cadastro.TipoProduto;
import br.com.caixasimples.cadastro.domain.MovimentoEstoque;
import br.com.caixasimples.cadastro.domain.Produto;
import br.com.caixasimples.shared.ContaId;
import br.com.caixasimples.shared.Money;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

    /**
     * Os movimentos de estoque do produto, mapeados como membros do agregado e não como entidade
     * independente. {@code cascade} é o que faz o agregado ser gravado como uma unidade só: salvar
     * a raiz grava o movimento novo junto, na mesma transação do saldo. A associação é
     * unidirecional, porque a única navegação que faz sentido no agregado é da raiz para o membro.
     *
     * <p><strong>É {@code LAZY}, ao contrário do caixa, e vale ler o porquê.</strong> O extrato de
     * uma sessão de caixa é um expediente; o histórico de um produto cresce a cada venda, sem
     * limite, e o produto é lido em toda venda, na listagem e na busca do balcão. Com {@code EAGER}
     * cada uma dessas leituras arrastaria o histórico inteiro. Por isso {@link #paraDominio} nunca
     * toca esta lista, e o domínio não conhece o histórico: guarda só o saldo consolidado.
     *
     * <p><strong>Custo aceito:</strong> acrescentar a uma coleção {@code LAZY} faz o Hibernate
     * carregá-la primeiro, então a baixa lê o histórico do produto, dentro da transação de
     * escrita e só nela. É um custo por venda, no caminho que já vai ao banco, e não um custo por
     * leitura.
     *
     * <p>{@link OrderBy} por {@code criadoEm} para o histórico sair na ordem em que aconteceu,
     * quando alguém o ler.
     */
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @JoinColumn(name = "produto_id", nullable = false)
    @OrderBy("criadoEm")
    private List<MovimentoEstoqueEntity> movimentos = new ArrayList<>();

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
     * é imutável depois do cadastro; e {@code estoqueAtual} só se move junto de um movimento de
     * estoque, por {@link #registrarMovimento}.
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

    /**
     * Grava um movimento de estoque <strong>e</strong> o saldo que a raiz calculou com ele, na
     * mesma linha e portanto na mesma transação. É o único método desta classe que escreve
     * {@code estoqueAtual}, e ele exige o movimento: não existe caminho que mova o saldo sem
     * deixar o registro, nem que registre sem mover o saldo. É assim que a invariante do agregado,
     * saldo igual à soma dos movimentos, é garantida sem nunca somar o histórico.
     *
     * <p>A instância é a <strong>gerenciada</strong>, carregada pelo repositório, pelo mesmo motivo
     * de {@link #atualizarCom}. O movimento é sempre acrescentado, nunca comparado por id como o
     * caixa faz: a lista não é remontada do domínio, então não há o que reconciliar.
     */
    public void registrarMovimento(Produto produto, MovimentoEstoque movimento) {
        Objects.requireNonNull(movimento, "movimento nao pode ser nulo");
        this.estoqueAtual = produto.getEstoqueAtual();
        this.movimentos.add(MovimentoEstoqueEntity.de(movimento));
    }

    /**
     * Remonta a raiz <strong>sem o histórico</strong>: o domínio conhece só o saldo. Ver o
     * comentário de {@link #movimentos}.
     */
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

    /**
     * Visibilidade de pacote de propósito: quem está fora de {@code cadastro.internal} nem consegue
     * nomear {@code MovimentoEstoqueEntity}, então o histórico só se lê daqui, e hoje só o teste
     * do membro o lê. Como a coleção é {@code LAZY}, tocar nela exige transação aberta.
     */
    List<MovimentoEstoqueEntity> getMovimentos() {
        return movimentos;
    }
}
