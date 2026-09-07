package br.com.caixasimples;

import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base dos testes de integracao — etapa 0.5 do plano de implementacao.
 *
 * <p>Sobe a aplicacao contra um <strong>Postgres real</strong> em container, nunca H2: o
 * comportamento de {@code JSONB} e de indice GIN nao se reproduz em H2, e a diferenca so
 * apareceria em producao (arquitetura §9). O Flyway aplica as migrations no container, entao um
 * teste que sobe verde tambem prova que as migrations batem com as entidades JPA
 * ({@code ddl-auto: validate}).
 *
 * <p>Como todas as subclasses compartilham a mesma configuracao, o Spring reaproveita o contexto e
 * o container sobe uma vez por execucao da suite.
 *
 * <p><strong>Nao anote a subclasse com {@code @Transactional}</strong> se ela troca de tenant no
 * meio do teste: uma transacao unica prende a sessao do Hibernate a um tenant resolvido no inicio,
 * e a troca nao surte efeito. Deixe cada chamada de repositorio abrir a propria transacao — que e
 * tambem o que acontece de verdade, uma por requisicao.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ConfiguracaoDeTeste.class})
public abstract class TesteDeIntegracao {

    /**
     * Chave HMAC aleatoria por execucao da suite.
     *
     * <p>A aplicacao exige {@code caixa-simples.jwt.secret} e nao tem padrao (D14e). Gerar aqui, em
     * vez de fixar um valor num arquivo de teste, mantem o repositorio <strong>sem nenhum segredo
     * literal</strong> e ainda exercita o mesmo caminho de configuracao da producao.
     */
    @DynamicPropertySource
    static void chaveDeAssinaturaDoTeste(DynamicPropertyRegistry registro) {
        byte[] aleatoria = new byte[48];
        new SecureRandom().nextBytes(aleatoria);
        registro.add("caixa-simples.jwt.secret",
                () -> Base64.getUrlEncoder().withoutPadding().encodeToString(aleatoria));
    }
}
