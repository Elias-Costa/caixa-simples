package br.com.caixasimples.caixa.domain;

import br.com.caixasimples.caixa.StatusSessaoCaixa;
import br.com.caixasimples.caixa.TipoMovimentoCaixa;
import br.com.caixasimples.shared.Money;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * O caixa de um expediente: a sessao operacional entre abertura e fechamento. Raiz do agregado
 * Caixa (modelo de dados §4), com {@link MovimentoCaixa} como membro.
 *
 * <p>Cuidado com o nome, que e vocabulario do CLAUDE.md: <em>caixa</em> aqui e a sessao, nao o
 * dinheiro em especie nem o sistema.
 *
 * <p><strong>A invariante que esta classe existe para guardar:</strong>
 * {@code valorFechamentoEsperado = valorAbertura + soma assinada dos movimentos}. Ela vale
 * <em>o tempo todo</em>, nao so no fechamento (D21a): o campo nasce igual a abertura e e reescrito
 * em {@link #registrar}, do mesmo jeito que {@code Produto.estoqueAtual} acompanha os movimentos de
 * estoque. E por isso que perguntar quanto deveria ter na gaveta agora e ler um campo, e nao somar
 * o historico.
 *
 * <p><strong>Nao importa framework</strong> — nem {@code jakarta.persistence}, nem
 * {@code org.springframework} (arquitetura §2). O mapeamento vive em
 * {@code caixa.internal.SessaoCaixaEntity}.
 *
 * <p><strong>Nao carrega {@code contaId}</strong>, pelo mesmo motivo de
 * {@code cadastro.domain.Produto}: sem campo, nao existe assinatura por onde um chamador pudesse
 * informar a conta — que e o que RNF05 proibe. O tenant e preenchido pelo Hibernate na entidade.
 *
 * <p><strong>O que esta classe ainda nao faz, e de proposito:</strong> nao exige motivo em sangria
 * nem em suprimento (RF14 e R07), nao tem {@code fechar()} nem calcula {@code diferenca} (RF15 e
 * R08), e nao recusa movimento em sessao FECHADA. Este passo (R06) entrega schema e persistencia; o
 * {@link #registrar} e cru de proposito, e quem o embrulha nos casos de uso nomeados e o R07.
 */
public class SessaoCaixa {

    private final UUID id;
    private final UUID usuarioId;
    private final Money valorAbertura;
    private final Instant abertaEm;

    /** Invariante viva (D21a) — ver o javadoc da classe. Nunca escrito de fora. */
    private Money valorFechamentoEsperado;

    /** Nulos ate o fechamento (R08), os tres juntos. */
    private Money valorFechamentoContado;
    private Money diferenca;
    private Instant fechadaEm;

    private StatusSessaoCaixa status;

    private final List<MovimentoCaixa> movimentos;

    /**
     * Abre uma sessao (RF13): nasce ABERTA, sem movimento nenhum, e ja com o esperado igual ao que
     * foi posto na gaveta.
     *
     * <p>Nao valida o valor de abertura alem de exigi-lo: se abrir com zero vale, ou se abrir com
     * valor negativo e erro, e regra de abertura — e regra de abertura e R07. Este construtor nao
     * inventa nenhuma das duas.
     *
     * @param usuarioId     quem abriu; referencia entre agregados, sempre por id
     * @param valorAbertura o que existe na gaveta no comeco do expediente
     */
    public SessaoCaixa(UUID usuarioId, Money valorAbertura) {
        this.id = UUID.randomUUID();
        this.usuarioId = Objects.requireNonNull(usuarioId, "usuarioId nao pode ser nulo");
        this.valorAbertura = Objects.requireNonNull(valorAbertura, "valorAbertura nao pode ser nulo");
        this.abertaEm = Instant.now();
        this.valorFechamentoEsperado = valorAbertura;
        this.status = StatusSessaoCaixa.ABERTA;
        this.movimentos = new ArrayList<>();
    }

    private SessaoCaixa(UUID id, UUID usuarioId, Money valorAbertura, Money valorFechamentoEsperado,
            Money valorFechamentoContado, Money diferenca, Instant abertaEm, Instant fechadaEm,
            StatusSessaoCaixa status, List<MovimentoCaixa> movimentos) {
        this.id = id;
        this.usuarioId = usuarioId;
        this.valorAbertura = valorAbertura;
        this.valorFechamentoEsperado = valorFechamentoEsperado;
        this.valorFechamentoContado = valorFechamentoContado;
        this.diferenca = diferenca;
        this.abertaEm = abertaEm;
        this.fechadaEm = fechadaEm;
        this.status = status;
        this.movimentos = new ArrayList<>(movimentos);
    }

    /**
     * Remonta uma sessao que ja existe no banco, preservando identidade e estado.
     *
     * <p>Existe so para {@code SessaoCaixaEntity} — nao e caminho de abertura. Pelo mesmo motivo de
     * {@code Produto.reconstituir}, nao recalcula o esperado a partir dos movimentos: o que esta
     * gravado ja passou por {@link #registrar} e pelos CHECK da migration, e recalcular aqui
     * mascararia uma linha divergente em vez de deixar o defeito aparecer.
     */
    public static SessaoCaixa reconstituir(UUID id, UUID usuarioId, Money valorAbertura,
            Money valorFechamentoEsperado, Money valorFechamentoContado, Money diferenca,
            Instant abertaEm, Instant fechadaEm, StatusSessaoCaixa status,
            List<MovimentoCaixa> movimentos) {
        return new SessaoCaixa(id, usuarioId, valorAbertura, valorFechamentoEsperado,
                valorFechamentoContado, diferenca, abertaEm, fechadaEm, status, movimentos);
    }

    /**
     * Lanca um movimento na sessao e mantem o esperado em dia, na mesma chamada — que e o que faz a
     * invariante da classe ser verdade o tempo todo, e nao so quando alguem lembra de recalcular.
     *
     * <p><strong>E o metodo cru, e continua cru ate o R07.</strong> Nao exige motivo em SANGRIA nem
     * em SUPRIMENTO (RF14), nao confere se a sessao ainda esta ABERTA e nao impede sangria maior que
     * o saldo. Os casos de uso nomeados — abrir, sangria e suprimento — sao a proxima etapa, e e la
     * que cada uma dessas regras entra, com o teste que a prova.
     *
     * @param tipo    quem decide se o dinheiro entra ou sai
     * @param valor   sempre positivo (D21b); o sinal e do tipo
     * @param motivo  RF14; nulo ou em branco vira ausencia
     * @param vendaId so em VENDA; nulo nos outros dois
     */
    public void registrar(TipoMovimentoCaixa tipo, Money valor, String motivo, UUID vendaId) {
        MovimentoCaixa movimento = MovimentoCaixa.novo(tipo, valor, motivo, vendaId);
        movimentos.add(movimento);

        // O switch e exaustivo de proposito: acrescentar um valor em TipoMovimentoCaixa quebra a
        // compilacao aqui, que e exatamente onde alguem precisa decidir se o dinheiro entra ou sai.
        // Um campo de sinal dentro do enum faria a mesma conta em silencio, com o valor errado.
        this.valorFechamentoEsperado = switch (tipo) {
            case VENDA, SUPRIMENTO -> valorFechamentoEsperado.somar(movimento.valor());
            case SANGRIA -> valorFechamentoEsperado.subtrair(movimento.valor());
        };
    }

    public UUID getId() {
        return id;
    }

    public UUID getUsuarioId() {
        return usuarioId;
    }

    public Money getValorAbertura() {
        return valorAbertura;
    }

    public Money getValorFechamentoEsperado() {
        return valorFechamentoEsperado;
    }

    /** Nulo enquanto a sessao esta ABERTA — so o fechamento (R08) o preenche. */
    public Money getValorFechamentoContado() {
        return valorFechamentoContado;
    }

    /** Nulo enquanto a sessao esta ABERTA — so o fechamento (R08) o preenche. */
    public Money getDiferenca() {
        return diferenca;
    }

    public Instant getAbertaEm() {
        return abertaEm;
    }

    /** Nulo enquanto a sessao esta ABERTA — so o fechamento (R08) o preenche. */
    public Instant getFechadaEm() {
        return fechadaEm;
    }

    public StatusSessaoCaixa getStatus() {
        return status;
    }

    /** Copia imutavel: movimento so entra pela raiz, nunca por quem leu a lista. */
    public List<MovimentoCaixa> getMovimentos() {
        return Collections.unmodifiableList(movimentos);
    }
}
