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
 * {@link #sangrar}, {@link #suprir}, {@link #registrarVenda} ou {@link #estornarVenda}. De fora
 * ninguém escolhe um tipo arbitrário nem omite o motivo.
 *
 * <ul>
 *   <li>Abertura com valor negativo é recusada; com zero, não. Abrir a gaveta sem troco é situação
 *       real: primeiro dia de operação, ou caixa que só recebe por Pix.</li>
 *   <li>Sangria que deixaria o esperado negativo é recusada, porque não se tira da gaveta o que
 *       não está lá.</li>
 *   <li>Sessão FECHADA não aceita movimento. É a linguagem do domínio escrita em código, já que o
 *       caixa é a sessão <em>entre</em> abertura e fechamento, e protege o {@link #fechar}: um
 *       lançamento posterior tornaria mentirosa a {@code diferenca} já gravada.</li>
 *   <li>A mesma venda não entra duas vezes na gaveta. O dinheiro de uma venda chega por evento,
 *       entregue ao menos uma vez, e o esperado não pode contar duas vezes o que entrou uma.</li>
 *   <li>O estorno de uma venda cancelada devolve exatamente o que a venda trouxe, e uma vez só:
 *       não se estorna venda que não entrou nesta sessão, nem a mesma venda duas vezes.</li>
 * </ul>
 *
 * <p><strong>O estorno pode deixar o esperado negativo</strong>, ao contrário da sangria, e a
 * diferença é deliberada. Sangria é decisão de agora, e recusar a que não cabe é impedir um erro.
 * O estorno é reação a um cancelamento que já aconteceu no balcão; recusá-lo aqui não desfaria o
 * cancelamento, só deixaria o caixa sem refletir o que houve, e prenderia a entrega do evento. Um
 * esperado negativo depois de uma sangria seguida de estorno é o fato a corrigir, com um
 * suprimento, do mesmo modo que o saldo de estoque negativo se corrige com um ajuste.
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
 *
 * <h2>Instantes do balcão</h2>
 *
 * <p>A abertura, a sangria, o suprimento, a entrada do dinheiro de uma venda e o fechamento têm
 * uma forma que recebe o instante pronto, além da que usa o relógio do servidor. É por ela que
 * chega o que o caixa registrou sem rede: o dia do histórico (RF16) e do fluxo de caixa (RF23) é
 * o dia em que o dinheiro mexeu no balcão, não o dia em que o servidor soube disso. A sessão
 * aberta sem rede chega também com o id que o dispositivo gerou (RNF01). As regras são as mesmas
 * nas duas formas.
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
        this(UUID.randomUUID(), usuarioId, valorAbertura, Instant.now());
    }

    /**
     * A mesma abertura, com o id e o instante que o dispositivo gravou ao abrir o caixa sem rede.
     *
     * @param id       o id gerado no dispositivo, que os gestos seguintes da sessão usam
     * @param abertaEm o instante do balcão em que o caixa abriu
     * @throws IllegalArgumentException se {@code valorAbertura} é negativo
     */
    public SessaoCaixa(UUID id, UUID usuarioId, Money valorAbertura, Instant abertaEm) {
        Objects.requireNonNull(valorAbertura, "valorAbertura nao pode ser nulo");
        if (valorAbertura.isNegativo()) {
            // Abertura negativa envenenaria o esperado desde a primeira linha, e o erro só
            // apareceria na conferência do fechamento, um expediente inteiro depois.
            throw new IllegalArgumentException(
                    "valor de abertura nao pode ser negativo: " + valorAbertura
                            + ". Abrir sem troco e zero, nunca um valor negativo.");
        }

        this.id = Objects.requireNonNull(id, "id da sessao de caixa nao pode ser nulo");
        this.usuarioId = Objects.requireNonNull(usuarioId, "usuarioId nao pode ser nulo");
        this.valorAbertura = valorAbertura;
        this.abertaEm = Objects.requireNonNull(abertaEm, "abertaEm nao pode ser nulo");
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
        sangrar(valor, motivo, Instant.now());
    }

    /** A mesma sangria, com o instante do balcão em que o dinheiro saiu da gaveta. */
    public void sangrar(Money valor, String motivo, Instant criadoEm) {
        exigirMotivo(motivo, TipoMovimentoCaixa.SANGRIA);
        Objects.requireNonNull(valor, "valor da sangria nao pode ser nulo");

        // A conta é feita antes de anexar o movimento, para que uma sangria recusada não deixe
        // rastro nenhum no agregado. Retirar exatamente o que há vale: zera a gaveta.
        if (valorFechamentoEsperado.subtrair(valor).isNegativo()) {
            throw new IllegalArgumentException(
                    "sangria de " + valor + " e maior que os " + valorFechamentoEsperado
                            + " que deveriam estar na gaveta. Nao se retira o que nao esta la.");
        }

        registrar(TipoMovimentoCaixa.SANGRIA, valor, motivo, null, null, criadoEm);
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
        suprir(valor, motivo, Instant.now());
    }

    /** O mesmo suprimento, com o instante do balcão em que o troco entrou na gaveta. */
    public void suprir(Money valor, String motivo, Instant criadoEm) {
        exigirMotivo(motivo, TipoMovimentoCaixa.SUPRIMENTO);
        registrar(TipoMovimentoCaixa.SUPRIMENTO, valor, motivo, null, null, criadoEm);
    }

    /**
     * O dinheiro em espécie que entra na gaveta por uma venda concluída.
     *
     * <p>Sem motivo, e a assinatura diz isso sem precisar de comentário no ponto de chamada: o RF14
     * exige justificativa de sangria e de suprimento, não de venda, porque a venda já se explica
     * pela {@code vendaId}.
     *
     * <p><strong>O valor é só o que foi pago em dinheiro</strong>, e quem soma isso é o listener do
     * evento de venda concluída, que é o único chamador. O que foi pago em Pix ou cartão nunca
     * esteve na gaveta, e por isso não entra no esperado: se entrasse, a conferência do
     * fechamento (RF15) acusaria falta em toda venda que não fosse em espécie.
     *
     * <p><strong>A mesma venda não entra duas vezes.</strong> O evento chega ao caixa por um outbox
     * que garante a entrega ao menos uma vez, então uma reentrega é possível; é o listener que a
     * reconhece e pula, por {@link #jaRegistrouVenda}. A recusa aqui é a invariante em si, para
     * qualquer chamador: dinheiro contado em dobro no esperado é uma diferença de fechamento que
     * nunca existiu.
     *
     * @param vendaId a venda que trouxe o dinheiro; referência entre agregados, sempre por id
     * @throws IllegalStateException se a sessão já está FECHADA, ou se esta venda já foi lançada
     *                               nesta sessão
     */
    public void registrarVenda(Money valor, UUID vendaId) {
        registrarVenda(valor, vendaId, Instant.now());
    }

    /**
     * A mesma entrada, com o instante em que a venda foi concluída no balcão: é quando o dinheiro
     * entrou na gaveta, mesmo que o servidor só saiba disso depois.
     */
    public void registrarVenda(Money valor, UUID vendaId, Instant criadoEm) {
        Objects.requireNonNull(vendaId, "vendaId nao pode ser nulo");
        if (jaRegistrouVenda(vendaId)) {
            throw new IllegalStateException(
                    "venda " + vendaId + " ja foi lancada na sessao de caixa " + id
                            + " e nao entra de novo. O dinheiro de uma venda conta uma vez so.");
        }
        registrar(TipoMovimentoCaixa.VENDA, valor, null, vendaId, null, criadoEm);
    }

    /**
     * Se o dinheiro desta venda já entrou nesta sessão. É a pergunta que o listener faz antes de
     * lançar, porque o evento pode chegar mais de uma vez. Continua verdadeira depois de um
     * estorno: o dinheiro entrou, e o estorno é outro movimento.
     */
    public boolean jaRegistrouVenda(UUID vendaId) {
        Objects.requireNonNull(vendaId, "vendaId nao pode ser nulo");
        return temMovimento(TipoMovimentoCaixa.VENDA, vendaId);
    }

    /**
     * O dinheiro em espécie que sai da gaveta porque a venda foi cancelada (RF12): o oposto exato
     * de {@link #registrarVenda}.
     *
     * <p><strong>O valor não é parâmetro, de propósito.</strong> O que sai é o que entrou, e quem
     * sabe quanto entrou é esta sessão, que tem o movimento VENDA daquela venda. Um valor vindo de
     * fora seria uma segunda conta para a mesma pergunta, com espaço para as duas divergirem. Por
     * isso também não existe estorno de venda que não entrou aqui: não há o que espelhar, e a
     * recusa é alta em vez de lançar zero.
     *
     * <p><strong>A mesma venda não sai duas vezes</strong>, pelo mesmo motivo de não entrar duas
     * vezes: o evento de cancelamento chega ao menos uma vez, o listener reconhece a reentrega por
     * {@link #jaEstornouVenda}, e a recusa aqui é a invariante para qualquer chamador.
     *
     * <p>Não olha se o esperado fica negativo; ver o javadoc da classe.
     *
     * @param vendaId a venda cancelada; referência entre agregados, sempre por id
     * @throws IllegalStateException se a sessão já está FECHADA, se esta venda não entrou nesta
     *                               sessão, ou se já foi estornada
     */
    public void estornarVenda(UUID vendaId) {
        estornarVenda(vendaId, Money.ZERO);
    }

    /** Estorna também o dinheiro de fiado recebido, inclusive quando entrou em outra sessão. */
    public void estornarVenda(UUID vendaId, Money valorDeRecebimentos) {
        Objects.requireNonNull(vendaId, "vendaId nao pode ser nulo");
        Objects.requireNonNull(valorDeRecebimentos, "valor de recebimentos nao pode ser nulo");
        if (valorDeRecebimentos.isNegativo()) {
            throw new IllegalArgumentException("valor de recebimentos nao pode ser negativo");
        }

        MovimentoCaixa entrada = movimentos.stream()
                .filter(movimento -> movimento.tipo() == TipoMovimentoCaixa.VENDA)
                .filter(movimento -> vendaId.equals(movimento.vendaId()))
                .findFirst()
                .orElse(null);
        if (entrada == null && valorDeRecebimentos.equals(Money.ZERO)) {
            throw new IllegalStateException("venda " + vendaId + " nao entrou na sessao de caixa "
                    + id + " e nao tem o que estornar. So se devolve o que entrou.");
        }
        if (jaEstornouVenda(vendaId)) {
            throw new IllegalStateException(
                    "venda " + vendaId + " ja foi estornada na sessao de caixa " + id
                            + " e nao sai de novo. O dinheiro de uma venda volta uma vez so.");
        }

        Money valorDaVenda = entrada == null ? Money.ZERO : entrada.valor();
        registrar(TipoMovimentoCaixa.ESTORNO, valorDaVenda.somar(valorDeRecebimentos), null,
                vendaId, null, Instant.now());
    }

    /** Cada recebimento em dinheiro entra uma vez, mesmo após reentrega do evento. */
    public void registrarRecebimento(UUID vendaId, UUID recebimentoId, Money valor) {
        Objects.requireNonNull(vendaId, "vendaId nao pode ser nulo");
        Objects.requireNonNull(recebimentoId, "recebimentoId nao pode ser nulo");
        if (jaRegistrouRecebimento(recebimentoId)) {
            throw new IllegalStateException("recebimento ja lancado nesta sessao: " + recebimentoId);
        }
        registrar(TipoMovimentoCaixa.RECEBIMENTO, valor, null, vendaId, recebimentoId,
                Instant.now());
    }

    public boolean jaRegistrouRecebimento(UUID recebimentoId) {
        return movimentos.stream().anyMatch(m -> recebimentoId.equals(m.recebimentoId()));
    }

    /**
     * Se o dinheiro desta venda já saiu desta sessão por estorno. É a pergunta que o listener do
     * cancelamento faz antes de estornar, porque o evento pode chegar mais de uma vez.
     */
    public boolean jaEstornouVenda(UUID vendaId) {
        Objects.requireNonNull(vendaId, "vendaId nao pode ser nulo");
        return temMovimento(TipoMovimentoCaixa.ESTORNO, vendaId);
    }

    private boolean temMovimento(TipoMovimentoCaixa tipo, UUID vendaId) {
        return movimentos.stream()
                .filter(movimento -> movimento.tipo() == tipo)
                .anyMatch(movimento -> vendaId.equals(movimento.vendaId()));
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
        return fechar(valorContado, Instant.now());
    }

    /** O mesmo fechamento, com o instante do balcão em que a gaveta foi contada. */
    public Money fechar(Money valorContado, Instant fechadaEm) {
        Objects.requireNonNull(valorContado, "valorContado nao pode ser nulo");
        Objects.requireNonNull(fechadaEm, "fechadaEm nao pode ser nulo");

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
        this.fechadaEm = fechadaEm;
        this.status = StatusSessaoCaixa.FECHADA;

        return diferenca;
    }

    /**
     * Anexa o movimento e mantém o esperado em dia, na mesma chamada. É o que faz a invariante da
     * classe ser verdade o tempo todo, e não apenas quando alguém lembra de recalcular.
     *
     * <p><strong>É privado de propósito.</strong> Público, seria uma porta lateral por onde se
     * lançaria sangria sem motivo e com tipo arbitrário. Quem chega aqui já passou por
     * {@link #sangrar}, {@link #suprir}, {@link #registrarVenda} ou {@link #estornarVenda}.
     */
    private void registrar(TipoMovimentoCaixa tipo, Money valor, String motivo, UUID vendaId,
            UUID recebimentoId, Instant criadoEm) {
        if (status != StatusSessaoCaixa.ABERTA) {
            // Depois do fechamento a diferença já está gravada; um movimento novo a tornaria
            // mentirosa sem que nada a recalculasse.
            throw new IllegalStateException(
                    "sessao de caixa " + id + " esta " + status + " e nao aceita movimento."
                            + " Um lancamento posterior ao fechamento invalidaria a diferenca ja"
                            + " conferida.");
        }

        MovimentoCaixa movimento = MovimentoCaixa.novo(tipo, valor, motivo, vendaId, recebimentoId,
                criadoEm);
        movimentos.add(movimento);

        // O switch é exaustivo de propósito: acrescentar um valor em TipoMovimentoCaixa quebra a
        // compilação aqui, que é exatamente onde alguém precisa decidir se o dinheiro entra ou sai.
        // Um campo de sinal dentro do enum faria a mesma conta em silêncio, com o valor errado.
        this.valorFechamentoEsperado = switch (tipo) {
            case VENDA, SUPRIMENTO, RECEBIMENTO -> valorFechamentoEsperado.somar(movimento.valor());
            case SANGRIA, ESTORNO -> valorFechamentoEsperado.subtrair(movimento.valor());
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
