package br.com.caixasimples.contas.internal;

import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Regras que uma senha precisa cumprir para ser aceita.
 *
 * <p>Comprimento mínimo de 15 caracteres e <strong>nenhuma regra de composição</strong>. Exigir
 * número, símbolo ou maiúscula empurra o usuário para uma senha pior, anotada num papel no balcão.
 * O comprimento faz o trabalho que a composição não faz.
 *
 * <p>O máximo é de 72 bytes em UTF-8, o limite do BCrypt, que recusa codificar mais que isso e, na
 * conferência, ignora o que passa. Conta em bytes, e não em caracteres, porque a letra acentuada
 * ocupa dois, e é conferido antes da lista de vazamentos, para a senha longa demais não sair do
 * servidor nem como prefixo de resumo.
 *
 * <p>Quando a lista de vazamentos não responde, quem escolheu a senha decide o desfecho. A senha
 * escolhida por outra pessoa, como a do mantenedor ao criar a Conta ou a do administrador ao criar
 * ou redefinir um operador, é recusada: ela é provisória, e esperar a lista voltar custa pouco. A
 * escolhida pela própria pessoa entra, porque a troca pode ser a resposta a uma senha vazada ou a um
 * aparelho furtado, e recusá-la manteria quem conhece a senha antiga entrando durante a queda.
 */
@Component
public class PoliticaDeSenha {

    static final int TAMANHO_MINIMO = 15;
    static final int MAXIMO_DE_BYTES = 72;

    private static final Logger log = LoggerFactory.getLogger(PoliticaDeSenha.class);

    private final VerificadorDeSenhaVazada verificador;

    PoliticaDeSenha(VerificadorDeSenhaVazada verificador) {
        this.verificador = verificador;
    }

    /**
     * Para a senha que outra pessoa escolheu: sem conseguir conferir a lista, recusa.
     *
     * @throws SenhaRecusadaException se a senha for curta, longa demais, vazada, ou se não der para
     *         verificar
     */
    public void exigirValida(String senha) {
        exigirTamanho(senha);
        verificador.exigirNaoVazada(senha);
    }

    /**
     * Para a senha que a própria pessoa escolheu: sem conseguir conferir a lista, aceita e deixa um
     * aviso no log, sem nada que identifique a pessoa.
     *
     * @throws SenhaRecusadaException se a senha for curta, longa demais ou vazada
     */
    public void exigirValidaEscolhidaPelaPropriaPessoa(String senha) {
        exigirTamanho(senha);
        try {
            verificador.exigirNaoVazada(senha);
        } catch (ListaDeVazadasIndisponivelException indisponivel) {
            log.warn("Senha escolhida pela própria pessoa aceita sem a verificação de vazamentos,"
                    + " porque a lista não respondeu");
        }
    }

    private static void exigirTamanho(String senha) {
        if (senha == null || senha.length() < TAMANHO_MINIMO) {
            throw new SenhaRecusadaException(
                    "A senha precisa ter ao menos " + TAMANHO_MINIMO + " caracteres");
        }
        if (senha.getBytes(StandardCharsets.UTF_8).length > MAXIMO_DE_BYTES) {
            throw new SenhaRecusadaException("A senha pode ter no máximo " + MAXIMO_DE_BYTES
                    + " letras sem acento; cada letra com acento ou cedilha conta como duas");
        }
    }
}
