package br.com.caixasimples.pagamentos.internal;

import br.com.caixasimples.pagamentos.FormaPagamento;
import br.com.caixasimples.pagamentos.domain.PaymentStrategy;
import br.com.caixasimples.pagamentos.domain.ResultadoPagamento;
import br.com.caixasimples.pagamentos.domain.SolicitacaoPagamento;
import org.springframework.stereotype.Component;

/**
 * Cartão passado na maquininha e lançado à mão, sem integração com ela (RF26).
 *
 * <p>Registra apenas o valor recebido. Bandeira, número de parcelas e código de autorização ficam
 * de fora porque nada no sistema os usaria: sem comunicação com a maquininha, seriam campos
 * digitados de novo pelo operador só para serem guardados.
 *
 * <p>Débito e crédito são a mesma forma. Distingui-los mudaria o que o operador tem de informar no
 * balcão sem mudar nada no que o sistema faz com o valor.
 *
 * <p>Fina de propósito, como as outras estratégias: a regra do que fica registrado mora em
 * {@code ResultadoPagamento.registradoAMao}, que é um tipo de domínio sem framework.
 */
@Component
class CartaoManualPaymentStrategy implements PaymentStrategy {

    @Override
    public FormaPagamento getTipo() {
        return FormaPagamento.CARTAO;
    }

    @Override
    public ResultadoPagamento pagar(SolicitacaoPagamento solicitacao) {
        return ResultadoPagamento.registradoAMao(solicitacao);
    }
}
