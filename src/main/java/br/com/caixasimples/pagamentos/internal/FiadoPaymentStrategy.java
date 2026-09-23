package br.com.caixasimples.pagamentos.internal;

import br.com.caixasimples.pagamentos.FormaPagamento;
import br.com.caixasimples.pagamentos.domain.PaymentStrategy;
import br.com.caixasimples.pagamentos.domain.ResultadoPagamento;
import br.com.caixasimples.pagamentos.domain.SolicitacaoPagamento;
import org.springframework.stereotype.Component;

/** Parcela de fiado: nasce pendente e será quitada por recebimentos da Venda. */
@Component
class FiadoPaymentStrategy implements PaymentStrategy {

    @Override
    public FormaPagamento getTipo() {
        return FormaPagamento.FIADO;
    }

    @Override
    public ResultadoPagamento pagar(SolicitacaoPagamento solicitacao) {
        return ResultadoPagamento.fiado(solicitacao);
    }
}
