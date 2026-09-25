package br.com.caixasimples.cadastro.domain;

import br.com.caixasimples.cadastro.TipoProduto;
import br.com.caixasimples.shared.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Produto ou serviço do catálogo de uma conta. Raiz do agregado Produto, que tem
 * {@link MovimentoEstoque} como membro.
 *
 * <p><strong>A raiz não carrega o histórico de movimentos</strong>, e isso é deliberado. Ela
 * guarda o saldo consolidado e, a cada baixa, estorno ou ajuste, devolve o movimento novo a quem
 * a chamou, para que os dois sejam gravados juntos. O histórico de um produto cresce a cada
 * venda, sem limite, e o produto é lido em toda venda; remontá-lo inteiro a cada leitura, como o
 * caixa faz com um expediente, seria pagar exatamente o custo que o saldo consolidado existe para
 * evitar.
 *
 * <p><strong>Não importa framework</strong>, nem {@code jakarta.persistence} nem
 * {@code org.springframework}. O mapeamento para o banco vive em
 * {@code cadastro.internal.ProdutoEntity}.
 *
 * <p><strong>Não carrega {@code contaId}, e isso é deliberado.</strong> O tenant é preenchido pelo
 * Hibernate na entidade, via {@code @TenantId}, a partir do contexto da requisição. Deixando a
 * conta fora do domínio, não existe assinatura em que um chamador possa informá-la, que é
 * literalmente o que o isolamento entre contas proíbe (RNF05).
 *
 * <p>Por que estrutura completa aqui e vertical slice em {@code Cliente}: a camada {@code domain/}
 * é reservada às raízes que têm invariante de verdade a proteger. Onde não há invariante, ela seria
 * cerimônia.
 */
public class Produto {

    private final UUID id;
    private final Instant criadoEm;

    private String nome;
    private Money preco;
    private String codigo;
    private String categoria;
    private String unidade;
    private TipoProduto tipo;

    /**
     * Saldo consolidado, nunca somado do histórico a cada leitura, que é o que mantém barato o
     * alerta de estoque baixo (RF20). Só se move por {@link #darBaixaPorVenda},
     * {@link #estornarPorCancelamento} e {@link #ajustarEstoque}, que devolvem o movimento
     * correspondente para ser gravado na mesma transação; não há setter nem edição de cadastro
     * que o toque.
     *
     * <p><strong>Pode ficar negativo.</strong> A venda que baixou mais do que o saldo registrava
     * já aconteceu no balcão; recusar a baixa aqui não desfaria a venda, só deixaria o estoque
     * mentindo por omissão. O saldo negativo é o fato a corrigir, por um ajuste de contagem, e é o
     * que o alerta de estoque baixo expõe.
     *
     * <p>Existe também em SERVICO, que simplesmente nunca recebe movimento. Uma coluna sempre
     * preenchida evita nulo em todo leitor; o custo é que um serviço aparece com saldo zero, então
     * o alerta filtra por {@link TipoProduto}, do mesmo modo que filtra pelas contas com estoque
     * habilitado.
     */
    private BigDecimal estoqueAtual;

    /**
     * Limiar do alerta de estoque baixo (RF20): o produto está baixo quando o saldo é menor ou
     * igual a ele. É por produto porque baixo depende do item: dois quilos de queijo e duas
     * garrafas de água não são o mesmo baixo. Nasce em zero, o que faz o alerta avisar quando o
     * item acabou mesmo sem ninguém ter configurado nada; quem informa um mínimo maior passa a ser
     * avisado antes.
     *
     * <p>Sempre preenchido, inclusive em SERVICO, pelo mesmo motivo de {@link #estoqueAtual}.
     */
    private BigDecimal estoqueMinimo;

    private Map<String, Object> atributos;
    private boolean ativo;

    /**
     * Cadastro de um item novo (RF01, RF02).
     *
     * @param nome      obrigatório
     * @param preco     obrigatório; zero é válido, negativo não
     * @param tipo      obrigatório
     * @param codigo    opcional; espaços nas pontas somem e texto em branco vira ausência
     * @param categoria opcional
     * @param unidade   opcional, por exemplo {@code un}, {@code kg} ou {@code hora}
     * @param atributos opcional; nulo vira mapa vazio, porque ausência de atributo específico não
     *                  é um caso a tratar em quem lê (RF02)
     */
    public Produto(String nome, Money preco, TipoProduto tipo, String codigo, String categoria,
            String unidade, Map<String, Object> atributos) {
        this(UUID.randomUUID(), Instant.now(), nome, preco, tipo, codigo, categoria, unidade,
                atributos);
    }

