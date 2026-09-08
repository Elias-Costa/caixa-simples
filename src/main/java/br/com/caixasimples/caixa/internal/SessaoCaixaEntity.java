package br.com.caixasimples.caixa.internal;

import br.com.caixasimples.caixa.StatusSessaoCaixa;
import br.com.caixasimples.caixa.domain.MovimentoCaixa;
import br.com.caixasimples.caixa.domain.SessaoCaixa;
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
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.hibernate.annotations.TenantId;

/**
 * Mapeamento JPA da tabela {@code sessao_caixa} e tradutor de e para {@link SessaoCaixa}.
 *
 * <p>A entidade e o dominio sao classes separadas de proposito (arquitetura §2, P7): e o que
 * permite {@link SessaoCaixa} nao conhecer {@code jakarta.persistence}. Este arquivo e a unica
 * ponte entre os dois.
 *
 * <p>{@code contaId} e preenchido pelo Hibernate a partir do
 * {@code CurrentTenantIdentifierResolver} e filtra toda consulta automaticamente ({@link TenantId}).
 * Nao ha construtor nem setter que o receba — {@code contaId} nunca vem de fora da aplicacao
 * (RNF05).
 *
 * <p><strong>O {@code atualizarCom} nasceu no R07</strong>, junto com o caso de uso que precisou
 * dele — sangria e suprimento sao as duas primeiras operacoes que alteram uma sessao ja gravada.
 * Ele so escreve o que o R07 muda; as colunas de fechamento continuam de fora, e entram no R08.
 */
