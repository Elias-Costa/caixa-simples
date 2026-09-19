package br.com.caixasimples.vendas.domain;

import br.com.caixasimples.shared.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Um produto ou serviço dentro de uma venda.
 *
 * <p><strong>Membro do agregado Venda</strong>, nunca raiz. Não tem repositório e não se altera
 * sozinho: nasce dentro de {@link Venda}, por {@link Venda#adicionarItem}, e some com ela, por
 * {@link Venda#removerItem}. É um {@code record} porque não há nada para alterar depois: item
 * lançado não se edita, nem a quantidade nem o desconto; o que se faz é remover e lançar de novo.
 *
 * <p><strong>Não importa framework</strong>, pelo mesmo motivo dos outros agregados. O mapeamento
 * para o banco vive em {@code vendas.internal}.
 *
 * <p>O {@link #precoUnitario} é <strong>cópia</strong> do preço do produto no momento da venda,
 * nunca leitura viva: reajustar o produto depois não pode alterar o valor de uma venda passada.
 * Quem faz a cópia é o caso de uso de montagem, que é onde o produto é consultado; aqui o valor
 * apenas chega pronto.
 *
 * <h2>Quanto vale o item</h2>
 *
 * <p>{@link #valorBruto()} é quantidade vezes preço unitário, arredondado para centavos
 * <em>neste item</em>, e {@link #subtotal()} é o bruto menos o desconto. O arredondamento é por
 * item, e não no total da venda, para que cada linha do comprovante feche com o total impresso;
 * o custo é acumular arredondamento quando há muitos itens fracionados, e ele foi aceito.
 *
 * <h2>O que este record valida, e o que deixa para a raiz</h2>
 *
 * <p>As guardas do construtor espelham as restrições da coluna: sinal, nulo e a escala da
 * quantidade. Que o desconto não passe do valor bruto é regra de {@link Venda#adicionarItem}, e
 * fica lá de propósito: uma linha gravada por fora do código, sem essa regra, continua legível
 * quando remontada do banco, e o defeito aparece no total em vez de derrubar a leitura.
 *
 * @param id            gerado na aplicação e nunca pelo banco, para que o registro tenha
 *                      identidade definitiva mesmo criado sem conexão (RNF01)
 * @param produtoId     referência entre agregados, então é um {@link UUID} e nunca um objeto
 *                      navegável
 * @param quantidade    sempre positiva, com no máximo três casas, porque venda fracionada é real
 *                      (0,750 kg) e é isso que a coluna guarda
 * @param precoUnitario cópia do preço do produto no momento da venda; zero vale, negativo não
 * @param desconto      desconto deste item (RF08); zero quando não há, nunca nulo
 * @param criadoEm      momento em que o item entrou na comanda, em UTC; é o que dá ordem de
 *                      leitura aos itens
 */
public record ItemVenda(UUID id, UUID produtoId, BigDecimal quantidade, Money precoUnitario,
        Money desconto, Instant criadoEm) {

    /** Três casas, porque é o que a coluna {@code numeric(12,3)} guarda. */
    public static final int CASAS_DA_QUANTIDADE = 3;

    public ItemVenda {
        Objects.requireNonNull(id, "id nao pode ser nulo");
        Objects.requireNonNull(produtoId, "produtoId nao pode ser nulo");
        Objects.requireNonNull(quantidade, "quantidade nao pode ser nula");
        Objects.requireNonNull(precoUnitario, "precoUnitario nao pode ser nulo");
        Objects.requireNonNull(desconto, "desconto nao pode ser nulo; use Money.ZERO quando nao ha");
        Objects.requireNonNull(criadoEm, "criadoEm nao pode ser nulo");

        if (quantidade.signum() <= 0) {
            // Item com quantidade zero não é item: nada foi vendido. Negativa seria devolução, e
            // devolução não é um item de venda com sinal trocado.
            throw new IllegalArgumentException(
                    "quantidade do item tem de ser positiva: " + quantidade);
        }
        if (quantidade.stripTrailingZeros().scale() > CASAS_DA_QUANTIDADE) {
            // O banco arredondaria a quarta casa em silêncio, e o total calculado aqui deixaria de
            // bater com a quantidade gravada. Mesma postura de Money, que recusa fração de centavo
            // em vez de arredondar escondido. Zeros à direita não contam: 2,0000 é 2.
            throw new IllegalArgumentException(
                    "quantidade do item tem no maximo " + CASAS_DA_QUANTIDADE
                            + " casas decimais: " + quantidade);
        }
        if (precoUnitario.isNegativo()) {
            throw new IllegalArgumentException(
                    "preco unitario nao pode ser negativo: " + precoUnitario);
        }
        if (desconto.isNegativo()) {
            // Desconto negativo seria acréscimo, e nenhum requisito pede acréscimo.
            throw new IllegalArgumentException(
                    "desconto do item nao pode ser negativo: " + desconto);
        }
    }

    /**
     * Item novo: identidade e momento nascem aqui, como em todo registro do sistema (RNF01).
     *
     * <p>Visibilidade de pacote, para que só a raiz crie item: é ela que confere o desconto contra
     * o bruto e mantém o total em dia.
     */
    static ItemVenda novo(UUID produtoId, BigDecimal quantidade, Money precoUnitario,
            Money desconto) {
        return new ItemVenda(UUID.randomUUID(), produtoId, quantidade, precoUnitario, desconto,
                Instant.now());
    }

    /** Quantidade vezes preço unitário, já arredondado para centavos neste item. */
    public Money valorBruto() {
        return precoUnitario.multiplicarArredondando(quantidade);
    }

    /**
     * O que o item vale na venda: {@link #valorBruto()} menos o {@link #desconto()}.
     *
     * <p>Pode sair negativo apenas numa linha gravada por fora do código, porque a raiz recusa
     * desconto acima do bruto antes de o item existir. Não há guarda aqui, pelo motivo dado no
     * javadoc da classe.
     */
    public Money subtotal() {
        return valorBruto().subtrair(desconto);
    }
}
