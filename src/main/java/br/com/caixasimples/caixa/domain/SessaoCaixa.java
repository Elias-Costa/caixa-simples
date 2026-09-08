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
 * a cada lancamento, do mesmo jeito que {@code Produto.estoqueAtual} acompanha os movimentos de
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
 * <h2>As regras que o R07 trouxe (D22)</h2>
 *
 * <p>O R06 entregou um {@code registrar} publico e cru, de proposito (D21c). Este passo o fechou:
 * ele agora e <strong>privado</strong>, e quem lanca movimento passa por {@link #sangrar},
 * {@link #suprir} ou {@link #registrarVenda}. De fora ninguem escolhe um tipo arbitrario nem omite
 * o motivo.
 *
 * <ul>
 *   <li><strong>D22b</strong> — abertura com valor negativo e recusada; com zero, nao. Abrir a
 *       gaveta sem troco e situacao real: primeiro dia, ou caixa que so recebe por Pix.</li>
 *   <li><strong>D22c</strong> — sangria que deixaria o esperado negativo e recusada: nao se tira
 *       da gaveta o que nao esta la.</li>
 *   <li><strong>D22d</strong> — sessao FECHADA nao aceita movimento. E a linguagem ubiqua escrita
 *       em codigo — caixa e a sessao <em>entre</em> abertura e fechamento — e protege o
 *       {@link #fechar}: um lancamento posterior tornaria a {@code diferenca} ja gravada
 *       mentirosa.</li>
 * </ul>
 *
 * <p><strong>Movimento de valor zero continua aceito</strong>, e a ausencia da regra e deliberada:
 * foi ao mantenedor junto com as quatro acima e nao foi escolhida (D22). O piso continua sendo o
 * CHECK de valor nao negativo da D21b.
 *
 * <h2>O fechamento, que o R08 trouxe (D23)</h2>
 *
 * <p>{@link #fechar} e o outro extremo da sessao: compara o que deveria haver na gaveta com o que o
 * operador contou e grava a {@code diferenca} (RF15). Duas guardas novas, do mesmo tipo das quatro
 * acima:
 *
 * <ul>
 *   <li><strong>D23d</strong> — valor contado negativo e recusado, pela mesma razao da D22b: contar
 *       zero e legitimo, contar menos que zero e erro de digitacao que grava uma diferenca sem
 *       sentido.</li>
 *   <li><strong>D23e</strong> — sessao FECHADA nao fecha de novo. E o complemento da D22d: aquela
 *       protege a {@code diferenca} de um movimento posterior, esta a protege de um segundo
 *       fechamento, que a reescreveria por cima da conferencia ja feita.</li>
 * </ul>
 *
 * <p><strong>A regra de <em>uma sessao aberta por operador</em> (D22a) nao mora aqui</strong>, e nem
 * poderia: uma sessao nao enxerga as outras. Quem a aplica e
 * {@code caixa.application.SessaoCaixaService}, com o indice unico parcial da V6 embaixo.
 */
public class SessaoCaixa {

    private final UUID id;
    private final UUID usuarioId;
    private final Money valorAbertura;
    private final Instant abertaEm;

    /** Invariante viva (D21a) — ver o javadoc da classe. Nunca escrito de fora. */
    private Money valorFechamentoEsperado;

    /** Nulos ate o fechamento, os tres juntos — quem os preenche e {@link #fechar}. */
    private Money valorFechamentoContado;
    private Money diferenca;
    private Instant fechadaEm;

    private StatusSessaoCaixa status;

    private final List<MovimentoCaixa> movimentos;

    /**
     * Abre uma sessao (RF13): nasce ABERTA, sem movimento nenhum, e ja com o esperado igual ao que
     * foi posto na gaveta.
     *
     * @param usuarioId     quem abriu; referencia entre agregados, sempre por id
     * @param valorAbertura o que existe na gaveta no comeco do expediente; zero vale, negativo nao
     *                      (D22b)
     * @throws IllegalArgumentException se {@code valorAbertura} e negativo
     */
    public SessaoCaixa(UUID usuarioId, Money valorAbertura) {
        Objects.requireNonNull(valorAbertura, "valorAbertura nao pode ser nulo");
        if (valorAbertura.isNegativo()) {
            // D22b — abertura negativa envenenaria o esperado desde a primeira linha, e o erro so
            // apareceria na conferencia do fechamento, um expediente inteiro depois.
            throw new IllegalArgumentException(
                    "valor de abertura nao pode ser negativo: " + valorAbertura
                            + ". Abrir sem troco e zero, nunca um valor negativo.");
        }

        this.id = UUID.randomUUID();
        this.usuarioId = Objects.requireNonNull(usuarioId, "usuarioId nao pode ser nulo");
        this.valorAbertura = valorAbertura;
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
     * <p>Existe so para {@code SessaoCaixaEntity} — nao e caminho de abertura, e por isso nao passa
     * pela guarda da D22b. Pelo mesmo motivo de {@code Produto.reconstituir}, nao recalcula o
     * esperado a partir dos movimentos: o que esta gravado ja passou pelos metodos de lancamento e
     * pelos CHECK da migration, e recalcular aqui mascararia uma linha divergente em vez de deixar
     * o defeito aparecer.
     */
    public static SessaoCaixa reconstituir(UUID id, UUID usuarioId, Money valorAbertura,
            Money valorFechamentoEsperado, Money valorFechamentoContado, Money diferenca,
            Instant abertaEm, Instant fechadaEm, StatusSessaoCaixa status,
            List<MovimentoCaixa> movimentos) {
        return new SessaoCaixa(id, usuarioId, valorAbertura, valorFechamentoEsperado,
                valorFechamentoContado, diferenca, abertaEm, fechadaEm, status, movimentos);
    }

    /**
     * Sangria (RF14): retirada de dinheiro do caixa — nao e estorno nem despesa.
     *
     * @param valor  sempre positivo (D21b); quem carrega o sinal e o tipo
     * @param motivo obrigatorio (RF14) — quem retira dinheiro justifica a retirada
     * @throws IllegalArgumentException se o motivo esta ausente ou em branco, ou se a retirada
     *                                  deixaria o esperado negativo (D22c)
     * @throws IllegalStateException    se a sessao ja esta FECHADA (D22d)
     */
    public void sangrar(Money valor, String motivo) {
        exigirMotivo(motivo, TipoMovimentoCaixa.SANGRIA);
        Objects.requireNonNull(valor, "valor da sangria nao pode ser nulo");

        // D22c — a conta e feita antes de anexar o movimento, para uma sangria recusada nao deixar
        // rastro nenhum no agregado. Retirar exatamente o que ha vale: zera a gaveta.
        if (valorFechamentoEsperado.subtrair(valor).isNegativo()) {
            throw new IllegalArgumentException(
                    "sangria de " + valor + " e maior que os " + valorFechamentoEsperado
                            + " que deveriam estar na gaveta. Nao se retira o que nao esta la.");
        }

        registrar(TipoMovimentoCaixa.SANGRIA, valor, motivo, null);
    }

    /**
     * Suprimento (RF14): reforco de troco — nao e venda nem receita.
     *
     * @param valor  sempre positivo (D21b)
     * @param motivo obrigatorio (RF14)
     * @throws IllegalArgumentException se o motivo esta ausente ou em branco
     * @throws IllegalStateException    se a sessao ja esta FECHADA (D22d)
     */
    public void suprir(Money valor, String motivo) {
        exigirMotivo(motivo, TipoMovimentoCaixa.SUPRIMENTO);
        registrar(TipoMovimentoCaixa.SUPRIMENTO, valor, motivo, null);
    }

    /**
     * O dinheiro que entra por uma venda.
     *
     * <p>Sem motivo, e a assinatura diz isso sem precisar de comentario no ponto de chamada: o RF14
     * exige justificativa de sangria e de suprimento, nao de venda — a venda ja se explica pela
     * {@code vendaId}.
     *
     * <p><strong>Ninguem chama este metodo ainda.</strong> Ele existe porque o movimento de VENDA
     * existe desde o R06 e precisava de um nome para o {@link #registrar} poder ficar privado. Quem
     * o chama de verdade e o R12, quando a conclusao da venda passar a lancar no caixa.
     *
     * @param vendaId a venda que trouxe o dinheiro; referencia entre agregados, sempre por id
     * @throws IllegalStateException se a sessao ja esta FECHADA (D22d)
     */
    public void registrarVenda(Money valor, UUID vendaId) {
        registrar(TipoMovimentoCaixa.VENDA, valor, null, vendaId);
    }

    /**
     * Fechamento com conferencia (RF15): o operador conta o dinheiro da gaveta, o agregado compara
     * com o que deveria estar la e grava a diferenca.
     *
     * <p><strong>O sinal da diferenca e {@code esperado - contado}</strong>, como manda o dicionario
     * de dados §3 — entao <em>positivo e falta</em> na gaveta e <em>negativo e sobra</em>. Vale
     * dizer em voz alta porque a intuicao costuma ler ao contrario: uma diferenca de 2,00 nao e
     * dinheiro a mais, e dinheiro que faltou.
     *
     * <p>As quatro colunas do fechamento nascem juntas, nesta chamada e so nesta, que e exatamente o
     * que o dicionario diz delas.
     *
     * @param valorContado o que foi contado na gaveta; zero vale, negativo nao (D23d)
     * @return a diferenca apurada, que e a pergunta que o operador esta fazendo ao fechar o caixa
     * @throws IllegalArgumentException se {@code valorContado} e negativo (D23d)
     * @throws IllegalStateException    se a sessao ja esta FECHADA (D23e)
     */
    public Money fechar(Money valorContado) {
        Objects.requireNonNull(valorContado, "valorContado nao pode ser nulo");

        // D23e — a checagem de estado vem antes da do valor de proposito: sessao ja fechada e
        // recusada independentemente do que se tenha contado, e a mensagem que interessa e essa.
        if (status != StatusSessaoCaixa.ABERTA) {
            throw new IllegalStateException(
                    "sessao de caixa " + id + " ja esta " + status + " e nao fecha de novo."
                            + " Um segundo fechamento reescreveria a diferenca ja conferida.");
        }

        if (valorContado.isNegativo()) {
            // D23d — mesmo motivo da D22b na abertura: gaveta vazia se conta como zero, e um valor
            // negativo so poderia ser erro de digitacao.
            throw new IllegalArgumentException(
                    "valor contado nao pode ser negativo: " + valorContado
                            + ". Gaveta vazia se conta como zero.");
        }

        this.valorFechamentoContado = valorContado;
        this.diferenca = valorFechamentoEsperado.subtrair(valorContado);
        this.fechadaEm = Instant.now();
        this.status = StatusSessaoCaixa.FECHADA;

        return diferenca;
    }

    /**
     * Anexa o movimento e mantem o esperado em dia, na mesma chamada — que e o que faz a invariante
     * da classe ser verdade o tempo todo, e nao so quando alguem lembra de recalcular.
     *
     * <p><strong>Privado desde o R07</strong>, e a mudanca e o ponto do passo: enquanto era publico
     * (D21c), era uma porta lateral por onde se lancava sangria sem motivo. Quem chega aqui agora
     * ja passou por {@link #sangrar}, {@link #suprir} ou {@link #registrarVenda}.
     */
    private void registrar(TipoMovimentoCaixa tipo, Money valor, String motivo, UUID vendaId) {
        if (status != StatusSessaoCaixa.ABERTA) {
            // D22d — depois do fechamento a diferenca ja esta gravada; um movimento novo a tornaria
            // mentirosa sem que nada a recalculasse.
            throw new IllegalStateException(
                    "sessao de caixa " + id + " esta " + status + " e nao aceita movimento."
                            + " Um lancamento posterior ao fechamento invalidaria a diferenca ja"
                            + " conferida.");
        }

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

    /**
     * RF14 — a regra vive aqui, e nao em {@link MovimentoCaixa}, porque o membro do agregado aceita
     * os tres tipos e so dois deles exigem motivo. O CHECK da V5 diz o mesmo no banco.
     */
    private static void exigirMotivo(String motivo, TipoMovimentoCaixa tipo) {
        if (motivo == null || motivo.isBlank()) {
            throw new IllegalArgumentException("motivo e obrigatorio em " + tipo + " (RF14)");
        }
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

    /** Nulo enquanto a sessao esta ABERTA — so {@link #fechar} o preenche. */
    public Money getValorFechamentoContado() {
        return valorFechamentoContado;
    }

    /** Nulo enquanto a sessao esta ABERTA. Positivo e falta na gaveta, negativo e sobra. */
    public Money getDiferenca() {
        return diferenca;
    }

    public Instant getAbertaEm() {
        return abertaEm;
    }

    /** Nulo enquanto a sessao esta ABERTA — so {@link #fechar} o preenche. */
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
