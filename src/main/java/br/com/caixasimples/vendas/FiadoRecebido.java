package br.com.caixasimples.vendas;

import br.com.caixasimples.pagamentos.FormaPagamento;
import br.com.caixasimples.shared.ContaId;
import br.com.caixasimples.shared.Money;
import java.util.Objects;
import java.util.UUID;

/**
 * Fato de uma entrada de fiado; o Caixa reage apenas quando a forma é dinheiro.
 *
 * <p>É ouvido dentro da transação que gravou o recebimento: o recebimento na Venda e o dinheiro na
 * gaveta de quem recebeu confirmam juntos ou não confirmam. Carrega a conta, lida do contexto
 * autenticado no momento da publicação, para o ouvinte conferir que roda nessa mesma conta.
 */
public record FiadoRecebido(ContaId contaId, UUID vendaId, UUID recebimentoId,
        UUID sessaoCaixaId, Money valor, FormaPagamento forma) {
    public FiadoRecebido {
        Objects.requireNonNull(contaId);
        Objects.requireNonNull(vendaId);
        Objects.requireNonNull(recebimentoId);
        Objects.requireNonNull(sessaoCaixaId);
        Objects.requireNonNull(valor);
        Objects.requireNonNull(forma);
    }
}