@Entity
@Table(name = "sessao_caixa")
public class SessaoCaixaEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "conta_id", nullable = false, updatable = false)
    private UUID contaId;

    /** Quem abriu. Referencia entre agregados, sempre por id (modelo de dados §4). */
    @Column(name = "usuario_id", nullable = false, updatable = false)
    private UUID usuarioId;

    @Column(name = "valor_abertura", nullable = false, updatable = false)
    private BigDecimal valorAbertura;

    /**
     * D21a — coluna viva, nao calculo do fechamento: acompanha os movimentos na mesma transacao,
     * como {@code produto.estoque_atual} acompanha os movimentos de estoque. Por isso e
     * {@code NOT NULL} desde a abertura, e nao nula ate o fechamento como as tres abaixo.
     */
    @Column(name = "valor_fechamento_esperado", nullable = false)
    private BigDecimal valorFechamentoEsperado;

    /** Nulas ate o fechamento (R08), as tres juntas. */
    @Column(name = "valor_fechamento_contado")
    private BigDecimal valorFechamentoContado;

    @Column(name = "diferenca")
    private BigDecimal diferenca;

    @Column(name = "fechada_em")
    private Instant fechadaEm;

    @Column(name = "aberta_em", nullable = false, updatable = false)
    private Instant abertaEm;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StatusSessaoCaixa status;

    /**
     * Os movimentos da sessao, mapeados como membros do agregado e nao como entidade independente.
     *
     * <p>{@code cascade} e {@code orphanRemoval} sao o que faz o agregado ser gravado como uma
     * unidade so: salvar a raiz grava os movimentos junto, na mesma transacao. A associacao e
     * <strong>unidirecional</strong> — o movimento nao aponta de volta para a sessao, porque a unica
     * navegacao que faz sentido no agregado e da raiz para o membro.
     *
     * <p><strong>{@code EAGER}, e a escolha custa algo — vale ler o porque.</strong> A regra do
     * projeto e que membro de agregado se carrega pela raiz, com o agregado inteiro na transacao
     * ({@code .claude/rules/agregados-e-repositorios.md}), e uma sessao sem os movimentos nao
     * responde a pergunta que ela existe para responder. Com {@code LAZY} isso so seria verdade
     * dentro de uma transacao aberta: quem chamasse {@code findById} fora de uma e tocasse na lista
     * levaria {@code LazyInitializationException} — o tipo de comportamento implicito que este
     * projeto evita, e que ja apareceu de fato ao escrever o teste de ida e volta deste passo.
     *
     * <p>O preco e listar sessoes: {@code findAll} traz os movimentos de todas. Enquanto so existe
     * consulta por id, isso nao pesa. O historico por operador e por dia do <strong>R08</strong> e
     * o ponto em que a conta muda — e la a saida deve ser uma projecao com os totais, nao uma lista
     * de agregados inteiros.
     *
     * <p>{@link OrderBy} por {@code criadoEm} para a leitura sair na ordem em que o expediente
     * aconteceu — sem ele, a ordem seria a que o banco resolvesse devolver, e o extrato do caixa
     * (R08) sairia embaralhado. Empate de microssegundo continua sem ordem definida, e isso e
     * aceitavel: o extrato nao precisa desempatar dois lancamentos do mesmo instante.
     */
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "sessao_caixa_id", nullable = false)
    @OrderBy("criadoEm")
    private List<MovimentoCaixaEntity> movimentos = new ArrayList<>();

    protected SessaoCaixaEntity() {
        // exigido pelo JPA
    }

    private SessaoCaixaEntity(SessaoCaixa sessao) {
        this.id = sessao.getId();
        this.usuarioId = sessao.getUsuarioId();
        this.valorAbertura = sessao.getValorAbertura().valor();
        this.valorFechamentoEsperado = sessao.getValorFechamentoEsperado().valor();
        this.valorFechamentoContado = valorOuNulo(sessao.getValorFechamentoContado());
        this.diferenca = valorOuNulo(sessao.getDiferenca());
        this.abertaEm = sessao.getAbertaEm();
        this.fechadaEm = sessao.getFechadaEm();
        this.status = sessao.getStatus();
        this.movimentos = sessao.getMovimentos().stream()
                .map(MovimentoCaixaEntity::de)
                .collect(Collectors.toCollection(ArrayList::new));
    }

    public static SessaoCaixaEntity de(SessaoCaixa sessao) {
        return new SessaoCaixaEntity(sessao);
    }

    /**
     * Copia para a linha o que uma sangria ou um suprimento mudou no agregado: o esperado (D21a) e
     * os movimentos novos.
     *
     * <p><strong>Acrescenta em vez de substituir a colecao</strong>, e a diferenca importa: com
     * {@code orphanRemoval}, trocar a lista inteira apagaria e reinseriria o historico do
     * expediente a cada lancamento. Entao a comparacao e por id — o que ja esta gravado fica onde
     * esta, e so o que o dominio criou agora vira linha nova.
     *
     * <p>Nao escreve {@code status}, {@code valorFechamentoContado}, {@code diferenca} nem
     * {@code fechadaEm}: no R07 nada muda essas quatro. Quem as escreve e o fechamento, no R08, e e
     * la que este metodo cresce.
     */
    public void atualizarCom(SessaoCaixa sessao) {
        this.valorFechamentoEsperado = sessao.getValorFechamentoEsperado().valor();

        Set<UUID> jaGravados = movimentos.stream()
                .map(MovimentoCaixaEntity::getId)
                .collect(Collectors.toSet());

        sessao.getMovimentos().stream()
                .filter(movimento -> !jaGravados.contains(movimento.id()))
                .map(MovimentoCaixaEntity::de)
                .forEach(movimentos::add);
    }

    public SessaoCaixa paraDominio() {
        List<MovimentoCaixa> movimentosDoDominio = movimentos.stream()
                .map(MovimentoCaixaEntity::paraDominio)
                .toList();

        return SessaoCaixa.reconstituir(id, usuarioId, Money.de(valorAbertura),
                Money.de(valorFechamentoEsperado), moneyOuNulo(valorFechamentoContado),
                moneyOuNulo(diferenca), abertaEm, fechadaEm, status, movimentosDoDominio);
    }

    public UUID getId() {
        return id;
    }

    /** Existe para o teste de isolamento poder afirmar de que conta a linha e. */
    public ContaId getContaId() {
        return ContaId.de(contaId);
    }

    /**
     * Visibilidade de pacote de proposito: quem esta fora de {@code caixa.internal} nem consegue
     * nomear {@code MovimentoCaixaEntity}, entao o membro do agregado so se le pelo dominio.
     */
    List<MovimentoCaixaEntity> getMovimentos() {
        return movimentos;
    }

    private static BigDecimal valorOuNulo(Money money) {
        return money == null ? null : money.valor();
    }

    private static Money moneyOuNulo(BigDecimal valor) {
        return valor == null ? null : Money.de(valor);
    }
}