    /**
     * O mesmo cadastro, com o id e o instante que o dispositivo gravou ao cadastrar o item sem
     * rede (RNF01). As regras são as mesmas.
     *
     * @param id       o id gerado no dispositivo, que as Vendas registradas sem rede já usam
     * @param criadoEm o instante do balcão em que o item foi cadastrado
     */
    public Produto(UUID id, Instant criadoEm, String nome, Money preco, TipoProduto tipo,
            String codigo, String categoria, String unidade, Map<String, Object> atributos) {
        this.id = Objects.requireNonNull(id, "id do produto nao pode ser nulo");
        this.criadoEm = Objects.requireNonNull(criadoEm, "criadoEm nao pode ser nulo");
        this.nome = exigirTexto(nome, "nome");
        this.preco = exigirPreco(preco);
        this.tipo = Objects.requireNonNull(tipo, "tipo nao pode ser nulo");
        this.codigo = textoOpcional(codigo);
        this.categoria = textoOpcional(categoria);
        this.unidade = textoOpcional(unidade);
        this.atributos = copiar(atributos);
        this.estoqueAtual = BigDecimal.ZERO;
        this.estoqueMinimo = BigDecimal.ZERO;
        this.ativo = true;
    }

    private Produto(UUID id, Instant criadoEm, String nome, Money preco, TipoProduto tipo,
            String codigo, String categoria, String unidade, BigDecimal estoqueAtual,
            BigDecimal estoqueMinimo, Map<String, Object> atributos, boolean ativo) {
        this.id = id;
        this.criadoEm = criadoEm;
        this.nome = nome;
        this.preco = preco;
        this.tipo = tipo;
        this.codigo = codigo;
        this.categoria = categoria;
        this.unidade = unidade;
        this.estoqueAtual = estoqueAtual;
        this.estoqueMinimo = estoqueMinimo;
        this.atributos = copiar(atributos);
        this.ativo = ativo;
    }

    /**
     * Remonta um produto que já existe no banco, preservando identidade e estado.
     *
     * <p>Existe apenas para {@code ProdutoEntity}, e não é caminho de cadastro. Por isso não
     * revalida: o que está gravado já passou pelo construtor público e pelos CHECK da migration, e
     * recusar aqui deixaria uma linha existente impossível de ler.
     */
    public static Produto reconstituir(UUID id, Instant criadoEm, String nome, Money preco,
            TipoProduto tipo, String codigo, String categoria, String unidade,
            BigDecimal estoqueAtual, BigDecimal estoqueMinimo, Map<String, Object> atributos,
            boolean ativo) {
        return new Produto(id, criadoEm, nome, preco, tipo, codigo, categoria, unidade,
                estoqueAtual, estoqueMinimo, atributos, ativo);
    }

    /**
     * Aplica uma edição do cadastro (RF04). As regras são as mesmas do construtor, e de propósito:
     * o que não entra num produto novo também não entra num produto editado.
     *
     * <p>Os atributos são <strong>substituídos por inteiro</strong>, nunca mesclados, porque a
     * edição descreve o produto como ele fica, e não um delta sobre o que estava lá.
     *
     * <p><strong>Não recebe {@code tipo}, e a ausência é que é a decisão.</strong> O tipo decide se
     * o item participa de estoque, então trocá-lo depois deixaria um movimento de estoque órfão num
     * item que virou SERVICO, ou um SERVICO com saldo. Errou o tipo no cadastro? Use
     * {@link #inativar()} e recadastre; o histórico de vendas do item antigo continua de pé.
     *
     * <p>Não recebe {@code estoqueAtual} pelo mesmo tipo de motivo: saldo só se move por movimento
     * de estoque, nunca por edição de cadastro. Nem {@code estoqueMinimo}: o limiar do alerta é
     * política de estoque, não descrição do item, e tem caso de uso próprio,
     * {@link #definirEstoqueMinimo}, para o formulário de cadastro não carregar um campo que só
     * faz sentido com o controle de estoque ligado.
     *
     * @throws IllegalStateException se o produto já foi inativado. Como não há reativação, editar
     *                               um registro que ninguém mais enxerga não teria efeito nenhum
     */
    public void alterar(String nome, Money preco, String codigo, String categoria, String unidade,
            Map<String, Object> atributos) {
        if (!ativo) {
            throw new IllegalStateException("produto inativo nao pode ser editado: " + id);
        }
        this.nome = exigirTexto(nome, "nome");
        this.preco = exigirPreco(preco);
        this.codigo = textoOpcional(codigo);
        this.categoria = textoOpcional(categoria);
        this.unidade = textoOpcional(unidade);
        this.atributos = copiar(atributos);
    }

