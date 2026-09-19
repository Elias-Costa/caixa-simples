package br.com.caixasimples.vendas.domain;

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
 * campo, não somar a lista.
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
 * </ul>
 *
 * <p><strong>O preço unitário chega pronto</strong>, copiado do produto por quem monta a venda.
 * A raiz não conhece o cadastro e não teria como consultá-lo; o que ela garante é que o valor
 * recebido é o que fica gravado, mesmo que o produto seja reajustado depois.
 *
 * <p><strong>Vincular cliente ainda não existe em código.</strong> A venda nasce sem cliente e o
 * vínculo chega como operação própria, porque numa comanda o cliente costuma ser identificado
 * depois do primeiro item, e às vezes só na hora de pagar.
 *
 * <p><strong>Conclusão e cancelamento tampouco existem ainda.</strong> Chegam com os casos de uso
 * que registram pagamento e estornam a venda; até lá nenhuma venda muda de estado.
 */
public class Venda {

    private final UUID id;
    private final UUID sessaoCaixaId;
    private final UUID usuarioId;
    private final UUID clienteId;
    private final Instant criadoEm;

    /** Muda com a conclusão e com o cancelamento, que ainda não existem em código. */
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
     * {@code SessaoCaixa.reconstituir}, não recalcula o total a partir dos itens: o que está
     * gravado já passou pela montagem e pelos CHECK da migration, e recalcular aqui mascararia uma
     * linha divergente em vez de deixar o defeito aparecer.
     *
     * <p>As listas recebidas são copiadas, para que quem chamou não consiga alterar o agregado
     * por fora depois de montá-lo.
     *
     * @param clienteId nulo quando a venda não tem cliente identificado (RF03)
     */
    public static Venda reconstituir(UUID id, UUID sessaoCaixaId, UUID usuarioId, UUID clienteId,
            StatusVenda status, Money valorTotal, Money valorDesconto, Instant criadoEm,
            List<ItemVenda> itens, List<Pagamento> pagamentos) {
        return new Venda(id, sessaoCaixaId, usuarioId, clienteId, status, valorTotal,
                valorDesconto, criadoEm, itens, pagamentos);
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
     * @throws IllegalArgumentException se o item não está nesta venda, ou se removê-lo deixaria o
     *                                  desconto da venda maior que a soma dos itens restantes
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
        if (somaSemOItem.subtrair(valorDesconto).isNegativo()) {
            throw new IllegalArgumentException(
                    "remover o item deixaria o desconto da venda, " + valorDesconto
                            + ", maior que a soma dos itens restantes, " + somaSemOItem
                            + ". Reduza o desconto antes de remover o item.");
        }

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
     * @throws IllegalArgumentException se o desconto é negativo ou passa da soma dos itens
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
        if (somaDosItens.subtrair(desconto).isNegativo()) {
            throw new IllegalArgumentException(
                    "desconto de " + desconto + " e maior que a soma dos itens, " + somaDosItens
                            + ". O total da venda nao fica negativo.");
        }

        this.valorDesconto = desconto;
        recalcularTotal();
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
