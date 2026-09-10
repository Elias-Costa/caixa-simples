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
 * sozinho: nasce dentro de {@link Venda} e some com ela. É entidade própria, e não um campo da
 * venda, porque uma venda pode ser dividida entre formas (RF09): metade em dinheiro e metade no
 * cartão são dois registros deste tipo, cada um com o seu valor.
 *
 * <p>É um {@code record} porque parcela lançada não se edita. A confirmação de uma parcela
 * PENDENTE, quando existir, é uma troca de registro feita pela raiz, não uma mutação daqui.
 *
 * <p>Os dois enums vêm da raiz do módulo de pagamentos, que é a API pública dele. O módulo de
 * vendas nomeia a forma e o estado da parcela, mas quem sabe como cada forma é paga, com troco ou
 * sem, à mão ou por provedor, continua sendo o módulo de pagamentos.
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
 * @param criadoEm momento do lançamento, em UTC
 */
public record Pagamento(UUID id, FormaPagamento forma, Money valor, StatusPagamento status,
        Instant criadoEm) {

    public Pagamento {
        Objects.requireNonNull(id, "id nao pode ser nulo");
        Objects.requireNonNull(forma, "forma de pagamento nao pode ser nula");
        Objects.requireNonNull(valor, "valor do pagamento nao pode ser nulo");
        Objects.requireNonNull(status, "status do pagamento nao pode ser nulo");
        Objects.requireNonNull(criadoEm, "criadoEm nao pode ser nulo");

        if (valor.isNegativo()) {
            // Estorno não é parcela com sinal trocado; é o cancelamento da venda (RF12).
            throw new IllegalArgumentException(
                    "valor do pagamento nao pode ser negativo: " + valor);
        }
    }
}
