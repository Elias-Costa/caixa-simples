package br.com.caixasimples.relatorios.application;

import br.com.caixasimples.relatorios.internal.ItemVendaParaRelatorioRepository;
import br.com.caixasimples.relatorios.internal.ProdutoVendido;
import br.com.caixasimples.shared.Money;
import br.com.caixasimples.vendas.StatusVenda;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Os produtos e serviços mais vendidos num período (RF22).
 *
 * <p><strong>Mais vendido é o que mais saiu, em quantidade</strong>, e não o que mais rendeu. É o
 * que a expressão diz no balcão: o café que vendeu cem vezes é o mais vendido, mesmo que o bolo,
 * que vendeu dez, tenha rendido mais. O valor vendido de cada produto sai ao lado, para a tela
 * mostrar as duas leituras sem uma segunda pergunta. O custo de somar quantidade é misturar
 * unidades, três quilos ao lado de três unidades, e por isso a unidade de cada produto vai junto.
 *
 * <p><strong>Contam as mesmas vendas do faturamento</strong>: só as CONCLUIDA, no dia em que
 * concluíram. Um ranking que incluísse a comanda ABERTA contaria o que ainda não foi vendido, e
 * um que incluísse a CANCELADA contaria o que foi devolvido; e os dois relatórios do mesmo período
 * precisam falar das mesmas vendas.
 *
 * <p><strong>Produto inativado continua no ranking</strong> do período em que foi vendido, com o
 * nome atual: o histórico aponta para ele, e o relatório é sobre o que aconteceu, não sobre o
 * catálogo de hoje.
 *
 * <p>A pedido, o ranking é o de um operador só (RF24): o que cada pessoa do balcão mais vendeu,
 * pelas mesmas vendas e no mesmo período.
 *
 * <p>O módulo só lê, e a conta vem sempre do contexto, nunca de parâmetro (RNF05).
 */
@Service
public class MaisVendidosService {

    private final ItemVendaParaRelatorioRepository itens;

    MaisVendidosService(ItemVendaParaRelatorioRepository itens) {
        this.itens = itens;
    }

    /**
     * Os produtos mais vendidos de um período de dias do balcão, <strong>com os dois extremos
     * incluídos</strong>, do mais vendido para o menos, no máximo {@code limite} deles, contando
     * as vendas de todos os operadores.
     *
     * @param inicio o primeiro dia, obrigatório
     * @param fim    o último dia, obrigatório; pode ser o mesmo que o primeiro
     * @param limite quantas posições no máximo, pelo menos uma; um ranking sem tamanho seria o
     *               catálogo inteiro ordenado, que ninguém pediu
     * @throws IllegalArgumentException se {@code fim} vem antes de {@code inicio}, ou se o limite
     *                                  não é positivo
     */
    @Transactional(readOnly = true)
    public MaisVendidos doPeriodo(LocalDate inicio, LocalDate fim, int limite) {
        return consultar(inicio, fim, limite, null);
    }

    /**
     * Os produtos mais vendidos de um período, como {@link #doPeriodo(LocalDate, LocalDate, int)},
     * contando <strong>só as vendas de um operador</strong> (RF24).
     *
     * <p>Duas assinaturas, e não um parâmetro que aceita nulo, porque o operador é o único filtro
     * do ranking além do período, e quem chama diz qual das duas perguntas está fazendo. Não há
     * filtro por forma de pagamento: um item não pertence a uma parcela, e o ranking não teria
     * como repartir uma venda dividida entre as formas.
     *
     * <p>Operador que não existe, ou que é de outra conta, dá lista vazia, e não erro: o filtro de
     * conta do mapeamento não devolve venda de operador de fora, e este módulo não pergunta ao
     * módulo de contas quem existe.
     *
     * @param inicio     o primeiro dia, obrigatório
     * @param fim        o último dia, obrigatório; pode ser o mesmo que o primeiro
     * @param limite     quantas posições no máximo, pelo menos uma
     * @param operadorId o operador cujas vendas contam, obrigatório nesta assinatura
     * @throws IllegalArgumentException se {@code fim} vem antes de {@code inicio}, ou se o limite
     *                                  não é positivo
     */
    @Transactional(readOnly = true)
    public MaisVendidos doPeriodo(LocalDate inicio, LocalDate fim, int limite, UUID operadorId) {
        Objects.requireNonNull(operadorId,
                "operadorId nao pode ser nulo; sem operador, use a assinatura sem ele");
        return consultar(inicio, fim, limite, operadorId);
    }

    /** O ranking em si; {@code operadorId} nulo é a conta inteira, e só as duas públicas chamam. */
    private MaisVendidos consultar(LocalDate inicio, LocalDate fim, int limite, UUID operadorId) {
        Periodo periodo = new Periodo(inicio, fim);
        if (limite < 1) {
            throw new IllegalArgumentException(
                    "limite do ranking tem de ser pelo menos 1: " + limite);
        }

        List<ProdutoVendido> linhas = itens.maisVendidosEntre(StatusVenda.CONCLUIDA,
                periodo.inicioInclusivo(), periodo.fimExclusivo(), operadorId, Limit.of(limite));

        List<Posicao> posicoes = linhas.stream()
                .map(linha -> new Posicao(linha.produtoId(), linha.nome(), linha.unidade(),
                        linha.quantidade(), Money.de(linha.valor())))
                .toList();
        return new MaisVendidos(inicio, fim, posicoes);
    }

    /**
     * A resposta do caso de uso: o período pedido e o ranking, já na ordem. Aninhada no serviço,
     * como {@code FaturamentoService.Faturamento}, por ser o formato de resposta deste caso de uso
     * e de mais nenhum.
     *
     * @param inicio   o primeiro dia do período, no fuso do balcão
     * @param fim      o último dia do período, incluído
     * @param posicoes do mais vendido para o menos; vazia quando nada foi vendido, nunca nula
     */
    public record MaisVendidos(LocalDate inicio, LocalDate fim, List<Posicao> posicoes) {
    }

    /**
     * Uma posição do ranking.
     *
     * @param produtoId  o produto, por id, como toda referência entre agregados
     * @param nome       o nome atual do produto
     * @param unidade    a unidade cadastrada, que dá sentido à quantidade; nula quando o cadastro
     *                   não a informou
     * @param quantidade a soma do que saiu no período, com três casas, porque venda fracionada é
     *                   real; não é dinheiro, e por isso não é {@code Money}
     * @param valor      a soma dos subtotais dos itens desse produto, com o mesmo arredondamento
     *                   por item do comprovante
     */
    public record Posicao(UUID produtoId, String nome, String unidade, BigDecimal quantidade,
            Money valor) {
    }
}
