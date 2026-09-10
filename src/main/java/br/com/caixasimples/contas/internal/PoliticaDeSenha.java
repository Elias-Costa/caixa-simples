package br.com.caixasimples.contas.internal;

import org.springframework.stereotype.Component;

/**
 * Regras que uma senha precisa cumprir para ser aceita.
 *
 * <p>Comprimento mínimo de 15 caracteres e <strong>nenhuma regra de composição</strong>. Exigir
 * número, símbolo ou maiúscula empurra o usuário para uma senha pior, anotada num papel no balcão.
 * O comprimento faz o trabalho que a composição não faz.
 */
@Component
public class PoliticaDeSenha {

    static final int TAMANHO_MINIMO = 15;

    private final VerificadorDeSenhaVazada verificador;

    PoliticaDeSenha(VerificadorDeSenhaVazada verificador) {
        this.verificador = verificador;
    }

    /**
     * @throws SenhaRecusadaException se a senha for curta, vazada, ou se não der para verificar
     */
    public void exigirValida(String senha) {
        if (senha == null || senha.length() < TAMANHO_MINIMO) {
            throw new SenhaRecusadaException(
                    "A senha precisa ter ao menos " + TAMANHO_MINIMO + " caracteres");
        }
        verificador.exigirNaoVazada(senha);
    }
}
