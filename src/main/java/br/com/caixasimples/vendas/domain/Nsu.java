package br.com.caixasimples.vendas.domain;

import br.com.caixasimples.shared.LimiteDeTexto;

/**
 * A regra do NSU do pagamento em cartão: o número impresso no comprovante da maquininha, que o
 * operador digita para o dono conferir cada pagamento contra o extrato da operadora.
 *
 * <p>É texto livre, e não só dígitos, porque cada operadora imprime um identificador diferente,
 * alguns com letras. O repetido não é recusado: o número só é único dentro de cada operadora ou
 * maquininha, e dois pagamentos legítimos podem repeti-lo.
 *
 * <p>A normalização mora num lugar só porque a parcela, o recebimento de fiado e a comparação do
 * reenvio a usam, e a comparação precisa olhar o que seria gravado, não o que foi digitado.
 */
public final class Nsu {

    /** O mesmo limite da coluna. */
    public static final int TAMANHO_MAXIMO = 40;

    private Nsu() {
    }

    /**
     * Corta os espaços das pontas e devolve nulo quando não sobra nada, para que o campo deixado em
     * branco valha como não informado, e não como um NSU vazio.
     *
     * @return o NSU sem espaços nas pontas, ou nulo quando não foi informado
     * @throws IllegalArgumentException se passa do tamanho máximo depois do corte
     */
    public static String normalizar(String nsu) {
        if (nsu == null || nsu.isBlank()) {
            return null;
        }
        return LimiteDeTexto.exigirAte(nsu.trim(), TAMANHO_MAXIMO, "NSU");
    }
}
