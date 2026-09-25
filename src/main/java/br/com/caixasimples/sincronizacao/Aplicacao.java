package br.com.caixasimples.sincronizacao;

/**
 * O que um módulo fez com um gesto que aceitou.
 *
 * @param versao  a revisão do Produto, Cliente ou SessaoCaixa depois do gesto, que o dispositivo
 *                guarda como a nova versão lida; nula quando o registro não tem revisão, como a
 *                Venda
 * @param revisao por que o gesto, aplicado, precisa ser conferido pelo administrador; nulo quando
 *                não há pendência
 */
public record Aplicacao(Long versao, String revisao) {

    public static Aplicacao aplicada(Long versao) {
        return new Aplicacao(versao, null);
    }

    public static Aplicacao comRevisao(Long versao, String motivo) {
        if (motivo == null || motivo.isBlank()) {
            throw new IllegalArgumentException("revisao sem motivo nao diz o que conferir");
        }
        return new Aplicacao(versao, motivo);
    }
}
