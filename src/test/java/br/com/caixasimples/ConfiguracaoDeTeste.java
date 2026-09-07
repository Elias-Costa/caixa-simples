package br.com.caixasimples;

import br.com.caixasimples.contas.CriadorDeContaDeTeste;
import br.com.caixasimples.contas.internal.ContaRepository;
import br.com.caixasimples.contas.internal.CredencialRepository;
import br.com.caixasimples.contas.internal.UsuarioRepository;
import br.com.caixasimples.contas.internal.VerificadorDeSenhaVazada;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Substituicoes que valem para toda a suite de integracao.
 */
@TestConfiguration(proxyBeanMethods = false)
public class ConfiguracaoDeTeste {

    /**
     * Tira o cliente do Have I Been Pwned do caminho: a suite nao toca a rede.
     *
     * <p>O adapter real continua no contexto, mas nunca e injetado — {@code @Primary} resolve para
     * o falso. O comportamento real dele fica coberto por teste de unidade proprio.
     */
    @Bean
    @Primary
    VerificadorDeSenhaVazada verificadorDeSenhaVazada() {
        return new VerificadorDeSenhaVazadaFalso();
    }

    /** Fixture compartilhada; declarada aqui em vez de descoberta por varredura. */
    @Bean
    CriadorDeContaDeTeste criadorDeContaDeTeste(ContaRepository contas, UsuarioRepository usuarios,
            CredencialRepository credenciais, PasswordEncoder encoder) {
        return new CriadorDeContaDeTeste(contas, usuarios, credenciais, encoder);
    }
}
