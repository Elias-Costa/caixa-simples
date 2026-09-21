package br.com.caixasimples;

import br.com.caixasimples.caixa.CriadorDeSessaoCaixaDeTeste;
import br.com.caixasimples.caixa.internal.SessaoCaixaRepository;
import br.com.caixasimples.contas.AutenticadorDeTeste;
import br.com.caixasimples.contas.CriadorDeContaDeTeste;
import br.com.caixasimples.contas.internal.ContaRepository;
import br.com.caixasimples.contas.internal.CredencialRepository;
import br.com.caixasimples.contas.internal.UsuarioRepository;
import br.com.caixasimples.contas.internal.VerificadorDeSenhaVazada;
import br.com.caixasimples.vendas.CriadorDeVendaDeTeste;
import br.com.caixasimples.vendas.internal.VendaRepository;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/**
 * Substituições que valem para toda a suíte de integração.
 */
@TestConfiguration(proxyBeanMethods = false)
public class ConfiguracaoDeTeste {

    /**
     * Tira o cliente do Have I Been Pwned do caminho, porque a suíte não toca a rede.
     *
     * <p>O adapter real continua no contexto, mas nunca é injetado, já que {@code @Primary} resolve
     * para o falso. O comportamento real dele fica coberto por teste de unidade próprio.
     */
    @Bean
    @Primary
    VerificadorDeSenhaVazada verificadorDeSenhaVazada() {
        return new VerificadorDeSenhaVazadaFalso();
    }

    /** Fixture compartilhada, declarada aqui em vez de descoberta por varredura. */
    @Bean
    CriadorDeContaDeTeste criadorDeContaDeTeste(ContaRepository contas, UsuarioRepository usuarios,
            CredencialRepository credenciais, PasswordEncoder encoder) {
        return new CriadorDeContaDeTeste(contas, usuarios, credenciais, encoder);
    }

    /**
     * Mesma ideia, para teste de endpoint: faz o login real de uma conta de teste e entrega o
     * token, ou o cabeçalho pronto.
     */
    @Bean
    AutenticadorDeTeste autenticadorDeTeste(MockMvc http, ObjectMapper json) {
        return new AutenticadorDeTeste(http, json);
    }

    /** Mesma ideia, para teste de outro módulo que precise apontar para uma venda real. */
    @Bean
    CriadorDeVendaDeTeste criadorDeVendaDeTeste(VendaRepository vendas) {
        return new CriadorDeVendaDeTeste(vendas);
    }

    /**
     * Mesma ideia, para o relatório que precisa de movimentos de caixa lançados em instantes
     * escolhidos.
     */
    @Bean
    CriadorDeSessaoCaixaDeTeste criadorDeSessaoCaixaDeTeste(SessaoCaixaRepository sessoes) {
        return new CriadorDeSessaoCaixaDeTeste(sessoes);
    }
}
