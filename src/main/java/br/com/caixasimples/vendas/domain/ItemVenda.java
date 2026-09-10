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
 * sozinho: nasce dentro de {@link Venda} e some com ela. É um {@code record} porque não há nada
 * para alterar depois: item lançado não se edita, o que se faz é remover e lançar de novo.
 *
 * <p><strong>Não importa framework</strong>, pelo mesmo motivo dos outros agregados. O mapeamento
 * para o banco vive em {@code vendas.internal}.
 *
 * <p>O {@link #precoUnitario} é <strong>cópia</strong> do preço do produto no momento da venda,
 * nunca leitura viva: reajustar o produto depois não pode alterar o valor de uma venda passada.
 * Quem faz a cópia é a montagem da venda, que é onde o produto é consultado; aqui o valor apenas
 * chega pronto.
 *
 * <p>As guardas abaixo espelham os CHECK da migration V7 e nada além deles. Como o desconto entra
 * na conta do total, e se ele pode passar do valor do item, é regra da montagem da venda, que
 * ainda não existe em código.
 *
 * @param id            gerado na aplicação e nunca pelo banco, para que o registro tenha
 *                      identidade definitiva mesmo criado sem conexão (RNF01)
 * @param produtoId     referência entre agregados, então é um {@link UUID} e nunca um objeto
 *                      navegável
 * @param quantidade    sempre positiva; três casas, porque venda fracionada é real (0,750 kg)
 * @param precoUnitario cópia do preço do produto no momento da venda; zero vale, negativo não
 * @param desconto      desconto deste item (RF08); zero quando não há, nunca nulo
 * @param criadoEm      momento em que o item entrou na comanda, em UTC; é o que dá ordem de
 *                      leitura aos itens
 */
public record ItemVenda(UUID id, UUID produtoId, BigDecimal quantidade, Money precoUnitario,
        Money desconto, Instant criadoEm) {

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
}
