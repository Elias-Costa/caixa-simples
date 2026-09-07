package br.com.caixasimples;

import br.com.caixasimples.contas.internal.SenhaRecusadaException;
import br.com.caixasimples.contas.internal.VerificadorDeSenhaVazada;
import java.util.HashSet;
import java.util.Set;

/**
 * Substitui o cliente do Have I Been Pwned na suite.
 *
 * <p>Existe por um motivo concreto: <strong>nenhum teste pode depender de rede</strong>. O adapter
 * real faz chamada HTTP externa, e uma suite que a executasse ficaria lenta, instavel e falharia
 * offline — justo neste projeto, cujo requisito central e funcionar sem conexao.
 *
 * <p>E um objeto simples, nao um mock, porque os dois estados que importam (senha vazada e servico
 * indisponivel) sao configuraveis em uma linha cada.
 */
public class VerificadorDeSenhaVazadaFalso implements VerificadorDeSenhaVazada {

    private final Set<String> vazadas = new HashSet<>();
    private boolean indisponivel;

    @Override
    public void exigirNaoVazada(String senha) {
        if (indisponivel) {
            throw new SenhaRecusadaException(
                    "Nao foi possivel verificar a senha contra a lista de vazamentos");
        }
        if (vazadas.contains(senha)) {
            throw new SenhaRecusadaException(
                    "Esta senha aparece em vazamentos publicos conhecidos. Escolha outra.");
        }
    }

    public void marcarComoVazada(String senha) {
        vazadas.add(senha);
    }

    public void simularIndisponibilidade(boolean indisponivel) {
        this.indisponivel = indisponivel;
    }

    /** Volta ao estado neutro — chame no {@code @AfterEach} de quem alterar os sinalizadores. */
    public void limpar() {
        vazadas.clear();
        indisponivel = false;
    }
}
