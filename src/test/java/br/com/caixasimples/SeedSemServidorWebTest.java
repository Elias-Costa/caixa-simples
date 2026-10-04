package br.com.caixasimples;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.caixasimples.contas.internal.CredencialRepository;
import br.com.caixasimples.contas.internal.VerificadorDeSenhaVazada;
import java.security.SecureRandom;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.context.WebApplicationContext;

/**
 * O seed como ele roda em produção: numa execução avulsa da imagem, sem servidor web, que cria a
 * Conta e termina. Com servidor web, o processo ficaria de pé depois de criar a Conta, e a execução
 * avulsa nunca acabaria sozinha.
 *
 * <p>Não estende {@link TesteDeIntegracao}: a base monta o MockMvc, que só existe com servidor web,
 * e é exatamente o contexto sem ele que se quer provar aqui. Por isso repete o que a base faz, o
 * PostgreSQL em contêiner, a chave de assinatura aleatória, o verificador de senha vazada sem rede
 * e as mensalidades e o segredo dos planos, que a aplicação exige também nesta execução avulsa.
 */
@SpringBootTest(webEnvironment = WebEnvironment.NONE, properties = {
        "spring.profiles.active=seed",
        "caixa-simples.seed.nome-negocio=Loja da Esquina",
        "caixa-simples.seed.email=seed.esquina@exemplo.test",
        "caixa-simples.seed.senha=uma senha longa de seed avulso"
})
@Import({TestcontainersConfiguration.class, SeedSemServidorWebTest.SemRede.class})
class SeedSemServidorWebTest {

    @Autowired
    private ApplicationContext contexto;

    @Autowired
    private CredencialRepository credenciais;

    @DynamicPropertySource
    static void chaveDeAssinaturaDoTeste(DynamicPropertyRegistry registro) {
        byte[] aleatoria = new byte[48];
        new SecureRandom().nextBytes(aleatoria);
        registro.add("caixa-simples.jwt.secret",
                () -> Base64.getUrlEncoder().withoutPadding().encodeToString(aleatoria));
        TesteDeIntegracao.registrarPlanos(registro);
    }

    @Test
    @DisplayName("sem servidor web, a aplicação sobe e o seed cria a Conta")
    void criaAContaSemServidorWeb() {
        assertThat(contexto).isNotInstanceOf(WebApplicationContext.class);
        assertThat(credenciais.findByEmailIgnoreCase("seed.esquina@exemplo.test")).isPresent();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class SemRede {

        @Bean
        @Primary
        VerificadorDeSenhaVazada verificadorDeSenhaVazada() {
            return new VerificadorDeSenhaVazadaFalso();
        }
    }
}
