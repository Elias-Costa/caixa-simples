package br.com.caixasimples.shared;

/**
 * Lançada quando uma operação que depende de tenant roda sem conta no contexto.
 *
 * <p>Falhar aqui é deliberado: a alternativa seria consultar sem filtro de conta, que é exatamente
 * a falha de isolamento que o RNF05 proíbe. Nunca trate esta exceção devolvendo dado sem filtro.
 */
public class TenantNaoResolvidoException extends RuntimeException {

    public TenantNaoResolvidoException() {
        super("Nenhuma conta no contexto atual: a requisicao nao passou pelo filtro de tenant "
                + "ou o token nao trouxe o claim de contaId");
    }
}
