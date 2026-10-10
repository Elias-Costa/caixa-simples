package br.com.caixasimples.shared.web;

/**
 * O corpo enviado em partes, sem tamanho declarado, passou do limite da rota enquanto era lido.
 *
 * <p>Não estende IOException de propósito: lançada de dentro da leitura do corpo, uma IOException
 * seria embrulhada pelo leitor de JSON como corpo ilegível, e a resposta seria 400. Assim ela chega
 * inteira ao tratador de erros, que responde 413, como o filtro responde ao pedido que declara um
 * tamanho acima do limite.
 */
class CorpoGrandeDemaisException extends RuntimeException {

    private final int limite;

    CorpoGrandeDemaisException(int limite) {
        super("O corpo passou de " + limite + " bytes.");
        this.limite = limite;
    }

    int limite() {
        return limite;
    }
}
