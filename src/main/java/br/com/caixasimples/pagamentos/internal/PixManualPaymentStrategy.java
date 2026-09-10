package br.com.caixasimples.pagamentos.internal;

import br.com.caixasimples.pagamentos.FormaPagamento;
import br.com.caixasimples.pagamentos.domain.PaymentStrategy;
import br.com.caixasimples.pagamentos.domain.ResultadoPagamento;
import br.com.caixasimples.pagamentos.domain.SolicitacaoPagamento;
import org.springframework.stereotype.Component;

/**
 * Pix lançado à mão, sem provedor.
 *
 * <p>O operador confere a notificação de recebimento e digita o valor. Não existe cobrança gerada
 * nem confirmação que chegue sozinha: por isso o pagamento nasce confirmado, como o cartão
 * digitado no balcão, e não pendente.
 *
 * <p>O Pix cobrado por um provedor (RF25) é uma estratégia diferente, que gera a cobrança e espera
 * a confirmação dele. As duas atendem a mesma forma e, por isso, <strong>não podem estar
 * registradas ao mesmo tempo</strong>: o serviço de pagamento recusa duas estratégias da mesma
 * forma ao subir a aplicação.
 *
 * <p>Fina de propósito, como as outras estratégias: a regra do que fica registrado mora em
 * {@code ResultadoPagamento.registradoAMao}, que é um tipo de domínio sem framework.
 */
@Component
class PixManualPaymentStrategy implements PaymentStrategy {

    @Override
    public FormaPagamento getTipo() {
        return FormaPagamento.PIX;
    }

    @Override
    public ResultadoPagamento pagar(SolicitacaoPagamento solicitacao) {
        return ResultadoPagamento.registradoAMao(solicitacao);
    }
}
