package br.com.caixasimples.pagamentos.domain;

import br.com.caixasimples.pagamentos.FormaPagamento;
import br.com.caixasimples.pagamentos.StatusPagamento;
import br.com.caixasimples.shared.Money;
import java.util.Objects;

/**
 * O que ficou registrado depois de uma tentativa de pagamento: a forma, o valor da parcela, o
 * estado em que ela nasceu e o troco a devolver.
 *
 * <p><strong>É aqui que mora a regra do troco</strong> (RF10), na fábrica {@link #emDinheiro}, e
 * não na estratégia que a chama. O motivo é o mesmo que faz {@code Money.arredondando} existir:
 * quem lê a chamada vê pelo nome qual decisão está sendo tomada, e a conta fica num tipo que não
 * conhece framework nenhum. A estratégia de dinheiro é um componente do Spring e, por isso, não
 * poderia guardar a regra sem levá-la junto para fora do domínio.
 *
 * <p>As três guardas do pagamento em dinheiro:
 *
 * <ul>
 *   <li>O valor recebido é <strong>obrigatório</strong>, mesmo quando o cliente paga certo. Nesse
 *       caso ele repete o valor da parcela e o troco sai zero. Deixar o nulo significar
 *       <em>pagou o valor exato</em> economizaria uma linha de quem chama e esconderia a regra num
 *       comentário.</li>
 *   <li>Receber <strong>menos</strong> que a parcela é recusado, pela mesma razão que uma sangria
 *       maior que a gaveta é recusada: a conta seguinte produziria um troco negativo, que não
 *       existe. Pagar menos em dinheiro se registra reduzindo o valor da parcela e completando o
 *       resto em outra forma (RF09), não entregando a menos.</li>
 *   <li>O status nasce CONFIRMADO. O dinheiro já está na gaveta no instante do registro.</li>
 * </ul>
 *
 * <p>O troco sai de valores já arredondados: cada item da venda arredonda o seu preço, e o total
 * é a soma desses valores. A diferença que volta ao cliente, portanto, não carrega fração de
 * centavo vinda de arredondamento adiado.
 *
 * <p><strong>Não tem identificador de transação do provedor</strong>, e a ausência é deliberada.
 * A entidade de pagamento tem essa coluna, mas nenhuma forma implementada até aqui produz o
 * identificador: dinheiro não tem, e Pix e cartão registrados à mão tampouco. O campo entra quando
 * houver um provedor de verdade produzindo o valor, pelo mesmo critério que manteve a
 * multiplicação fora de {@code Money} até existir o primeiro uso real.
 *
 * @param troco o que volta para o cliente; zero nas formas que não devolvem dinheiro
 */
public record ResultadoPagamento(FormaPagamento forma, Money valor, StatusPagamento status,
        Money troco) {

    public ResultadoPagamento {
        Objects.requireNonNull(forma, "forma de pagamento nao pode ser nula");
        Objects.requireNonNull(valor, "valor do pagamento nao pode ser nulo");
        Objects.requireNonNull(status, "status do pagamento nao pode ser nulo");
        Objects.requireNonNull(troco, "troco nao pode ser nulo; use Money.ZERO quando nao ha troco");
    }

    /**
     * Pagamento em espécie, com o troco calculado a partir do que o cliente entregou (RF10).
     *
     * @param valor         quanto desta venda está sendo pago em dinheiro
     * @param valorRecebido o que veio na mão do cliente, obrigatório e nunca menor que
     *                      {@code valor}
     * @throws NullPointerException     se {@code valorRecebido} é nulo
     * @throws IllegalArgumentException se {@code valorRecebido} é menor que {@code valor}
     */
    public static ResultadoPagamento emDinheiro(Money valor, Money valorRecebido) {
        Objects.requireNonNull(valor, "valor do pagamento nao pode ser nulo");
        Objects.requireNonNull(valorRecebido,
                "valor recebido em dinheiro e obrigatorio; informe o proprio valor da parcela "
                        + "quando o cliente pagar o valor exato");

        Money troco = valorRecebido.subtrair(valor);
        if (troco.isNegativo()) {
            throw new IllegalArgumentException("valor recebido em dinheiro e menor que o valor a "
                    + "pagar: recebido " + valorRecebido + ", a pagar " + valor);
        }

        return new ResultadoPagamento(FormaPagamento.DINHEIRO, valor, StatusPagamento.CONFIRMADO,
                troco);
    }
}
