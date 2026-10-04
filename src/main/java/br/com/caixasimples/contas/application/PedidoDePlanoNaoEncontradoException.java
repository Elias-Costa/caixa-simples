package br.com.caixasimples.contas.application;

import java.util.UUID;

/**
 * Não existe pedido de plano com esse id <strong>nesta conta</strong>.
 *
 * <p>Um id de outra conta é indistinguível de um id que nunca existiu, porque toda consulta de
 * pedido passa pelo filtro de tenant (RNF05).
 */
public class PedidoDePlanoNaoEncontradoException extends RuntimeException {

    public PedidoDePlanoNaoEncontradoException(UUID id) {
        super("pedido de plano nao encontrado nesta conta: " + id);
    }
}
