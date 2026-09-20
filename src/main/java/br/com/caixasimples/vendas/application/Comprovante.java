package br.com.caixasimples.vendas.application;

import br.com.caixasimples.pagamentos.FormaPagamento;
import br.com.caixasimples.shared.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * O comprovante não-fiscal de uma venda concluída (RF11): o que a tela imprime ou compartilha.
 *
 * <p><strong>É dado, não desenho.</strong> Quem monta a página, imprime e compartilha é a tela,
 * porque ela precisa fazer isso também sem internet (RNF01), e um comprovante desenhado no
 * servidor não chegaria ao balcão sem conexão. Por isso não há PDF nem HTML aqui, e não há
 * dependência de biblioteca de impressão.
 *
 * <p><strong>Não é documento fiscal</strong>, e não deve se parecer com um. Não tem chave de
 * acesso, número de série nem tributo. A emissão fiscal é outro assunto, fora deste comprovante.
 *
 * <p><strong>Traz só o que é da venda.</strong> Nome do negócio e nome do operador não estão
 * aqui: a tela já sabe quem está logado e os põe no cabeçalho. O operador vai por id, como em
 * toda referência entre agregados.
 *
 * <p><strong>Cada linha vem calculada.</strong> Bruto e subtotal são os que o domínio calcula,
 * com o arredondamento por item, para que a tela não repita a conta e cada linha impressa feche
 * com o total. A soma dos itens, o desconto da venda e o total vêm da mesma fonte. Só entram
 * parcelas confirmadas, porque o comprovante diz como a venda foi paga.
 *
 * <p>O instante vai cru, em UTC. Quem o mostra formata no fuso do balcão.
 *
 * @param vendaId         a venda, para reimpressão e para a tela mostrar o identificador como
 *                        preferir
 * @param usuarioId       o operador que fez a venda, por id
 * @param concluidoEm     quando os pagamentos fecharam a conta; nunca nulo, porque só venda
 *                        CONCLUIDA tem comprovante
 * @param linhas          os itens, na ordem em que entraram na comanda
 * @param somaDosItens    a soma dos subtotais das linhas, antes do desconto da venda
 * @param descontoDaVenda o desconto sobre o total (RF08); zero quando não há
 * @param valorTotal      o que a venda custou: a soma dos itens menos o desconto da venda
 * @param parcelas        como a venda foi paga (RF09): só as parcelas confirmadas
 * @param troco           o que voltou para o cliente, somando todas as parcelas; zero quando
 *                        não houve dinheiro ou o cliente pagou exato
 */
public record Comprovante(UUID vendaId, UUID usuarioId, Instant concluidoEm, List<Linha> linhas,
        Money somaDosItens, Money descontoDaVenda, Money valorTotal, List<Parcela> parcelas,
        Money troco) {

    /**
     * Uma linha do comprovante: um item da venda com o nome que o produto tem hoje.
     *
     * @param produtoId     referência ao produto, por id
     * @param nome          o nome de hoje, não o da época da venda: o item guarda o preço copiado,
     *                      mas não o nome, e um produto renomeado sai com o nome novo
     * @param unidade       a unidade cadastrada no produto; nula quando não há
     * @param quantidade    a quantidade vendida, com até três casas
     * @param precoUnitario o preço copiado na hora da venda
     * @param valorBruto    quantidade vezes preço, arredondado nesta linha
     * @param desconto      o desconto deste item (RF08); zero quando não há
     * @param subtotal      o bruto menos o desconto
     */
    public record Linha(UUID produtoId, String nome, String unidade, BigDecimal quantidade,
            Money precoUnitario, Money valorBruto, Money desconto, Money subtotal) {
    }

    /**
     * Uma parcela confirmada, numa forma só.
     *
     * @param forma como esta parte foi paga
     * @param valor o valor da parcela, não o total da venda
     * @param troco o que voltou para o cliente nesta parcela; zero fora de dinheiro
     */
    public record Parcela(FormaPagamento forma, Money valor, Money troco) {
    }
}
