package br.com.caixasimples.pagamentos.domain;

import br.com.caixasimples.shared.Money;

/** Resultado verificado da consulta por txid, independente do PSP. */
public record ConsultaPix(String txid, String chaveRecebedora, Money valor, boolean pago) {
}