    /**
     * Soft delete (RF05): o registro fica, para o histórico de vendas não perder a referência.
     *
     * <p><strong>É idempotente.</strong> Inativar um produto já inativo não é erro de ninguém, e
     * sim o mesmo estado pedido de novo: dois cliques no balcão, ou a requisição que o cliente
     * offline reenvia ao voltar a rede.
     *
     * <p>Não existe {@code reativar()}. Reativar esbarraria no índice único parcial do código do
     * produto, que só vale entre os ativos, porque o código do item inativado pode já ter sido
     * reaproveitado por outro.
     */
    public void inativar() {
        this.ativo = false;
    }

    /**
     * Se este item tem estoque para controlar: só PRODUTO tem. SERVICO nunca recebe movimento, e é
     * esta pergunta que o caso de uso faz antes de pedir a baixa, porque vender um serviço é
     * legítimo e não é erro de ninguém.
     */
    public boolean controlaEstoque() {
        return tipo == TipoProduto.PRODUTO;
    }

    /**
     * Baixa de uma venda concluída (RF18): o saldo desce e o movimento correspondente é devolvido,
     * para que quem persiste grave os dois na mesma transação. É um dos três caminhos que movem
     * {@code estoqueAtual}, ao lado de {@link #estornarPorCancelamento} e {@link #ajustarEstoque}.
     *
     * <p><strong>O saldo pode ficar negativo</strong>; ver o comentário do campo.
     *
     * <p><strong>Não olha {@code ativo}</strong>, de propósito: a venda aconteceu enquanto o
     * produto estava ativo, e a montagem da venda já recusa produto inativado. Um produto
     * inativado entre a venda e a baixa ainda deve o estoque que vendeu.
     *
     * <p><strong>Não sabe se a mesma venda já baixou.</strong> A raiz não carrega o histórico,
     * então a pergunta vai ao repositório pelo caso de uso, que recusa a duplicata antes de chegar
     * aqui; a rede embaixo é o índice único da migration V9.
     *
     * @param quantidade o que a venda levou; positiva
     * @param vendaId    a venda que levou; referência entre agregados, sempre por id
     * @return o movimento de SAIDA que esta baixa gerou, para ser gravado junto do saldo
     * @throws IllegalStateException    se o item é SERVICO, que não tem estoque
     * @throws IllegalArgumentException se a quantidade é nula, zero ou negativa
     */
    public MovimentoEstoque darBaixaPorVenda(BigDecimal quantidade, UUID vendaId) {
        Objects.requireNonNull(quantidade, "quantidade nao pode ser nula");
        Objects.requireNonNull(vendaId, "vendaId nao pode ser nulo");
        if (!controlaEstoque()) {
            throw new IllegalStateException(
                    "servico nao tem estoque para baixar: " + id
                            + ". So produto recebe movimento de estoque.");
        }
        if (quantidade.signum() <= 0) {
            throw new IllegalArgumentException(
                    "quantidade da baixa deve ser positiva: " + quantidade);
        }
        this.estoqueAtual = this.estoqueAtual.subtract(quantidade);
        return MovimentoEstoque.saidaPorVenda(quantidade, vendaId);
    }

