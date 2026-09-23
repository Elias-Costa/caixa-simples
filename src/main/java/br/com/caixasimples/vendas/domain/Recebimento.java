package br.com.caixasimples.vendas.domain;

import br.com.caixasimples.pagamentos.FormaPagamento;
import br.com.caixasimples.shared.Money;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Lançamento imutável de uma entrada de fiado, membro do agregado Venda. */
public record Recebimento(UUID id, UUID sessaoCaixaId, Money valor, FormaPagamento forma,
        Instant criadoEm) {

    public Recebimento {
        Objects.requireNonNull(id, "id do recebimento nao pode ser nulo");
        Objects.requireNonNull(sessaoCaixaId, "sessao do recebimento nao pode ser nula");
        Objects.requireNonNull(valor, "valor do recebimento nao pode ser nulo");
        Objects.requireNonNull(forma, "forma do recebimento nao pode ser nula");
        Objects.requireNonNull(criadoEm, "instante do recebimento nao pode ser nulo");
        if (valor.valor().signum() <= 0) {
            throw new IllegalArgumentException("valor do recebimento deve ser positivo");
        }
        if (forma == FormaPagamento.FIADO) {
            throw new IllegalArgumentException("fiado nao e forma de receber uma divida");
        }
    }

    static Recebimento novo(UUID sessaoCaixaId, Money valor, FormaPagamento forma) {
        return new Recebimento(UUID.randomUUID(), sessaoCaixaId, valor, forma, Instant.now());
    }
}
