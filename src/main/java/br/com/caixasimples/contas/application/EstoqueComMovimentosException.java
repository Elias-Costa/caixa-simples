package br.com.caixasimples.contas.application;

/** Desligar o controle pararia de atualizar um saldo que já tem histórico (RF17). */
public class EstoqueComMovimentosException extends IllegalStateException {

    public EstoqueComMovimentosException() {
        super("O controle de estoque não pode ser desligado, porque já há movimentos de estoque.");
    }
}
