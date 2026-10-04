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

    /** Mensalidade fictícia do plano intermediário nos testes. */
    public static final String MENSALIDADE_CAIXA_SIMPLES = "30.00";

    /** Mensalidade fictícia do plano completo nos testes. */
    public static final String MENSALIDADE_COMPLETO = "70.00";

    /**
     * Chave HMAC aleatória por execução da suíte, e as mensalidades e o segredo dos planos.
     *
     * <p>A aplicação exige a chave de assinatura e não tem valor padrão para ela. Gerar aqui, em
     * vez de fixar um valor num arquivo de teste, mantém o repositório <strong>sem nenhum segredo
     * literal</strong> e ainda exercita o mesmo caminho de configuração da produção.
     */
    @DynamicPropertySource
    static void chaveDeAssinaturaDoTeste(DynamicPropertyRegistry registro) {
        // Gerado uma vez: o fornecedor é consultado a cada leitura da propriedade.
        String chave = segredoAleatorio();
        registro.add("caixa-simples.jwt.secret", () -> chave);
        registrarPlanos(registro);
    }

    /**
     * O segredo dos códigos de pedido, aleatório como a chave do token, e as mensalidades
     * fictícias. Público para o teste que sobe a aplicação sem esta base.
     */
    public static void registrarPlanos(DynamicPropertyRegistry registro) {
        String segredo = segredoAleatorio();
        registro.add("caixa-simples.planos.segredo", () -> segredo);
        registro.add("caixa-simples.planos.mensalidade-caixa-simples",
                () -> MENSALIDADE_CAIXA_SIMPLES);
        registro.add("caixa-simples.planos.mensalidade-completo", () -> MENSALIDADE_COMPLETO);
    }

    private static String segredoAleatorio() {
        byte[] aleatoria = new byte[48];
        new SecureRandom().nextBytes(aleatoria);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(aleatoria);
    }
}
