package br.com.caixasimples.pagamentos.domain;

import br.com.caixasimples.shared.Money;

/** Resultado verificado da consulta por txid, independente do PSP. */
public record ConsultaPix(String txid, String chaveRecebedora, Money valor, boolean pago,
        boolean removida) {
    public ConsultaPix(String txid, String chaveRecebedora, Money valor, boolean pago) {
        this(txid, chaveRecebedora, valor, pago, false);
    }
}
