package br.com.caixasimples.contas.application;

import java.util.UUID;

/**
 * O código de ativação não é o deste pedido, deste plano e deste valor.
 *
 * <p>Continua sendo um argumento recusado, que a API responde com 400 e esta mensagem. Tem classe
 * própria porque, ao contrário do código em branco, é uma tentativa de ativar o plano com um código
 * que não confere, e vai ao log como evento de segurança.
 */
public class CodigoDePlanoRecusadoException extends IllegalArgumentException {

    private final UUID pedidoId;

    public CodigoDePlanoRecusadoException(UUID pedidoId) {
        super("o codigo nao e o deste pedido");
        this.pedidoId = pedidoId;
    }

    public UUID pedidoId() {
        return pedidoId;
    }
}
