package br.com.caixasimples.contas.application;

/** Desligar o controle pararia de atualizar um saldo que já tem histórico (RF17). */
public class EstoqueComMovimentosException extends IllegalStateException {

    public EstoqueComMovimentosException() {
        super("o controle de estoque nao pode ser desligado porque ja existem movimentos");
    }
}
