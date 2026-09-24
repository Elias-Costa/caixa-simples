package br.com.caixasimples.pagamentos.domain;

import br.com.caixasimples.shared.Money;

/** Porta do provedor Pix; a Conta é sempre a do contexto autenticado. */
public interface PixGateway {
    String chaveRecebedora();

    /** Repetir o mesmo txid não cria outra cobrança. */
    String criarCobranca(CobrancaPix cobranca, Money valor);

    /** Consulta o PSP; o aviso do webhook nunca decide o estado financeiro. */
    ConsultaPix consultar(CobrancaPix cobranca);
}
