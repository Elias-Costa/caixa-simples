package br.com.caixasimples.shared;

/**
 * Lancada quando uma operacao que depende de tenant roda sem conta no contexto.
 *
 * <p>Falhar aqui e deliberado: o alternativo seria consultar sem filtro de conta, que e exatamente
 * a falha que RNF05 proibe. Nunca trate esta excecao devolvendo dado sem filtro.
 */
public class TenantNaoResolvidoException extends RuntimeException {

    public TenantNaoResolvidoException() {
        super("Nenhuma conta no contexto atual: a requisicao nao passou pelo filtro de tenant "
                + "ou o token nao trouxe o claim de contaId");
    }
}
