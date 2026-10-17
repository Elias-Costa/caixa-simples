package br.com.caixasimples.shared;

/**
 * A conferência do tamanho de um texto contra o limite da coluna que o guarda, antes de ele chegar
 * ao banco.
 *
 * <p>O banco continua sendo a última linha de defesa, com cada texto em {@code varchar} limitado.
 * Mas a recusa dele chega como violação de integridade, que a borda HTTP não distingue da unicidade
 * e que, sem tratamento, vira erro inesperado. Conferido aqui, o texto longo é argumento inválido:
 * 400 na rota e recusa com a mensagem no lote de sincronização, que chama os mesmos casos de uso.
 *
 * <p>O tamanho é o de {@link String#length}, em unidades UTF-16, a mesma conta do limite dos campos
 * do aplicativo. O PostgreSQL conta caracteres, e um emoji vale um lá e dois aqui: um texto com
 * emoji perto do limite é recusado mesmo cabendo na coluna, nunca o contrário.
 *
 * <p>A mensagem diz o campo, o tamanho e o limite, e nunca o texto recebido: ele pode ser o nome
 * ou o contato de uma pessoa, e a recusa do lote é gravada com a mensagem.
 */
public final class LimiteDeTexto {

    private LimiteDeTexto() {
    }

    /**
     * Quem chama passa o texto como ele será gravado, já sem os espaços das pontas, para que um
     * texto colado com espaços em volta não passe do limite sem passar de fato.
     *
     * @param texto  o texto como será gravado; nulo passa, porque o campo opcional ausente cabe
     * @param limite o tamanho da coluna
     * @param campo  o nome do campo na mensagem, como o operador o conhece
     * @return o próprio texto
     * @throws IllegalArgumentException se o texto passa do limite
     */
    public static String exigirAte(String texto, int limite, String campo) {
        if (texto != null && texto.length() > limite) {
            throw new IllegalArgumentException(campo + " com " + texto.length()
                    + " caracteres; o limite é " + limite + ".");
        }
        return texto;
    }
}