    /**
     * Estorno de uma venda cancelada (RF12): o oposto exato de {@link #darBaixaPorVenda}. O saldo
     * sobe o que a venda tinha levado e o movimento de ENTRADA correspondente é devolvido, para
     * que quem persiste grave os dois na mesma transação.
     *
     * <p><strong>Não olha {@code ativo}</strong>, como a baixa: o cancelamento é consequência de
     * uma venda que já aconteceu, e o estoque que volta para a prateleira volta, esteja o item no
     * catálogo ou não.
     *
     * <p><strong>Não sabe se a venda chegou a dar baixa, nem se já foi estornada.</strong> A raiz
     * não carrega o histórico, então as duas perguntas são feitas ao repositório pelo caso de uso,
     * que recusa o estorno sem baixa e o estorno em dobro antes de chegar aqui; a rede embaixo é o
     * índice único da migration V9, que já inclui o tipo para a SAIDA e a ENTRADA da mesma venda
     * caberem lado a lado.
     *
     * @param quantidade o que a venda tinha levado, e volta; positiva
     * @param vendaId    a venda cancelada; referência entre agregados, sempre por id
     * @return o movimento de ENTRADA que este estorno gerou, para ser gravado junto do saldo
     * @throws IllegalStateException    se o item é SERVICO, que não tem estoque
     * @throws IllegalArgumentException se a quantidade é nula, zero ou negativa
     */
    public MovimentoEstoque estornarPorCancelamento(BigDecimal quantidade, UUID vendaId) {
        Objects.requireNonNull(quantidade, "quantidade nao pode ser nula");
        Objects.requireNonNull(vendaId, "vendaId nao pode ser nulo");
        if (!controlaEstoque()) {
            throw new IllegalStateException(
                    "servico nao tem estoque para estornar: " + id
                            + ". So produto recebe movimento de estoque.");
        }
        if (quantidade.signum() <= 0) {
            throw new IllegalArgumentException(
                    "quantidade do estorno deve ser positiva: " + quantidade);
        }
        this.estoqueAtual = this.estoqueAtual.add(quantidade);
        return MovimentoEstoque.entradaPorCancelamento(quantidade, vendaId);
    }

    /**
     * Ajuste manual do estoque (RF19): perda, quebra ou contagem. O saldo recebe a diferença e o
     * movimento de AJUSTE correspondente é devolvido, para que quem persiste grave os dois na
     * mesma transação. É o terceiro caminho, além de {@link #darBaixaPorVenda} e de
     * {@link #estornarPorCancelamento}, que move {@code estoqueAtual}.
     *
     * <p><strong>A diferença carrega o sinal.</strong> Positiva soma, negativa subtrai: uma perda
     * de duas unidades é {@code -2}, uma contagem que achou três a mais é {@code +3}. Perda e
     * quebra são o caso natural dessa forma; na contagem, quem chama informa o contado menos o
     * registrado, e o operador vê a diferença antes de confirmar. Ao contrário da baixa por venda,
     * em que o sinal está no tipo, aqui está na quantidade, porque o mesmo tipo AJUSTE serve para
     * os dois sentidos.
     *
     * <p><strong>O motivo é obrigatório</strong>, como na sangria e no suprimento do caixa: quem
     * mexe no saldo à mão justifica. A exigência mora aqui, e não no movimento, porque o membro
     * do agregado apenas registra o que a raiz decidiu.
     *
     * <p><strong>Recusa produto inativo</strong>, como {@link #alterar}: ajustar à mão o estoque
     * de um item que ninguém mais enxerga não teria efeito nenhum. A baixa por venda não olha
     * {@code ativo} porque a venda já aconteceu; o ajuste é uma decisão de agora.
     *
     * <p>O saldo pode ficar negativo aqui também: uma perda maior do que o registrado é o mesmo
     * fato que a venda que baixou mais do que havia, e o acerto é outra contagem.
     *
     * @param diferenca o que soma ou subtrai do saldo, com sinal; nunca zero
     * @param motivo    obrigatório (RF19)
     * @return o movimento de AJUSTE que esta diferença gerou, para ser gravado junto do saldo
     * @throws IllegalStateException    se o item é SERVICO, que não tem estoque, ou está inativo
     * @throws IllegalArgumentException se a diferença é zero ou se o motivo está ausente ou em
     *                                  branco
     */
    public MovimentoEstoque ajustarEstoque(BigDecimal diferenca, String motivo) {
        Objects.requireNonNull(diferenca, "diferenca nao pode ser nula");
        if (!controlaEstoque()) {
            throw new IllegalStateException(
                    "servico nao tem estoque para ajustar: " + id
                            + ". So produto recebe movimento de estoque.");
        }
        if (!ativo) {
            throw new IllegalStateException("produto inativo nao tem estoque ajustado: " + id);
        }
        if (motivo == null || motivo.isBlank()) {
            throw new IllegalArgumentException("motivo e obrigatorio em ajuste de estoque (RF19)");
        }
        if (diferenca.signum() == 0) {
            throw new IllegalArgumentException(
                    "diferenca do ajuste nao pode ser zero: nada entrou nem saiu");
        }
        this.estoqueAtual = this.estoqueAtual.add(diferenca);
        return MovimentoEstoque.ajuste(diferenca, motivo);
    }

