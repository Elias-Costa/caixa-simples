package br.com.caixasimples.sincronizacao;

/**
 * Um gesto enviado pelo dispositivo que uma regra do módulo dono recusou.
 *
 * <p>Existe para o módulo dizer que a exceção da regra dele é recusa, e não defeito, quando ela não
 * é argumento inválido, estado inválido nem acesso negado: o registro que não existe nesta conta,
 * o caixa que o operador já tem aberto, o conteúdo que não tem a forma do gesto. A mensagem é o
 * que o administrador lê na revisão.
 */
public class OperacaoRecusadaException extends RuntimeException {

    public OperacaoRecusadaException(String motivo) {
        super(motivo);
    }

    public OperacaoRecusadaException(String motivo, Throwable causa) {
        super(motivo, causa);
    }
}
