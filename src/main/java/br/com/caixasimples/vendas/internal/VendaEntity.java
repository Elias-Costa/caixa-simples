package br.com.caixasimples.vendas.internal;

import br.com.caixasimples.shared.ContaId;
import br.com.caixasimples.shared.Money;
import br.com.caixasimples.vendas.StatusVenda;
import br.com.caixasimples.vendas.domain.ItemVenda;
import br.com.caixasimples.vendas.domain.Pagamento;
import br.com.caixasimples.vendas.domain.Venda;
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
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.hibernate.annotations.Fetch;
import org.hibernate.annotations.FetchMode;
import org.hibernate.annotations.TenantId;

/**
 * Mapeamento JPA da tabela {@code venda} e tradutor de e para {@link Venda}.
 *
 * <p>A entidade e o domínio são classes separadas de propósito, e é isso que permite {@link Venda}
 * não conhecer {@code jakarta.persistence}. Este arquivo é a única ponte entre os dois.
 *
 * <p>{@code contaId} é preenchido pelo Hibernate a partir do
 * {@code CurrentTenantIdentifierResolver} e filtra toda consulta automaticamente
 * ({@link TenantId}). Não há construtor nem setter que o receba, porque {@code contaId} nunca vem
 * de fora da aplicação (RNF05).
 *
 * <p><strong>Não há {@code atualizarCom}</strong>, ao contrário de {@code SessaoCaixaEntity}, e a
 * ausência acompanha o domínio: {@link Venda} ainda não tem caminho de escrita, então não existe
 * estado alterado para copiar. O método nasce junto da primeira mutação, que é a montagem da
 * comanda.
 */
@Entity
@Table(name = "venda")
public class VendaEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "conta_id", nullable = false, updatable = false)
    private UUID contaId;

    /** A sessão de caixa em que a venda foi feita. Referência entre agregados, sempre por id. */
    @Column(name = "sessao_caixa_id", nullable = false, updatable = false)
    private UUID sessaoCaixaId;

    /** O operador que realizou a venda. */
    @Column(name = "usuario_id", nullable = false, updatable = false)
    private UUID usuarioId;

    /** Nulo quando a venda não tem cliente identificado (RF03). */
    @Column(name = "cliente_id")
    private UUID clienteId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StatusVenda status;

    @Column(name = "valor_total", nullable = false)
    private BigDecimal valorTotal;

    /** Zero quando não há desconto sobre a venda, nunca nulo. */
    @Column(name = "valor_desconto", nullable = false)
    private BigDecimal valorDesconto;

    @Column(name = "criado_em", nullable = false, updatable = false)
    private Instant criadoEm;

    /**
     * Os itens da venda, mapeados como membros do agregado e não como entidade independente.
     *
     * <p>{@code cascade} e {@code orphanRemoval} são o que faz o agregado ser gravado como uma
     * unidade só: salvar a raiz grava os itens junto, na mesma transação. A associação é
     * <strong>unidirecional</strong>, porque a única navegação que faz sentido no agregado é da
     * raiz para o membro.
     *
     * <p>É {@code EAGER} pelo mesmo motivo de {@code SessaoCaixaEntity}: membro de agregado se
     * carrega pela raiz, e uma venda sem os itens não responde à pergunta que ela existe para
     * responder. Com {@code LAZY}, quem chamasse {@code findById} fora de uma transação e tocasse
     * na lista levaria {@code LazyInitializationException}.
     *
     * <p><strong>{@link Fetch} com {@code SUBSELECT}, e vale ler o porquê.</strong> Esta entidade
     * tem duas listas {@code EAGER}, e o Hibernate não consegue trazer as duas por JOIN na mesma
     * consulta: ele recusa na subida da aplicação, com {@code MultipleBagFetchException}. E mesmo
     * que aceitasse, o JOIN duplo multiplicaria linhas, itens vezes pagamentos, para depois
     * desfazer a multiplicação em memória. Com {@code SUBSELECT} cada coleção vem numa consulta
     * própria, logo depois da raiz, o que é o comportamento que se esperaria ler no nome.
     *
     * <p>{@link OrderBy} por {@code criadoEm} para os itens saírem na ordem em que entraram na
     * comanda. Empate de instante continua sem ordem definida, e isso é aceitável.
     */
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @Fetch(FetchMode.SUBSELECT)
    @JoinColumn(name = "venda_id", nullable = false)
    @OrderBy("criadoEm")
    private List<ItemVendaEntity> itens = new ArrayList<>();

    /** Mesmo mapeamento dos itens, pelos mesmos motivos, inclusive o {@code SUBSELECT}. */
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @Fetch(FetchMode.SUBSELECT)
    @JoinColumn(name = "venda_id", nullable = false)
    @OrderBy("criadoEm")
    private List<PagamentoEntity> pagamentos = new ArrayList<>();

    protected VendaEntity() {
        // exigido pelo JPA
    }

    private VendaEntity(Venda venda) {
        this.id = venda.getId();
        this.sessaoCaixaId = venda.getSessaoCaixaId();
        this.usuarioId = venda.getUsuarioId();
        this.clienteId = venda.getClienteId();
        this.status = venda.getStatus();
        this.valorTotal = venda.getValorTotal().valor();
        this.valorDesconto = venda.getValorDesconto().valor();
        this.criadoEm = venda.getCriadoEm();
        this.itens = venda.getItens().stream()
                .map(ItemVendaEntity::de)
                .collect(Collectors.toCollection(ArrayList::new));
        this.pagamentos = venda.getPagamentos().stream()
                .map(PagamentoEntity::de)
                .collect(Collectors.toCollection(ArrayList::new));
    }

    public static VendaEntity de(Venda venda) {
        return new VendaEntity(venda);
    }

    public Venda paraDominio() {
        List<ItemVenda> itensDoDominio = itens.stream()
                .map(ItemVendaEntity::paraDominio)
                .toList();
        List<Pagamento> pagamentosDoDominio = pagamentos.stream()
                .map(PagamentoEntity::paraDominio)
                .toList();

        return Venda.reconstituir(id, sessaoCaixaId, usuarioId, clienteId, status,
                Money.de(valorTotal), Money.de(valorDesconto), criadoEm, itensDoDominio,
                pagamentosDoDominio);
    }

    public UUID getId() {
        return id;
    }

    /** Existe para o teste de isolamento poder afirmar de que conta a linha é. */
    public ContaId getContaId() {
        return ContaId.de(contaId);
    }

    /**
     * Visibilidade de pacote de propósito: quem está fora de {@code vendas.internal} nem consegue
     * nomear {@code ItemVendaEntity}, então o membro do agregado só se lê pelo domínio.
     */
    List<ItemVendaEntity> getItens() {
        return itens;
    }

    /** Mesma visibilidade de pacote de {@link #getItens()}, pelo mesmo motivo. */
    List<PagamentoEntity> getPagamentos() {
        return pagamentos;
    }
}
