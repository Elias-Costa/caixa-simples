package br.com.caixasimples;

import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base dos testes de integração.
 *
 * <p>Sobe a aplicação contra um <strong>PostgreSQL real</strong> em container, nunca H2: o
 * comportamento de {@code JSONB} e de índice GIN não se reproduz em H2, e a diferença só apareceria
 * em produção. O Flyway aplica as migrations no container, então um teste que sobe verde também
 * prova que as migrations batem com as entidades JPA, já que o Hibernate roda em modo de validação.
 *
 * <p>Como todas as subclasses compartilham a mesma configuração, o Spring reaproveita o contexto e
 * o container sobe uma vez por execução da suíte.
 *
 * <p><strong>Não anote a subclasse com {@code @Transactional}</strong> se ela troca de tenant no
 * meio do teste: uma transação única prende a sessão do Hibernate a um tenant resolvido no início,
 * e a troca não surte efeito. Deixe cada chamada de repositório abrir a própria transação, que é
 * também o que acontece de verdade, uma por requisição.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ConfiguracaoDeTeste.class})
public abstract class TesteDeIntegracao {

    /**
     * Chave HMAC aleatória por execução da suíte.
     *
     * <p>A aplicação exige a chave de assinatura e não tem valor padrão para ela. Gerar aqui, em
     * vez de fixar um valor num arquivo de teste, mantém o repositório <strong>sem nenhum segredo
     * literal</strong> e ainda exercita o mesmo caminho de configuração da produção.
     */
    @DynamicPropertySource
    static void chaveDeAssinaturaDoTeste(DynamicPropertyRegistry registro) {
        byte[] aleatoria = new byte[48];
        new SecureRandom().nextBytes(aleatoria);
        registro.add("caixa-simples.jwt.secret",
                () -> Base64.getUrlEncoder().withoutPadding().encodeToString(aleatoria));
    }
}
