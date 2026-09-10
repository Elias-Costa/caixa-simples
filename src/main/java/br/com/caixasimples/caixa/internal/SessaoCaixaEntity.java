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
 * <p>A entidade e o domínio são classes separadas de propósito, e é isso que permite
 * {@link SessaoCaixa} não conhecer {@code jakarta.persistence}. Este arquivo é a única ponte entre
 * os dois.
 *
 * <p>{@code contaId} é preenchido pelo Hibernate a partir do
 * {@code CurrentTenantIdentifierResolver} e filtra toda consulta automaticamente
 * ({@link TenantId}). Não há construtor nem setter que o receba, porque {@code contaId} nunca vem
 * de fora da aplicação (RNF05).
 */
@Entity
@Table(name = "sessao_caixa")
public class SessaoCaixaEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "conta_id", nullable = false, updatable = false)
    private UUID contaId;

    /** Quem abriu. Referência entre agregados, sempre por id. */
    @Column(name = "usuario_id", nullable = false, updatable = false)
    private UUID usuarioId;

    @Column(name = "valor_abertura", nullable = false, updatable = false)
    private BigDecimal valorAbertura;

    /**
     * Coluna viva, não cálculo do fechamento: acompanha os movimentos na mesma transação, como
     * {@code produto.estoque_atual} acompanha os movimentos de estoque. Por isso é
     * {@code NOT NULL} desde a abertura, e não nula até o fechamento como as três abaixo.
     */
    @Column(name = "valor_fechamento_esperado", nullable = false)
    private BigDecimal valorFechamentoEsperado;

    /** Nulas até o fechamento, as três juntas. */
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
     * Os movimentos da sessão, mapeados como membros do agregado e não como entidade independente.
     *
     * <p>{@code cascade} e {@code orphanRemoval} são o que faz o agregado ser gravado como uma
     * unidade só: salvar a raiz grava os movimentos junto, na mesma transação. A associação é
     * <strong>unidirecional</strong>, porque a única navegação que faz sentido no agregado é da
     * raiz para o membro.
     *
     * <p><strong>É {@code EAGER}, e a escolha custa algo. Vale ler o porquê.</strong> A regra do
     * projeto é que membro de agregado se carrega pela raiz, com o agregado inteiro na transação, e
     * uma sessão sem os movimentos não responde à pergunta que ela existe para responder. Com
     * {@code LAZY} isso só seria verdade dentro de uma transação aberta: quem chamasse
     * {@code findById} fora de uma e tocasse na lista levaria {@code LazyInitializationException},
     * que é o tipo de comportamento implícito que este projeto evita, e que chegou a aparecer ao
     * escrever o teste de ida e volta do mapeamento.
     *
     * <p>O preço é listar sessões, porque {@code findAll} traria os movimentos de todas. É por isso
     * que o histórico por operador e por dia não usa esta entidade, e sim uma projeção com os
     * totais, em {@link LinhaDoHistorico}.
     *
     * <p>{@link OrderBy} por {@code criadoEm} para a leitura sair na ordem em que o expediente
     * aconteceu. Sem ele, a ordem seria a que o banco resolvesse devolver, e o extrato do caixa
     * sairia embaralhado. Empate de microssegundo continua sem ordem definida, e isso é aceitável:
     * o extrato não precisa desempatar dois lançamentos do mesmo instante.
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
     * Copia para a linha tudo o que o agregado pode ter mudado: o esperado, os movimentos novos e
     * as quatro colunas do fechamento.
     *
     * <p><strong>Acrescenta em vez de substituir a coleção</strong>, e a diferença importa: com
     * {@code orphanRemoval}, trocar a lista inteira apagaria e reinseriria o histórico do
     * expediente a cada lançamento. Por isso a comparação é por id, de modo que o que já está
     * gravado fica onde está e só o que o domínio criou agora vira linha nova.
     *
     * <p><strong>É um método só, e não um por caso de uso.</strong> A linha copia o estado do
     * domínio, que é a fonte da verdade; para uma sessão ABERTA, copiar as quatro colunas do
     * fechamento é escrever nulo por cima de nulo e ABERTA por cima de ABERTA. Um segundo método
     * exigiria que quem chama soubesse escolher entre os dois, e escolher errado gravaria pela
     * metade, que é um defeito bem mais caro de achar do que quatro atribuições sem efeito.
     */
    public void atualizarCom(SessaoCaixa sessao) {
        this.valorFechamentoEsperado = sessao.getValorFechamentoEsperado().valor();
        this.valorFechamentoContado = valorOuNulo(sessao.getValorFechamentoContado());
        this.diferenca = valorOuNulo(sessao.getDiferenca());
        this.fechadaEm = sessao.getFechadaEm();
        this.status = sessao.getStatus();

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

    /** Existe para o teste de isolamento poder afirmar de que conta a linha é. */
    public ContaId getContaId() {
        return ContaId.de(contaId);
    }

    /**
     * Visibilidade de pacote de propósito: quem está fora de {@code caixa.internal} nem consegue
     * nomear {@code MovimentoCaixaEntity}, então o membro do agregado só se lê pelo domínio.
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
