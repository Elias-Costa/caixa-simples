package br.com.caixasimples.pagamentos.application;

import br.com.caixasimples.pagamentos.FormaPagamento;

/**
 * A forma pedida existe no vocabulário do domínio, mas nenhuma estratégia a atende ainda.
 *
 * <p>É falha alta, e não silêncio, porque a alternativa seria devolver um pagamento recusado e
 * confundir uma forma não implementada com um cartão negado pela operadora. Uma é defeito de
 * software, a outra é desfecho de negócio.
 */
public class FormaDePagamentoNaoSuportadaException extends RuntimeException {

    public FormaDePagamentoNaoSuportadaException(FormaPagamento forma) {
        super("nenhuma forma de pagamento registrada atende: " + forma);
    }
}
