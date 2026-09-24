package br.com.caixasimples.pagamentos.internal;

import br.com.caixasimples.pagamentos.FormaPagamento;
import br.com.caixasimples.pagamentos.domain.PaymentStrategy;
import br.com.caixasimples.pagamentos.domain.ResultadoPagamento;
import br.com.caixasimples.pagamentos.domain.SolicitacaoPagamento;
import org.springframework.stereotype.Component;

/** A cobrança integrada reserva a parcela; só a confirmação posterior a quita. */
@Component
class PixIntegradoPaymentStrategy implements PaymentStrategy {
    @Override
    public FormaPagamento getTipo() {
        return FormaPagamento.PIX;
    }

    @Override
    public ResultadoPagamento pagar(SolicitacaoPagamento solicitacao) {
        return ResultadoPagamento.pixPendente(solicitacao);
    }
}
