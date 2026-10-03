package br.com.caixasimples;

import java.util.ArrayList;
import java.util.List;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * O codificador de senha da aplicação, com o registro de cada conferência.
 *
 * <p>Serve ao teste de que a recusa de um e-mail inexistente custa o mesmo que a de uma senha errada.
 * O custo de uma conferência BCrypt está escrito no próprio hash conferido, então basta saber quantas
 * conferências cada recusa fez e contra que hash, sem medir tempo. Todo login da suíte continua
 * conferindo de verdade: este codificador só anota e repassa ao da aplicação.
 */
public class CodificadorDeSenhaQueRegistra implements PasswordEncoder {

    private final PasswordEncoder daAplicacao;
    private final List<String> conferidos = new ArrayList<>();

    public CodificadorDeSenhaQueRegistra(PasswordEncoder daAplicacao) {
        this.daAplicacao = daAplicacao;
    }

    @Override
    public String encode(CharSequence senha) {
        return daAplicacao.encode(senha);
    }

    @Override
    public boolean matches(CharSequence senha, String hash) {
        synchronized (conferidos) {
            conferidos.add(hash);
        }
        return daAplicacao.matches(senha, hash);
    }

    /** Esquece as conferências anteriores, para o teste olhar só as que ele mesmo provocar. */
    public void esquecer() {
        synchronized (conferidos) {
            conferidos.clear();
        }
    }

    /** Os hashes conferidos desde o último {@link #esquecer()}, na ordem em que chegaram. */
    public List<String> conferidos() {
        synchronized (conferidos) {
            return List.copyOf(conferidos);
        }
    }
}
