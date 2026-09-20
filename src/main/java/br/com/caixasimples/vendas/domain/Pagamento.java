package br.com.caixasimples.vendas.domain;

import br.com.caixasimples.pagamentos.FormaPagamento;
import br.com.caixasimples.pagamentos.StatusPagamento;
import br.com.caixasimples.shared.Money;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Uma parcela do pagamento de uma venda, numa forma só.
 *
 * <p><strong>Membro do agregado Venda</strong>, nunca raiz. Não tem repositório e não se altera
 * sozinho: nasce dentro de {@link Venda}, por {@link Venda#registrarPagamento}, e some com ela. É
 * entidade própria, e não um campo da venda, porque uma venda pode ser dividida entre formas
 * (RF09): metade em dinheiro e metade no cartão são dois registros deste tipo, cada um com o seu
 * valor.
 *
 * <p>É um {@code record} porque parcela lançada não se edita, e também não se desfaz: uma parcela
 * lançada com o valor errado se corrige cancelando a venda. A confirmação de uma parcela PENDENTE,
 * quando existir, é uma troca de registro feita pela raiz, não uma mutação daqui.
 *
 * <p>Os dois enums vêm da raiz do módulo de pagamentos, que é a API pública dele. O módulo de
 * vendas nomeia a forma e o estado da parcela, mas quem sabe como cada forma é paga, com troco ou
 * sem, à mão ou por provedor, continua sendo o módulo de pagamentos.
 *
 * <p><strong>O troco fica gravado na parcela</strong>, e não só devolvido à tela no ato: o
 * comprovante (RF11) imprime o troco, e uma reimpressão tem de sair igual à primeira. É o troco,
 * e não o valor recebido, porque é o troco que o comprovante mostra; o recebido é a soma dos dois.
 * Zero quando não há, nunca nulo, como o desconto do item: nenhum leitor precisa tratar ausência.
 * E só dinheiro devolve troco (RF10): Pix e cartão são pagos no valor exato, e o módulo de
 * pagamentos já recusa valor recebido nas duas formas; a guarda aqui espelha a restrição da
 * coluna, para uma linha gravada por fora do código não virar uma parcela que o domínio recusaria.
 *
 * <p><strong>Não tem identificador de transação do provedor</strong>, e a ausência é deliberada:
 * nenhuma forma implementada produz o valor, já que dinheiro não tem e Pix e cartão lançados à
 * mão tampouco. O campo entra com o primeiro provedor de verdade.
 *
 * @param id       gerado na aplicação e nunca pelo banco (RNF01)
 * @param forma    como esta parcela foi paga
 * @param valor    o valor desta parcela, não o total da venda; zero vale, negativo não
 * @param status   PENDENTE só para cobrança gerada por provedor; o que o operador lança à mão
 *                 nasce CONFIRMADO
 * @param troco    o que voltou para o cliente nesta parcela; zero fora de dinheiro e quando o
 *                 cliente pagou o valor exato, nunca nulo
 * @param criadoEm momento do lançamento, em UTC
 */
public record Pagamento(UUID id, FormaPagamento forma, Money valor, StatusPagamento status,
        Money troco, Instant criadoEm) {

    public Pagamento {
        Objects.requireNonNull(id, "id nao pode ser nulo");
        Objects.requireNonNull(forma, "forma de pagamento nao pode ser nula");
        Objects.requireNonNull(valor, "valor do pagamento nao pode ser nulo");
        Objects.requireNonNull(status, "status do pagamento nao pode ser nulo");
        Objects.requireNonNull(troco, "troco nao pode ser nulo; use Money.ZERO quando nao ha");
        Objects.requireNonNull(criadoEm, "criadoEm nao pode ser nulo");

        if (valor.isNegativo()) {
            // Estorno não é parcela com sinal trocado; é o cancelamento da venda (RF12).
            throw new IllegalArgumentException(
                    "valor do pagamento nao pode ser negativo: " + valor);
        }
        if (troco.isNegativo()) {
            // Troco negativo seria o cliente pagando a menos, que o módulo de pagamentos já
            // recusa antes de a parcela existir.
            throw new IllegalArgumentException("troco nao pode ser negativo: " + troco);
        }
        if (forma != FormaPagamento.DINHEIRO && !troco.equals(Money.ZERO)) {
            throw new IllegalArgumentException(
                    "so dinheiro devolve troco; parcela em " + forma + " veio com troco de "
                            + troco);
        }
    }

    /**
     * Parcela nova: identidade e momento nascem aqui, como em todo registro do sistema (RNF01).
     *
     * <p>Visibilidade de pacote, para que só a raiz crie parcela: é ela que confere o valor contra
     * o que falta pagar. O status e o troco chegam prontos, decididos pelo módulo de pagamentos,
     * porque é a forma que sabe se a parcela nasce confirmada ou espera um provedor, e quanto
     * volta para o cliente.
     */
    static Pagamento novo(FormaPagamento forma, Money valor, StatusPagamento status, Money troco) {
        return new Pagamento(UUID.randomUUID(), forma, valor, status, troco, Instant.now());
    }
}
