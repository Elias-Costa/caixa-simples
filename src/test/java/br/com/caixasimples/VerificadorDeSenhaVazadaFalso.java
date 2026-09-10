package br.com.caixasimples;

import br.com.caixasimples.contas.internal.SenhaRecusadaException;
import br.com.caixasimples.contas.internal.VerificadorDeSenhaVazada;
import java.util.HashSet;
import java.util.Set;

/**
 * Substitui o cliente do Have I Been Pwned na suíte.
 *
 * <p>Existe por um motivo concreto: <strong>nenhum teste pode depender de rede</strong>. O adapter
 * real faz chamada HTTP externa, e uma suíte que a executasse ficaria lenta, instável e falharia
 * sem conexão, justo neste projeto, cujo requisito central é funcionar offline.
 *
 * <p>É um objeto simples, e não um mock, porque os dois estados que importam, senha vazada e
 * serviço indisponível, são configuráveis em uma linha cada.
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

    /** Volta ao estado neutro. Chame no {@code @AfterEach} de quem alterar os sinalizadores. */
    public void limpar() {
        vazadas.clear();
        indisponivel = false;
    }
}
