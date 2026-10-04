package br.com.caixasimples.vendas.domain;

import br.com.caixasimples.pagamentos.FormaPagamento;
import br.com.caixasimples.shared.Money;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Lançamento imutável de uma entrada de fiado, membro do agregado Venda.
 *
 * <p>O recebimento em cartão também passa na maquininha e aparece no extrato da operadora, por
 * isso pode levar o NSU do comprovante, com a mesma regra da parcela da venda.
 *
 * @param nsu só em cartão; sem espaços nas pontas, nulo quando não foi informado
 */
public record Recebimento(UUID id, UUID sessaoCaixaId, Money valor, FormaPagamento forma,
        Instant criadoEm, String nsu) {

    public Recebimento(UUID id, UUID sessaoCaixaId, Money valor, FormaPagamento forma,
            Instant criadoEm) {
        this(id, sessaoCaixaId, valor, forma, criadoEm, null);
    }

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
        nsu = Nsu.normalizar(nsu);
        if (nsu != null && forma != FormaPagamento.CARTAO) {
            throw new IllegalArgumentException(
                    "NSU so existe em cartao; recebimento em " + forma + " veio com NSU.");
        }
    }

    static Recebimento novo(UUID id, UUID sessaoCaixaId, Money valor, FormaPagamento forma,
            String nsu) {
        return new Recebimento(id, sessaoCaixaId, valor, forma, Instant.now(), nsu);
    }
}
