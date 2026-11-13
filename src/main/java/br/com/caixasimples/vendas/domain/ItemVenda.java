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
 * <p>As guardas do construtor espelham as restrições da coluna: sinal, nulo, a escala e o tamanho
 * da quantidade. Que o desconto não passe do valor bruto é regra de {@link Venda#adicionarItem}, e
 * fica lá de propósito: uma linha gravada por fora do código, sem essa regra, continua legível
 * quando remontada do banco, e o defeito aparece no total em vez de derrubar a leitura.
 *
 * @param id            gerado na aplicação e nunca pelo banco, para que o registro tenha
 *                      identidade definitiva mesmo criado sem conexão (RNF01)
 * @param produtoId     referência entre agregados, então é um {@link UUID} e nunca um objeto
 *                      navegável
 * @param quantidade    sempre positiva e abaixo de um bilhão, com no máximo três casas, porque
 *                      venda fracionada é real (0,750 kg) e é isso que a coluna guarda
 * @param precoUnitario cópia do preço do produto no momento da venda; zero vale, negativo não
 * @param desconto      desconto deste item (RF08); zero quando não há, nunca nulo
 * @param criadoEm      momento em que o item entrou na comanda, em UTC; é o que dá ordem de
 *                      leitura aos itens
 */
public record ItemVenda(UUID id, UUID produtoId, BigDecimal quantidade, Money precoUnitario,
        Money desconto, Instant criadoEm) {

    /** Três casas, porque é o que a coluna {@code numeric(12,3)} guarda. */
    public static final int CASAS_DA_QUANTIDADE = 3;

    /**
     * A partir daqui a quantidade não cabe na coluna {@code numeric(12,3)}, que guarda nove algarismos
     * antes da vírgula. Sem esta guarda, o gesto do lote levaria o valor até o banco.
     */
    public static final BigDecimal QUANTIDADE_ACIMA_DO_LIMITE = new BigDecimal("1000000000");

    /** A unidade do produto vendido por peça, cuja quantidade não tem fração. */
    public static final String UNIDADE_INTEIRA = "un";

    public ItemVenda {
        Objects.requireNonNull(id, "id nao pode ser nulo");
        Objects.requireNonNull(produtoId, "produtoId nao pode ser nulo");
        Objects.requireNonNull(quantidade, "quantidade nao pode ser nula");
        Objects.requireNonNull(precoUnitario, "precoUnitario nao pode ser nulo");
        Objects.requireNonNull(desconto, "desconto nao pode ser nulo; use Money.ZERO quando nao ha");
        Objects.requireNonNull(criadoEm, "criadoEm nao pode ser nulo");

        // As três recusas da quantidade chegam ao balcão como vieram, com rede e na revisão do lote, e
        // o aparelho sem rede repete o mesmo texto: por isso são frases de tela, que dizem a saída.
        if (quantidade.signum() <= 0) {
            // Item com quantidade zero não é item: nada foi vendido. Negativa seria devolução, e
            // devolução não é um item de venda com sinal trocado.
            throw new IllegalArgumentException("Informe uma quantidade maior que zero.");
        }
        if (quantidade.stripTrailingZeros().scale() > CASAS_DA_QUANTIDADE) {
            // O banco arredondaria a quarta casa em silêncio, e o total calculado aqui deixaria de
            // bater com a quantidade gravada. Mesma postura de Money, que recusa fração de centavo
            // em vez de arredondar escondido. Zeros à direita não contam: 2,0000 é 2.
            throw new IllegalArgumentException("Informe uma quantidade com até três casas decimais.");
        }
        if (quantidade.compareTo(QUANTIDADE_ACIMA_DO_LIMITE) >= 0) {
            throw new IllegalArgumentException("Informe uma quantidade menor que um bilhão.");
        }
        if (precoUnitario.isNegativo()) {
            throw new IllegalArgumentException("O preço unitário não pode ser negativo.");
        }
        if (desconto.isNegativo()) {
            // Desconto negativo seria acréscimo, e nenhum requisito pede acréscimo.
            throw new IllegalArgumentException("O desconto do item não pode ser negativo.");
        }
    }

    /**
     * Item novo, criado pela raiz.
     *
     * <p>Visibilidade de pacote, para que só a raiz crie item: é ela que confere o desconto contra
     * o bruto e mantém o total em dia. A identidade e o instante chegam dela: gerados na hora
     * quando o item é lançado no servidor, ou os do balcão quando ele foi lançado no dispositivo
     * sem rede, com o id que os gestos seguintes daquele dispositivo já usam (RNF01, RNF03).
     */
    static ItemVenda novo(UUID id, UUID produtoId, BigDecimal quantidade, Money precoUnitario,
            Money desconto, Instant criadoEm) {
        return new ItemVenda(id, produtoId, quantidade, precoUnitario, desconto, criadoEm);
    }

    /**
     * Recusa a quantidade com fração do produto vendido por {@code un} ou sem unidade.
     *
     * <p>A unidade é texto livre no cadastro, e só essas duas formas dizem com certeza que o produto
     * é vendido por peça: {@code un} é a do catálogo inicial inteiro, e o produto sem unidade é
     * vendido inteiro. Uma lista do que se fraciona erraria toda unidade escrita de outro jeito,
     * então qualquer outra grafia, como {@code unid} ou {@code kg}, continua com três casas.
     * Maiúscula e espaço nas pontas não contam.
     *
     * <p>Mora aqui, ao lado das outras regras da quantidade, mas quem chama é o caso de uso de
     * montagem, que é onde o produto é consultado: o item não guarda a unidade. A regra vale para o
     * item novo; o item já gravado não volta a ser conferido.
     *
     * @param nomeDoProduto o nome que a recusa cita
     * @param unidade       a unidade do cadastro, nula quando não há
     * @param quantidade    nula, zero ou negativa passa, porque a guarda do construtor a recusa com a
     *                      frase dela: -1,5 em un é quantidade que não vale, não fração
     * @throws IllegalArgumentException se a unidade é {@code un} ou vazia e a quantidade tem fração
     */
    public static void exigirInteiraNaUnidade(String nomeDoProduto, String unidade,
            BigDecimal quantidade) {
        if (quantidade == null || quantidade.signum() <= 0) {
            return;
        }
        String aparada = unidade == null ? "" : unidade.trim();
        boolean vendidoPorPeca = aparada.isEmpty() || aparada.equalsIgnoreCase(UNIDADE_INTEIRA);
        boolean temFracao = quantidade.remainder(BigDecimal.ONE).signum() != 0;
        if (vendidoPorPeca && temFracao) {
            throw new IllegalArgumentException("O produto " + nomeDoProduto
                    + " é vendido por unidade e só aceita quantidade inteira.");
        }
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
