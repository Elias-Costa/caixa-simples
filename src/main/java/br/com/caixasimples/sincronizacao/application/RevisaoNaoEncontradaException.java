package br.com.caixasimples.sincronizacao.application;

import java.util.UUID;

/**
 * Não existe operação sincronizada com esse id <strong>nesta conta</strong>.
 *
 * <p>O id que nunca existiu e o id de outra conta dão a mesma resposta: o registro de operações
 * passa pelo filtro de tenant, e a operação de outra conta simplesmente não volta do banco
 * (RNF05).
 */
public class RevisaoNaoEncontradaException extends RuntimeException {

    public RevisaoNaoEncontradaException(UUID operacaoId) {
        super("operacao sincronizada nao encontrada nesta conta: " + operacaoId);
    }
}
