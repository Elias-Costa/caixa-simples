package br.com.caixasimples.vendas.domain;

import br.com.caixasimples.pagamentos.FormaPagamento;
import br.com.caixasimples.pagamentos.StatusPagamento;
import br.com.caixasimples.pagamentos.domain.CobrancaPix;
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
 * <p>É um {@code record} porque o valor da parcela lançada não se edita nem se desfaz: uma parcela
 * lançada com o valor errado se corrige cancelando a venda. A confirmação de uma parcela PENDENTE
 * troca o registro pela raiz, sem mutação direta do membro.
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
 * <p>O Pix integrado leva {@link CobrancaPix}: txid, chave, vencimento e código para retomar a
 * tentativa. Uma linha histórica de Pix manual continua sem esses dados.
 *
 * @param id       gerado na aplicação e nunca pelo banco (RNF01)
 * @param forma    como esta parcela foi paga
 * @param valor    o valor desta parcela, não o total da venda; zero vale, negativo não
 * @param status   Pix integrado nasce PENDENTE e passa a CONFIRMADO após reconsulta do PSP;
 *                 o que o operador lança à mão nasce CONFIRMADO
 * @param troco    o que voltou para o cliente nesta parcela; zero fora de dinheiro e quando o
 *                 cliente pagou o valor exato, nunca nulo
 * @param criadoEm momento do lançamento, em UTC
 */
public record Pagamento(UUID id, FormaPagamento forma, Money valor, StatusPagamento status,
        Money troco, Instant criadoEm, CobrancaPix cobrancaPix) {

    public Pagamento(UUID id, FormaPagamento forma, Money valor, StatusPagamento status,
            Money troco, Instant criadoEm) {
        this(id, forma, valor, status, troco, criadoEm, null);
    }

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
        if (cobrancaPix != null && forma != FormaPagamento.PIX) {
            throw new IllegalArgumentException("cobranca Pix exige parcela PIX");
        }
    }

    /**
     * Parcela nova, criada pela raiz.
     *
     * <p>Visibilidade de pacote, para que só a raiz crie parcela: é ela que confere o valor contra
     * o que falta pagar. O status e o troco chegam prontos, decididos pelo módulo de pagamentos,
     * porque é a forma que sabe se a parcela nasce confirmada ou espera um provedor, e quanto
     * volta para o cliente. A identidade e o instante chegam da raiz, como no item: gerados na
     * hora no servidor, ou os do balcão quando a parcela foi lançada no dispositivo sem rede.
     */
    static Pagamento novo(UUID id, FormaPagamento forma, Money valor, StatusPagamento status,
            Money troco, Instant criadoEm) {
        return new Pagamento(id, forma, valor, status, troco, criadoEm);
    }

    static Pagamento pixPendente(UUID id, Money valor, CobrancaPix cobranca) {
        return new Pagamento(id, FormaPagamento.PIX, valor, StatusPagamento.PENDENTE,
                Money.ZERO, Instant.now(), Objects.requireNonNull(cobranca));
    }

    Pagamento comCobranca(CobrancaPix cobranca) {
        return new Pagamento(id, forma, valor, status, troco, criadoEm, cobranca);
    }

    Pagamento comStatus(StatusPagamento novoStatus) {
        return new Pagamento(id, forma, valor, novoStatus, troco, criadoEm, cobrancaPix);
    }
}
