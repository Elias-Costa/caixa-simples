package br.com.caixasimples.vendas.domain;

import br.com.caixasimples.pagamentos.FormaPagamento;
import br.com.caixasimples.pagamentos.StatusPagamento;
import br.com.caixasimples.shared.Money;
import br.com.caixasimples.vendas.StatusVenda;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Uma venda do balcão. Raiz do agregado Venda, com {@link ItemVenda} e {@link Pagamento} como
 * membros.
 *
 * <p><strong>As invariantes que esta classe existe para guardar:</strong>
 * {@code valorTotal = soma dos subtotais dos itens menos o desconto da venda}, o tempo todo; e,
 * numa venda CONCLUIDA, a soma dos pagamentos CONFIRMADO é igual ao {@code valorTotal} (RF09). É
 * por isso que item e pagamento não têm repositório: uma linha alterada por fora deixaria o total
 * mentindo.
 *
 * <p>A primeira invariante vale <em>o tempo todo</em>, e não apenas na conclusão: o total é
 * reescrito a cada item que entra ou sai e a cada desconto aplicado, do mesmo jeito que o esperado
 * da sessão de caixa acompanha os movimentos. Perguntar quanto a comanda está devendo é ler um
 * campo, não somar a lista. A segunda é conferida por {@link #concluir}, que é a única transição
 * para CONCLUIDA, e as duas são conferidas de novo por {@link #reconstituir}: um estado que as
 * viole não entra no agregado, venha de onde vier. Uma venda CANCELADA não tem a segunda
 * invariante: ela pode ter sido abandonada com a conta pela metade.
 *
 * <p><strong>Não importa framework</strong>, nem {@code jakarta.persistence} nem
 * {@code org.springframework}. O mapeamento vive em {@code vendas.internal.VendaEntity}.
 *
 * <p><strong>Não carrega {@code contaId}</strong>, pelo mesmo motivo dos outros agregados: sem o
 * campo, não existe assinatura por onde um chamador pudesse informar a conta, que é exatamente o
 * que o isolamento entre contas proíbe (RNF05). O tenant é preenchido pelo Hibernate na entidade.
 *
 * <p>{@code sessaoCaixaId}, {@code usuarioId}, {@code clienteId} e o {@code produtoId} de cada
 * item são referências entre agregados: sempre por id, nunca objeto navegável. O cliente é
 * opcional (RF03), e por isso é o único que aceita nulo.
 *
 * <h2>As regras da montagem</h2>
 *
 * <p>A comanda se monta por {@link #adicionarItem}, {@link #removerItem} e
 * {@link #aplicarDesconto}, e as três obedecem à mesma regra de uma frase: <strong>nenhum valor
 * fica negativo</strong>. A operação que deixaria um item ou a venda abaixo de zero é recusada
 * antes de tocar no agregado, e por isso uma recusa não deixa rastro.
 *
 * <ul>
 *   <li>Desconto de item maior que o valor bruto do item é recusado. Brinde é preço zero, não
 *       desconto acima do valor. Desconto igual ao bruto vale, e zera o item.</li>
 *   <li>Desconto da venda maior que a soma dos itens é recusado, inclusive em venda vazia. E
 *       remover um item que deixaria o desconto já aplicado maior que a soma restante também é
 *       recusado: quem quer tirar o item reduz o desconto antes.</li>
 *   <li>O mesmo produto pode aparecer em duas linhas, com descontos diferentes. As linhas não se
 *       mesclam, porque item lançado não se edita.</li>
 *   <li>Só venda ABERTA aceita montagem. Uma venda CONCLUIDA já tem os pagamentos batendo com o
 *       total, e uma CANCELADA já produziu o estorno; mexer nos itens de qualquer das duas tornaria
 *       mentiroso o que já foi gravado.</li>
 *   <li>Depois de uma parcela lançada, a comanda continua aberta a montagem, mas nenhuma operação
 *       deixa o total abaixo do que já foi pago: remover item ou aplicar desconto que fizesse isso
 *       é recusado. Adicionar item nunca reduz o total, então nunca é recusado por esse motivo.</li>
 * </ul>
 *
 * <p><strong>O preço unitário chega pronto</strong>, copiado do produto por quem monta a venda.
 * A raiz não conhece o cadastro e não teria como consultá-lo; o que ela garante é que o valor
 * recebido é o que fica gravado, mesmo que o produto seja reajustado depois.
 *
 * <h2>O pagamento e a conclusão</h2>
 *
 * <p>Uma venda pode ser dividida entre formas (RF09): cada parcela entra por
 * {@link #registrarPagamento}, e {@link #concluir} é um passo à parte, que confere a conta e vira
 * a venda para CONCLUIDA. Registrar a parcela que fecha a conta <strong>não</strong> conclui a
 * venda: um método com esse nome que às vezes mudasse o status seria comportamento escondido, e
 * quem finaliza a venda chama a operação que diz isso.
 *
 * <ul>
 *   <li>Parcela maior que o que falta pagar é recusada. O que falta é o total menos as parcelas já
 *       lançadas que não estão RECUSADO: uma parcela PENDENTE, à espera de um provedor, reserva o
 *       lugar dela; uma RECUSADO é desfecho encerrado e não ocupa lugar.</li>
 *   <li>A venda só conclui com a soma das parcelas CONFIRMADO igual ao total. A menos e a mais são
 *       recusados, e a mais nem chega a existir, pela regra anterior.</li>
 *   <li>Venda sem item não conclui: venda de nada não é venda. Venda com item de preço zero e
 *       total zero conclui sem parcela nenhuma, porque algo foi vendido e a conta fecha.</li>
 * </ul>
 *
 * <p><strong>O status e o troco de cada parcela chegam prontos</strong>, decididos pelo módulo de
 * pagamentos, que é quem sabe como cada forma é paga. A raiz registra o que ficou decidido e
 * guarda a conta; o troco não é persistido e volta a quem chamou.
 *
 * <p><strong>Parcela lançada não se desfaz.</strong> Não existe operação que remova uma parcela:
 * um valor lançado errado se corrige cancelando a venda, que é o único jeito de uma venda com
 * parcela errada deixar de estar ABERTA, porque a conclusão exige igualdade.
 *
 * <h2>O cancelamento</h2>
 *
 * <p>{@link #cancelar} é a única outra transição de estado, e serve às duas situações do balcão:
 * abandonar uma comanda ABERTA, com ou sem parcela lançada, e desfazer uma venda CONCLUIDA
 * (RF12). CANCELADA é estado final: não se cancela de novo, não se monta, não se paga, não se
 * conclui. Nada mais é conferido aqui: o que o cancelamento desfaz fora do agregado, o dinheiro
 * na gaveta e o estoque, é anunciado pelo caso de uso, e só quando a venda estava CONCLUIDA,
 * porque só a conclusão tinha produzido efeito fora do módulo.
 *
 * <p><strong>As parcelas ficam como estão.</strong> Uma parcela CONFIRMADO de uma venda cancelada
 * continua CONFIRMADO: ela foi paga de fato, e o status da venda é o fato novo. A devolução do
 * valor ao cliente acontece no balcão; o sistema não registra estorno de Pix nem de cartão,
 * porque as duas formas são lançadas à mão e não há provedor a quem pedir.
 *
 * <p><strong>Vincular cliente ainda não existe em código.</strong> A venda nasce sem cliente e o
 * vínculo chega como operação própria, porque numa comanda o cliente costuma ser identificado
 * depois do primeiro item, e às vezes só na hora de pagar.
 */
public class Venda {

    private final UUID id;
    private final UUID sessaoCaixaId;
    private final UUID usuarioId;
    private final UUID clienteId;
    private final Instant criadoEm;

    /** Só {@link #concluir} e {@link #cancelar} o mudam, e CANCELADA é final. */
    private StatusVenda status;

    /** Invariante viva, descrita no javadoc da classe. Nunca escrita de fora. */
    private Money valorTotal;

    /** Desconto sobre o total (RF08), além do de cada item. Só {@link #aplicarDesconto} o muda. */
    private Money valorDesconto;

    private final List<ItemVenda> itens;
    private final List<Pagamento> pagamentos;

    /**
     * Abre uma comanda (RF07): nasce ABERTA, vazia, com total e desconto zero e sem cliente.
     *
     * @param sessaoCaixaId a sessão de caixa em que a venda é feita; referência entre agregados,
     *                      sempre por id. Se ela está ABERTA e é desta conta, quem confere é o caso
     *                      de uso, porque a raiz não enxerga o caixa
     * @param usuarioId     o operador; referência entre agregados, sempre por id
     */
    public Venda(UUID sessaoCaixaId, UUID usuarioId) {
        this.id = UUID.randomUUID();
        this.sessaoCaixaId = Objects.requireNonNull(sessaoCaixaId,
                "sessaoCaixaId nao pode ser nulo");
        this.usuarioId = Objects.requireNonNull(usuarioId, "usuarioId nao pode ser nulo");
        this.clienteId = null;
        this.criadoEm = Instant.now();
        this.status = StatusVenda.ABERTA;
        this.valorTotal = Money.ZERO;
        this.valorDesconto = Money.ZERO;
        this.itens = new ArrayList<>();
        this.pagamentos = new ArrayList<>();
    }

    private Venda(UUID id, UUID sessaoCaixaId, UUID usuarioId, UUID clienteId, StatusVenda status,
            Money valorTotal, Money valorDesconto, Instant criadoEm, List<ItemVenda> itens,
            List<Pagamento> pagamentos) {
        this.id = id;
        this.sessaoCaixaId = sessaoCaixaId;
        this.usuarioId = usuarioId;
        this.clienteId = clienteId;
        this.status = status;
        this.valorTotal = valorTotal;
        this.valorDesconto = valorDesconto;
        this.criadoEm = criadoEm;
        this.itens = new ArrayList<>(itens);
        this.pagamentos = new ArrayList<>(pagamentos);
    }

    /**
     * Remonta uma venda que já existe no banco, preservando identidade e estado.
     *
     * <p>Existe para {@code VendaEntity}, e não é caminho de montagem: não passa pelas regras de
     * {@link #adicionarItem} nem de {@link #aplicarDesconto}. Pelo mesmo motivo de
     * {@code SessaoCaixa.reconstituir}, não recalcula o total a partir dos itens: recalcular
     * mascararia uma linha divergente. O que ele faz é <strong>conferir e recusar</strong>: as duas
     * invariantes da classe são checadas aqui, e um estado que as viole estoura em vez de virar
     * agregado. É o único jeito de o defeito aparecer alto, e não como um total errado que ninguém
     * confere.
     *
     * <p>Custo aceito: uma linha divergente no banco, gravada por script ou por defeito, deixa de
     * ser legível pelo domínio até ser corrigida. Projeções de relatório, que leem colunas e não
     * remontam o agregado, continuam funcionando.
     *
     * <p>As listas recebidas são copiadas, para que quem chamou não consiga alterar o agregado
     * por fora depois de montá-lo.
     *
     * @param clienteId nulo quando a venda não tem cliente identificado (RF03)
     * @throws IllegalStateException se {@code valorTotal} não é a soma dos subtotais menos o
     *                               desconto, ou se a venda está CONCLUIDA com a soma dos
     *                               pagamentos CONFIRMADO diferente do total
     */
    public static Venda reconstituir(UUID id, UUID sessaoCaixaId, UUID usuarioId, UUID clienteId,
            StatusVenda status, Money valorTotal, Money valorDesconto, Instant criadoEm,
            List<ItemVenda> itens, List<Pagamento> pagamentos) {
        Venda venda = new Venda(id, sessaoCaixaId, usuarioId, clienteId, status, valorTotal,
                valorDesconto, criadoEm, itens, pagamentos);

        Money totalPelosItens = venda.somaDosItens().subtrair(venda.valorDesconto);
        if (!venda.valorTotal.equals(totalPelosItens)) {
            throw new IllegalStateException(
                    "venda " + id + " tem valorTotal " + venda.valorTotal + ", mas os itens somam "
                            + venda.somaDosItens() + " menos desconto " + venda.valorDesconto
                            + " = " + totalPelosItens + ". O estado gravado viola a invariante"
                            + " do total e nao pode ser remontado.");
        }
        if (venda.status == StatusVenda.CONCLUIDA) {
            Money confirmados = venda.somaDosConfirmados();
            if (!confirmados.equals(venda.valorTotal)) {
                throw new IllegalStateException(
                        "venda " + id + " esta CONCLUIDA com " + confirmados + " em pagamentos"
                                + " confirmados para um total de " + venda.valorTotal
                                + ". O estado gravado viola a invariante da conclusao e nao"
                                + " pode ser remontado.");
            }
        }

        return venda;
    }

    /**
     * Lança um item na comanda (RF07) e mantém o total em dia, na mesma chamada.
     *
     * @param produtoId     referência entre agregados, sempre por id
     * @param quantidade    positiva, com no máximo três casas
     * @param precoUnitario o preço vigente do produto, copiado por quem chama; zero vale
     * @param desconto      desconto deste item (RF08); {@code Money.ZERO} quando não há, nunca
     *                      nulo, e nunca maior que quantidade vezes preço
     * @return o id do item criado, para que quem lançou consiga removê-lo depois
     * @throws IllegalArgumentException se alguma guarda de {@link ItemVenda} falha, ou se o
     *                                  desconto passa do valor bruto do item
     * @throws IllegalStateException    se a venda não está ABERTA
     */
    public UUID adicionarItem(UUID produtoId, BigDecimal quantidade, Money precoUnitario,
            Money desconto) {
        exigirAberta();

        ItemVenda item = ItemVenda.novo(produtoId, quantidade, precoUnitario, desconto);

        if (item.subtotal().isNegativo()) {
            // A conta é feita antes de anexar, para que um item recusado não deixe rastro. Igual
            // ao bruto passa: o item vira cortesia e vale zero.
            throw new IllegalArgumentException(
                    "desconto de " + item.desconto() + " e maior que o valor do item, "
                            + item.valorBruto() + ". Brinde e preco zero, nao desconto acima do"
                            + " valor.");
        }

        itens.add(item);
        recalcularTotal();
        return item.id();
    }

    /**
     * Tira um item da comanda e mantém o total em dia. É também o caminho para corrigir um item,
     * já que item lançado não se edita: remove e lança de novo.
     *
     * @throws IllegalArgumentException se o item não está nesta venda, se removê-lo deixaria o
     *                                  desconto da venda maior que a soma dos itens restantes, ou
     *                                  se deixaria o total abaixo do que já foi pago
     * @throws IllegalStateException    se a venda não está ABERTA
     */
    public void removerItem(UUID itemId) {
        exigirAberta();
        Objects.requireNonNull(itemId, "itemId nao pode ser nulo");

        ItemVenda item = itens.stream()
                .filter(candidato -> candidato.id().equals(itemId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "item " + itemId + " nao esta na venda " + id));

        // Mesma regra do desconto da venda, olhada pelo outro lado: sem este item, a soma
        // restante ainda cobre o desconto já aplicado? Conferido antes de remover, para que uma
        // recusa não deixe rastro.
        Money somaSemOItem = somaDosItens().subtrair(item.subtotal());
        Money totalSemOItem = somaSemOItem.subtrair(valorDesconto);
        if (totalSemOItem.isNegativo()) {
            throw new IllegalArgumentException(
                    "remover o item deixaria o desconto da venda, " + valorDesconto
                            + ", maior que a soma dos itens restantes, " + somaSemOItem
                            + ". Reduza o desconto antes de remover o item.");
        }
        exigirTotalNaoAbaixoDoPago(totalSemOItem, "remover o item");

        itens.remove(item);
        recalcularTotal();
    }

    /**
     * Desconto sobre o total da venda (RF08), além do desconto de cada item.
     *
     * <p><strong>Substitui, não acumula.</strong> Aplicar 5,00 e depois 3,00 deixa 3,00, porque a
     * chamada descreve o desconto como ele fica, e não um acréscimo sobre o anterior. Para tirar
     * o desconto, aplica-se zero.
     *
     * @param desconto zero vale, negativo não, e nunca maior que a soma dos itens
     * @throws IllegalArgumentException se o desconto é negativo, passa da soma dos itens, ou
     *                                  deixaria o total abaixo do que já foi pago
     * @throws IllegalStateException    se a venda não está ABERTA
     */
    public void aplicarDesconto(Money desconto) {
        exigirAberta();
        Objects.requireNonNull(desconto, "desconto nao pode ser nulo; use Money.ZERO para tirar");

        if (desconto.isNegativo()) {
            // Desconto negativo seria acréscimo, e nenhum requisito pede acréscimo.
            throw new IllegalArgumentException(
                    "desconto da venda nao pode ser negativo: " + desconto);
        }

        Money somaDosItens = somaDosItens();
        Money totalComODesconto = somaDosItens.subtrair(desconto);
        if (totalComODesconto.isNegativo()) {
            throw new IllegalArgumentException(
                    "desconto de " + desconto + " e maior que a soma dos itens, " + somaDosItens
                            + ". O total da venda nao fica negativo.");
        }
        exigirTotalNaoAbaixoDoPago(totalComODesconto, "aplicar o desconto");

        this.valorDesconto = desconto;
        recalcularTotal();
    }

    /**
     * Lança uma parcela do pagamento (RF09). Não muda o status: quem fecha a venda é
     * {@link #concluir}, mesmo quando esta parcela completa a conta.
     *
     * <p>Forma, valor e status chegam prontos do módulo de pagamentos, que é quem sabe se a
     * parcela nasce confirmada ou espera um provedor, e quanto volta de troco. Aqui só se confere
     * que a parcela cabe no que falta pagar, e se anexa.
     *
     * @param status o estado em que a parcela nasceu; PENDENTE reserva lugar na conta, RECUSADO
     *               não
     * @throws IllegalArgumentException se o valor passa do que falta pagar
     * @throws IllegalStateException    se a venda não está ABERTA
     */
    public void registrarPagamento(FormaPagamento forma, Money valor, StatusPagamento status) {
        exigirAberta();
        Objects.requireNonNull(forma, "forma de pagamento nao pode ser nula");
        Objects.requireNonNull(valor, "valor do pagamento nao pode ser nulo");
        Objects.requireNonNull(status, "status do pagamento nao pode ser nulo");

        // Conferido antes de anexar, para que uma parcela recusada não deixe rastro. Igual ao
        // saldo passa: é a parcela que fecha a conta.
        Money saldo = saldoAPagar();
        if (saldo.subtrair(valor).isNegativo()) {
            throw new IllegalArgumentException(
                    "parcela de " + valor + " e maior que o que falta pagar, " + saldo
                            + ". O total da venda e " + valorTotal + " e ja ha "
                            + somaDasParcelasLancadas() + " em parcelas lancadas.");
        }

        pagamentos.add(Pagamento.novo(forma, valor, status));
    }

    /**
     * Fecha a venda (RF09): confere que os pagamentos confirmados cobrem exatamente o total e vira
     * o status para CONCLUIDA. É a única transição para CONCLUIDA que existe em código.
     *
     * <p>Passo à parte de {@link #registrarPagamento} de propósito: a tela chama isto quando o
     * operador finaliza, e é o caso de uso que, depois de gravar o resultado, publica o evento que
     * avisa o caixa e o estoque. A raiz não faz nada fora do agregado, e não confere o estado do
     * caixa: ela não o enxerga, e quem pergunta é o caso de uso.
     *
     * @throws IllegalStateException se a venda não está ABERTA, não tem item, ou a soma dos
     *                               pagamentos CONFIRMADO é diferente do total
     */
    public void concluir() {
        if (status != StatusVenda.ABERTA) {
            throw new IllegalStateException(
                    "venda " + id + " esta " + status + " e nao conclui de novo."
                            + " So venda ABERTA conclui.");
        }
        if (itens.isEmpty()) {
            // Venda de nada não é venda, como item de quantidade zero não é item. Sem esta guarda,
            // uma comanda aberta por engano viraria venda concluída vazia no histórico.
            throw new IllegalStateException(
                    "venda " + id + " nao tem item nenhum e nao conclui. Venda de nada nao e"
                            + " venda.");
        }

        Money confirmados = somaDosConfirmados();
        if (!confirmados.equals(valorTotal)) {
            throw new IllegalStateException(
                    "venda " + id + " tem " + confirmados + " em pagamentos confirmados para um"
                            + " total de " + valorTotal + "; faltam "
                            + valorTotal.subtrair(confirmados) + ". A venda so conclui quando os"
                            + " pagamentos confirmados cobrem exatamente o total.");
        }

        this.status = StatusVenda.CONCLUIDA;
    }

    /**
     * Desfaz a venda (RF12): vira o status para CANCELADA, que é final. Vale para a comanda ABERTA
     * que o operador abandona e para a venda CONCLUIDA que o cliente devolve; a raiz não distingue
     * as duas, porque em ambas o que ela guarda é o mesmo: a venda deixa de valer.
     *
     * <p>Itens e parcelas ficam como estão, de propósito. Uma venda cancelada continua contando o
     * que tinha sido vendido e como tinha sido pago; é isso que permite ao caixa e ao estoque
     * desfazerem exatamente o que a conclusão fez. Quem anuncia o cancelamento para fora, e só
     * quando havia o que desfazer, é o caso de uso.
     *
     * <p>Não pergunta pelo caixa: a raiz não o enxerga. A regra de que uma venda CONCLUIDA só
     * cancela com a sessão em que nasceu ainda ABERTA mora no caso de uso, ao lado da regra
     * equivalente da conclusão.
     *
     * @throws IllegalStateException se a venda já está CANCELADA
     */
    public void cancelar() {
        if (status == StatusVenda.CANCELADA) {
            throw new IllegalStateException(
                    "venda " + id + " ja esta CANCELADA e nao cancela de novo.");
        }
        this.status = StatusVenda.CANCELADA;
    }

    /**
     * Reescreve o total a partir do estado atual. É o que faz a invariante da classe ser verdade
     * o tempo todo, e não apenas quando alguém lembra de recalcular. Quem chega aqui já passou
     * pela guarda de sinal do método que mudou o estado, então o resultado nunca é negativo.
     */
    private void recalcularTotal() {
        this.valorTotal = somaDosItens().subtrair(valorDesconto);
    }

    private Money somaDosItens() {
        return itens.stream().map(ItemVenda::subtotal).reduce(Money.ZERO, Money::somar);
    }

    /**
     * O que já ocupa lugar na conta: toda parcela que não está RECUSADO. A PENDENTE conta porque
     * espera um provedor confirmar o que já foi pedido; a RECUSADO é desfecho encerrado.
     */
    private Money somaDasParcelasLancadas() {
        return pagamentos.stream()
                .filter(parcela -> parcela.status() != StatusPagamento.RECUSADO)
                .map(Pagamento::valor)
                .reduce(Money.ZERO, Money::somar);
    }

    /** O que conta para a conclusão: só o que já está confirmado. */
    private Money somaDosConfirmados() {
        return pagamentos.stream()
                .filter(parcela -> parcela.status() == StatusPagamento.CONFIRMADO)
                .map(Pagamento::valor)
                .reduce(Money.ZERO, Money::somar);
    }

    private Money saldoAPagar() {
        return valorTotal.subtrair(somaDasParcelasLancadas());
    }

    /**
     * A regra do desconto, olhada pelo lado do pagamento: nenhuma operação de montagem deixa o
     * total abaixo do que já foi pago, porque a conta deixaria de fechar e parcela lançada não se
     * desfaz. Chamada com o total que a operação produziria, antes de a operação acontecer.
     */
    private void exigirTotalNaoAbaixoDoPago(Money totalResultante, String operacao) {
        Money pago = somaDasParcelasLancadas();
        if (totalResultante.subtrair(pago).isNegativo()) {
            throw new IllegalArgumentException(
                    operacao + " deixaria o total da venda em " + totalResultante
                            + ", abaixo dos " + pago + " ja lancados em pagamento."
                            + " Parcela lancada nao se desfaz.");
        }
    }

    private void exigirAberta() {
        if (status != StatusVenda.ABERTA) {
            throw new IllegalStateException(
                    "venda " + id + " esta " + status + " e nao aceita montagem."
                            + " Item e desconto so mudam enquanto a venda esta ABERTA.");
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getSessaoCaixaId() {
        return sessaoCaixaId;
    }

    public UUID getUsuarioId() {
        return usuarioId;
    }

    /** Nulo quando a venda não tem cliente identificado (RF03). */
    public UUID getClienteId() {
        return clienteId;
    }

    public StatusVenda getStatus() {
        return status;
    }

    public Money getValorTotal() {
        return valorTotal;
    }

    /** Desconto sobre o total da venda (RF08), além do desconto de cada item. Zero quando não há. */
    public Money getValorDesconto() {
        return valorDesconto;
    }

    public Instant getCriadoEm() {
        return criadoEm;
    }

    /** Cópia imutável: item só entra pela raiz, nunca por quem leu a lista. */
    public List<ItemVenda> getItens() {
        return Collections.unmodifiableList(itens);
    }

    /** Cópia imutável: pagamento só entra pela raiz, nunca por quem leu a lista. */
    public List<Pagamento> getPagamentos() {
        return Collections.unmodifiableList(pagamentos);
    }
}
