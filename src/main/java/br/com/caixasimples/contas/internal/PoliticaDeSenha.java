package br.com.caixasimples.contas.internal;

import org.springframework.stereotype.Component;

/**
 * Regras que uma senha precisa cumprir para ser aceita (decisao A5).
 *
 * <p>Comprimento minimo de 15 caracteres e <strong>nenhuma regra de composicao</strong>. Exigir
 * numero, simbolo ou maiuscula empurra o usuario para uma senha pior e anotada num papel no
 * balcao — o comprimento faz o trabalho que a composicao nao faz.
 */
@Component
public class PoliticaDeSenha {

    static final int TAMANHO_MINIMO = 15;

    private final VerificadorDeSenhaVazada verificador;

    PoliticaDeSenha(VerificadorDeSenhaVazada verificador) {
        this.verificador = verificador;
    }

    /**
     * @throws SenhaRecusadaException se a senha for curta, vazada, ou se nao der para verificar
     */
    public void exigirValida(String senha) {
        if (senha == null || senha.length() < TAMANHO_MINIMO) {
            throw new SenhaRecusadaException(
                    "A senha precisa ter ao menos " + TAMANHO_MINIMO + " caracteres");
        }
        verificador.exigirNaoVazada(senha);
    }
}
