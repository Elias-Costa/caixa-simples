package br.com.caixasimples.pagamentos.internal;

import br.com.caixasimples.pagamentos.FormaPagamento;
import br.com.caixasimples.pagamentos.domain.PaymentStrategy;
import br.com.caixasimples.pagamentos.domain.ResultadoPagamento;
import br.com.caixasimples.pagamentos.domain.SolicitacaoPagamento;
import org.springframework.stereotype.Component;

/**
 * Pagamento em espécie, com troco (RF10).
 *
 * <p><strong>A classe é fina de propósito.</strong> A regra do troco não está aqui: está em
 * {@code ResultadoPagamento.emDinheiro}, que é um tipo de domínio sem framework. O que sobra para
 * esta classe é dizer qual forma ela atende e chamar aquela fábrica, e é exatamente o que ela faz.
 *
 * <p>Fica em {@code internal} com visibilidade de pacote porque ninguém de fora precisa nomeá-la:
 * quem paga fala com o serviço de pagamento, que encontra a estratégia pela forma. Trocar esta
 * implementação não alcança módulo nenhum.
 */
@Component
class DinheiroPaymentStrategy implements PaymentStrategy {

    @Override
    public FormaPagamento getTipo() {
        return FormaPagamento.DINHEIRO;
    }

    @Override
    public ResultadoPagamento pagar(SolicitacaoPagamento solicitacao) {
        return ResultadoPagamento.emDinheiro(solicitacao.valor(), solicitacao.valorRecebido());
    }
}