    /**
     * Define o limiar do alerta de estoque baixo (RF20); ver o comentário de
     * {@link #estoqueMinimo}.
     *
     * <p>Recusa SERVICO e produto inativo pelos mesmos motivos de {@link #ajustarEstoque}: serviço
     * não tem estoque para alertar, e item que saiu do catálogo não vai ser reposto.
     *
     * @param minimo zero ou mais, com no máximo três casas, como todo saldo de estoque
     * @throws IllegalStateException    se o item é SERVICO ou está inativo
     * @throws IllegalArgumentException se o mínimo é negativo ou tem mais de três casas
     */
    public void definirEstoqueMinimo(BigDecimal minimo) {
        Objects.requireNonNull(minimo, "estoque minimo nao pode ser nulo");
        if (!controlaEstoque()) {
            throw new IllegalStateException(
                    "servico nao tem estoque minimo: " + id + ". So produto tem estoque.");
        }
        if (!ativo) {
            throw new IllegalStateException(
                    "produto inativo nao tem estoque minimo definido: " + id);
        }
        if (minimo.signum() < 0) {
            // Com o saldo podendo ficar negativo, um mínimo abaixo de zero esconderia justamente
            // o produto que já vendeu mais do que tinha.
            throw new IllegalArgumentException("estoque minimo nao pode ser negativo: " + minimo);
        }
        MovimentoEstoque.exigirCasasDaQuantidade(minimo, "estoque minimo");
        this.estoqueMinimo = minimo;
    }

    /**
     * Se este produto está no limiar do alerta de estoque baixo (RF20): saldo menor ou igual ao
     * mínimo. Com o mínimo em zero, que é o padrão, responde sim quando o item acabou ou ficou
     * negativo.
     *
     * <p>SERVICO nunca está baixo, porque não tem estoque. Não olha {@code ativo}: quem monta a
     * lista do alerta já consulta só os ativos, porque item fora do catálogo não vai ser reposto.
     */
    public boolean estaComEstoqueBaixo() {
        return controlaEstoque() && estoqueAtual.compareTo(estoqueMinimo) <= 0;
    }

    private static Money exigirPreco(Money preco) {
        Objects.requireNonNull(preco, "preco nao pode ser nulo");
        if (preco.isNegativo()) {
            // Zero passa, porque cortesia, brinde e item de acompanhamento existem. Negativo não é
            // preço: seria desconto, e desconto é da venda, não do cadastro.
            throw new IllegalArgumentException("preco nao pode ser negativo: " + preco);
        }
        return preco;
    }

    private static String exigirTexto(String valor, String campo) {
        if (valor == null || valor.isBlank()) {
            throw new IllegalArgumentException(campo + " nao pode ser vazio");
        }
        return valor.trim();
    }

    private static String textoOpcional(String valor) {
        if (valor == null || valor.isBlank()) {
            return null;
        }
        return valor.trim();
    }

    private static Map<String, Object> copiar(Map<String, Object> atributos) {
        if (atributos == null) {
            return Map.of();
        }
        // unmodifiableMap sobre uma cópia, e não Map.copyOf: o JSONB pode ter valor nulo, que
        // Map.copyOf recusa, e uma linha já gravada assim ficaria impossível de ler.
        return Collections.unmodifiableMap(new LinkedHashMap<>(atributos));
    }

    public UUID getId() {
        return id;
    }

    public Instant getCriadoEm() {
        return criadoEm;
    }

    public String getNome() {
        return nome;
    }

    public Money getPreco() {
        return preco;
    }

    public TipoProduto getTipo() {
        return tipo;
    }

    /** Pode ser nulo: muitos negócios não usam código nenhum. */
    public String getCodigo() {
        return codigo;
    }

    public String getCategoria() {
        return categoria;
    }

    public String getUnidade() {
        return unidade;
    }

    public BigDecimal getEstoqueAtual() {
        return estoqueAtual;
    }

    public BigDecimal getEstoqueMinimo() {
        return estoqueMinimo;
    }

    /** Cópia imutável: atributo só muda pela raiz, nunca por quem leu o mapa. */
    public Map<String, Object> getAtributos() {
        return atributos;
    }

    public boolean isAtivo() {
        return ativo;
    }
}
