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
 * O caixa de um expediente: a sessão operacional entre abertura e fechamento. Raiz do agregado
 * Caixa, com {@link MovimentoCaixa} como membro.
 *
 * <p>Cuidado com o nome, que é vocabulário do domínio: <em>caixa</em> aqui é a sessão, não o
 * dinheiro em espécie nem o sistema.
 *
 * <p><strong>A invariante que esta classe existe para guardar:</strong>
 * {@code valorFechamentoEsperado = valorAbertura + soma assinada dos movimentos}. Ela vale
 * <em>o tempo todo</em>, não apenas no fechamento: o campo nasce igual à abertura e é reescrito a
 * cada lançamento, do mesmo jeito que {@code Produto.estoqueAtual} acompanha os movimentos de
 * estoque. É por isso que perguntar quanto deveria haver na gaveta agora é ler um campo, e não
 * somar o histórico inteiro.
 *
 * <p><strong>Não importa framework</strong>, nem {@code jakarta.persistence} nem
 * {@code org.springframework}. O mapeamento vive em {@code caixa.internal.SessaoCaixaEntity}.
 *
 * <p><strong>Não carrega {@code contaId}</strong>, pelo mesmo motivo do agregado Produto: sem o
 * campo, não existe assinatura por onde um chamador pudesse informar a conta, que é exatamente o
 * que o isolamento entre contas proíbe (RNF05). O tenant é preenchido pelo Hibernate na entidade.
 *
 * <h2>As regras de lançamento</h2>
 *
 * <p>Não existe método público e genérico de lançar movimento: quem lança passa por
 * {@link #sangrar}, {@link #suprir} ou {@link #registrarVenda}. De fora ninguém escolhe um tipo
 * arbitrário nem omite o motivo.
 *
 * <ul>
 *   <li>Abertura com valor negativo é recusada; com zero, não. Abrir a gaveta sem troco é situação
 *       real: primeiro dia de operação, ou caixa que só recebe por Pix.</li>
 *   <li>Sangria que deixaria o esperado negativo é recusada, porque não se tira da gaveta o que
 *       não está lá.</li>
 *   <li>Sessão FECHADA não aceita movimento. É a linguagem do domínio escrita em código, já que o
 *       caixa é a sessão <em>entre</em> abertura e fechamento, e protege o {@link #fechar}: um
 *       lançamento posterior tornaria mentirosa a {@code diferenca} já gravada.</li>
 * </ul>
 *
 * <p><strong>Movimento de valor zero continua aceito</strong>, e a ausência dessa regra é
 * deliberada: a questão foi levantada junto com as três acima e a restrição não foi escolhida. O
 * piso continua sendo o valor não negativo.
 *
 * <h2>O fechamento</h2>
 *
 * <p>{@link #fechar} é o outro extremo da sessão: compara o que deveria haver na gaveta com o que o
 * operador contou e grava a diferença (RF15). Duas guardas, do mesmo tipo das anteriores:
 *
 * <ul>
 *   <li>Valor contado negativo é recusado, pela mesma razão da abertura: contar zero é legítimo,
 *       contar menos que zero é erro de digitação que gravaria uma diferença sem sentido.</li>
 *   <li>Sessão FECHADA não fecha de novo. É o complemento da regra anterior: aquela protege a
 *       {@code diferenca} de um movimento posterior, esta a protege de um segundo fechamento, que a
 *       reescreveria por cima da conferência já feita.</li>
 * </ul>
 *
 * <p><strong>A regra de <em>uma sessão aberta por operador</em> não mora aqui</strong>, e nem
 * poderia: uma sessão não enxerga as outras. Quem a aplica é
 * {@code caixa.application.SessaoCaixaService}, com o índice único parcial da migration V6 embaixo.
 */
public class SessaoCaixa {

    private final UUID id;
    private final UUID usuarioId;
    private final Money valorAbertura;
    private final Instant abertaEm;

    /** Invariante viva, descrita no javadoc da classe. Nunca escrita de fora. */
    private Money valorFechamentoEsperado;

    /** Nulos até o fechamento, os três juntos. Quem os preenche é {@link #fechar}. */
    private Money valorFechamentoContado;
    private Money diferenca;
    private Instant fechadaEm;

    private StatusSessaoCaixa status;

    private final List<MovimentoCaixa> movimentos;

    /**
     * Abre uma sessão (RF13): nasce ABERTA, sem movimento nenhum, e já com o esperado igual ao que
     * foi posto na gaveta.
     *
     * @param usuarioId     quem abriu; referência entre agregados, sempre por id
     * @param valorAbertura o que existe na gaveta no começo do expediente; zero vale, negativo não
     * @throws IllegalArgumentException se {@code valorAbertura} é negativo
     */
    public SessaoCaixa(UUID usuarioId, Money valorAbertura) {
        Objects.requireNonNull(valorAbertura, "valorAbertura nao pode ser nulo");
        if (valorAbertura.isNegativo()) {
            // Abertura negativa envenenaria o esperado desde a primeira linha, e o erro só
            // apareceria na conferência do fechamento, um expediente inteiro depois.
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
     * Remonta uma sessão que já existe no banco, preservando identidade e estado.
     *
     * <p>Existe apenas para {@code SessaoCaixaEntity}. Não é caminho de abertura, e por isso não
     * passa pela guarda de valor negativo. Pelo mesmo motivo de {@code Produto.reconstituir}, não
     * recalcula o esperado a partir dos movimentos: o que está gravado já passou pelos métodos de
     * lançamento e pelos CHECK da migration, e recalcular aqui mascararia uma linha divergente em
     * vez de deixar o defeito aparecer.
     */
    public static SessaoCaixa reconstituir(UUID id, UUID usuarioId, Money valorAbertura,
            Money valorFechamentoEsperado, Money valorFechamentoContado, Money diferenca,
            Instant abertaEm, Instant fechadaEm, StatusSessaoCaixa status,
            List<MovimentoCaixa> movimentos) {
        return new SessaoCaixa(id, usuarioId, valorAbertura, valorFechamentoEsperado,
                valorFechamentoContado, diferenca, abertaEm, fechadaEm, status, movimentos);
    }

    /**
     * Sangria (RF14): retirada de dinheiro do caixa. Não é estorno nem despesa.
     *
     * @param valor  sempre positivo; quem carrega o sinal é o tipo do movimento
     * @param motivo obrigatório (RF14), porque quem retira dinheiro justifica a retirada
     * @throws IllegalArgumentException se o motivo está ausente ou em branco, ou se a retirada
     *                                  deixaria o esperado negativo
     * @throws IllegalStateException    se a sessão já está FECHADA
     */
    public void sangrar(Money valor, String motivo) {
        exigirMotivo(motivo, TipoMovimentoCaixa.SANGRIA);
        Objects.requireNonNull(valor, "valor da sangria nao pode ser nulo");

        // A conta é feita antes de anexar o movimento, para que uma sangria recusada não deixe
        // rastro nenhum no agregado. Retirar exatamente o que há vale: zera a gaveta.
        if (valorFechamentoEsperado.subtrair(valor).isNegativo()) {
            throw new IllegalArgumentException(
                    "sangria de " + valor + " e maior que os " + valorFechamentoEsperado
                            + " que deveriam estar na gaveta. Nao se retira o que nao esta la.");
        }

        registrar(TipoMovimentoCaixa.SANGRIA, valor, motivo, null);
    }

    /**
     * Suprimento (RF14): reforço de troco. Não é venda nem receita.
     *
     * @param valor  sempre positivo
     * @param motivo obrigatório (RF14)
     * @throws IllegalArgumentException se o motivo está ausente ou em branco
     * @throws IllegalStateException    se a sessão já está FECHADA
     */
    public void suprir(Money valor, String motivo) {
        exigirMotivo(motivo, TipoMovimentoCaixa.SUPRIMENTO);
        registrar(TipoMovimentoCaixa.SUPRIMENTO, valor, motivo, null);
    }

    /**
     * O dinheiro que entra por uma venda.
     *
     * <p>Sem motivo, e a assinatura diz isso sem precisar de comentário no ponto de chamada: o RF14
     * exige justificativa de sangria e de suprimento, não de venda, porque a venda já se explica
     * pela {@code vendaId}.
     *
     * <p><strong>Ainda não há chamador em produção.</strong> O método existe porque o movimento do
     * tipo VENDA já faz parte do modelo, e a raiz precisava de um nome público para ele de modo que
     * o lançamento genérico pudesse ficar privado. O chamador real nasce quando a conclusão da
     * venda passar a lançar no caixa.
     *
     * @param vendaId a venda que trouxe o dinheiro; referência entre agregados, sempre por id
     * @throws IllegalStateException se a sessão já está FECHADA
     */
    public void registrarVenda(Money valor, UUID vendaId) {
        registrar(TipoMovimentoCaixa.VENDA, valor, null, vendaId);
    }

    /**
     * Fechamento com conferência (RF15): o operador conta o dinheiro da gaveta, o agregado compara
     * com o que deveria estar lá e grava a diferença.
     *
     * <p><strong>O sinal da diferença é {@code esperado - contado}</strong>, então <em>positivo é
     * falta</em> na gaveta e <em>negativo é sobra</em>. Vale dizer em voz alta porque a intuição
     * costuma ler ao contrário: uma diferença de 2,00 não é dinheiro a mais, é dinheiro que faltou.
     *
     * <p>As quatro colunas do fechamento nascem juntas, nesta chamada e apenas nela.
     *
     * @param valorContado o que foi contado na gaveta; zero vale, negativo não
     * @return a diferença apurada, que é a pergunta que o operador está fazendo ao fechar o caixa
     * @throws IllegalArgumentException se {@code valorContado} é negativo
     * @throws IllegalStateException    se a sessão já está FECHADA
     */
    public Money fechar(Money valorContado) {
        Objects.requireNonNull(valorContado, "valorContado nao pode ser nulo");

        // A checagem de estado vem antes da do valor de propósito: sessão já fechada é recusada
        // independentemente do que se tenha contado, e a mensagem que interessa é essa.
        if (status != StatusSessaoCaixa.ABERTA) {
            throw new IllegalStateException(
                    "sessao de caixa " + id + " ja esta " + status + " e nao fecha de novo."
                            + " Um segundo fechamento reescreveria a diferenca ja conferida.");
        }

        if (valorContado.isNegativo()) {
            // Mesmo motivo da abertura: gaveta vazia se conta como zero, e um valor negativo só
            // poderia ser erro de digitação.
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
     * Anexa o movimento e mantém o esperado em dia, na mesma chamada. É o que faz a invariante da
     * classe ser verdade o tempo todo, e não apenas quando alguém lembra de recalcular.
     *
     * <p><strong>É privado de propósito.</strong> Público, seria uma porta lateral por onde se
     * lançaria sangria sem motivo e com tipo arbitrário. Quem chega aqui já passou por
     * {@link #sangrar}, {@link #suprir} ou {@link #registrarVenda}.
     */
    private void registrar(TipoMovimentoCaixa tipo, Money valor, String motivo, UUID vendaId) {
        if (status != StatusSessaoCaixa.ABERTA) {
            // Depois do fechamento a diferença já está gravada; um movimento novo a tornaria
            // mentirosa sem que nada a recalculasse.
            throw new IllegalStateException(
                    "sessao de caixa " + id + " esta " + status + " e nao aceita movimento."
                            + " Um lancamento posterior ao fechamento invalidaria a diferenca ja"
                            + " conferida.");
        }

        MovimentoCaixa movimento = MovimentoCaixa.novo(tipo, valor, motivo, vendaId);
        movimentos.add(movimento);

        // O switch é exaustivo de propósito: acrescentar um valor em TipoMovimentoCaixa quebra a
        // compilação aqui, que é exatamente onde alguém precisa decidir se o dinheiro entra ou sai.
        // Um campo de sinal dentro do enum faria a mesma conta em silêncio, com o valor errado.
        this.valorFechamentoEsperado = switch (tipo) {
            case VENDA, SUPRIMENTO -> valorFechamentoEsperado.somar(movimento.valor());
            case SANGRIA -> valorFechamentoEsperado.subtrair(movimento.valor());
        };
    }

    /**
     * A exigência de motivo (RF14) vive aqui, e não em {@link MovimentoCaixa}, porque o membro do
     * agregado aceita os três tipos e apenas dois deles exigem justificativa. O CHECK da migration
     * V5 diz o mesmo no banco.
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

    /** Nulo enquanto a sessão está ABERTA. Só {@link #fechar} o preenche. */
    public Money getValorFechamentoContado() {
        return valorFechamentoContado;
    }

    /** Nulo enquanto a sessão está ABERTA. Positivo é falta na gaveta, negativo é sobra. */
    public Money getDiferenca() {
        return diferenca;
    }

    public Instant getAbertaEm() {
        return abertaEm;
    }

    /** Nulo enquanto a sessão está ABERTA. Só {@link #fechar} o preenche. */
    public Instant getFechadaEm() {
        return fechadaEm;
    }

    public StatusSessaoCaixa getStatus() {
        return status;
    }

    /** Cópia imutável: movimento só entra pela raiz, nunca por quem leu a lista. */
    public List<MovimentoCaixa> getMovimentos() {
        return Collections.unmodifiableList(movimentos);
    }
}
