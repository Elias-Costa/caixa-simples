package br.com.caixasimples.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.caixasimples.TesteDeIntegracao;
import br.com.caixasimples.contas.AutenticadorDeTeste;
import br.com.caixasimples.contas.CriadorDeContaDeTeste;
import br.com.caixasimples.contas.CriadorDeContaDeTeste.ContaCriada;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * A política de conteúdo, a de referência e a de permissões saem em toda resposta da origem: o
 * shell, os arquivos do aplicativo e a API, com ou sem token.
 *
 * <p>O token fica no aparelho, ao alcance de qualquer script da origem, e a política de conteúdo é
 * o que limita o estrago de um script injetado. Ela só vale se chegar com o documento e com os
 * arquivos que ele carrega, então o teste olha cada tipo de resposta, contra o servidor de verdade,
 * no mesmo contexto dos outros testes de borda. Os valores estão escritos por extenso aqui de
 * propósito: mudar a política é uma decisão, e o teste deve mudar junto.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class CabecalhosDeSegurancaNaBordaTest extends TesteDeIntegracao {

    private static final String SENHA = "uma senha longa de teste";

    private static final String POLITICA_DE_CONTEUDO = "default-src 'self'; script-src 'self'; "
            + "style-src 'self'; img-src 'self'; connect-src 'self'; font-src 'self'; "
            + "manifest-src 'self'; worker-src 'self'; object-src 'none'; base-uri 'self'; "
            + "form-action 'self'; frame-ancestors 'none'";

    private static final String POLITICA_DE_PERMISSOES = "camera=(), microphone=(), geolocation=(), "
            + "payment=(), usb=(), clipboard-write=(self), web-share=(self)";

    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    @LocalServerPort
    private int porta;

    @Autowired
    private CriadorDeContaDeTeste criador;

    @Autowired
    private AutenticadorDeTeste autenticador;

    @Test
    @DisplayName("o shell sai com as três políticas, pela raiz e por rota do cliente")
    void shell() throws Exception {
        HttpResponse<String> raiz = enviar(pedido("/"));
        HttpResponse<String> rotaDoCliente = enviar(pedido("/vendas/8f7b1c2e-0000-4000-8000-000000000001"));

        assertThat(raiz.statusCode()).isEqualTo(200);
        conferirPoliticas(raiz);
        assertThat(rotaDoCliente.statusCode()).isEqualTo(200);
        conferirPoliticas(rotaDoCliente);
    }

    /**
     * O service worker é avaliado com os cabeçalhos da resposta que o trouxe, e não com os do
     * documento: o arquivo pedido pelo nome também precisa sair com a política.
     */
    @Test
    @DisplayName("o script de assets, o manifest e o arquivo que não existe saem com as três políticas")
    void arquivosDoAplicativo() throws Exception {
        HttpResponse<String> script = enviar(pedido("/assets/aplicativo-de-teste.js"));
        HttpResponse<String> manifest = enviar(pedido("/manifest.json"));
        HttpResponse<String> inexistente = enviar(pedido("/assets/nao-existe.js"));

        assertThat(script.statusCode()).isEqualTo(200);
        conferirPoliticas(script);
        assertThat(manifest.statusCode()).isEqualTo(200);
        conferirPoliticas(manifest);
        assertThat(inexistente.statusCode()).isEqualTo(404);
        conferirPoliticas(inexistente);
    }

    @Test
    @DisplayName("a API sai com as três políticas, na recusa sem token e na resposta autenticada")
    void api() throws Exception {
        ContaCriada conta = criador.criar("Loja da Esquina", SENHA);
        String token = autenticador.tokenDe(conta);

        HttpResponse<String> semToken = enviar(pedido("/api/auth/eu"));
        HttpResponse<String> comToken = enviar(pedido("/api/auth/eu")
                .header("Authorization", "Bearer " + token));

        assertThat(semToken.statusCode()).isEqualTo(401);
        conferirPoliticas(semToken);
        assertThat(comToken.statusCode()).isEqualTo(200);
        conferirPoliticas(comToken);
    }

    /**
     * Confere também dois cabeçalhos que o Spring Security já escrevia, para provar que acrescentar
     * as políticas não tirou os de antes.
     */
    private static void conferirPoliticas(HttpResponse<String> resposta) {
        assertThat(resposta.headers().allValues("Content-Security-Policy"))
                .containsExactly(POLITICA_DE_CONTEUDO);
        assertThat(resposta.headers().allValues("Content-Security-Policy-Report-Only")).isEmpty();
        assertThat(resposta.headers().allValues("Referrer-Policy")).containsExactly("no-referrer");
        assertThat(resposta.headers().allValues("Permissions-Policy"))
                .containsExactly(POLITICA_DE_PERMISSOES);
        assertThat(resposta.headers().firstValue("X-Content-Type-Options")).hasValue("nosniff");
        assertThat(resposta.headers().firstValue("X-Frame-Options")).hasValue("DENY");
    }

    private HttpRequest.Builder pedido(String caminho) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + porta + caminho)).GET();
    }

    private HttpResponse<String> enviar(HttpRequest.Builder pedido) throws IOException, InterruptedException {
        return http.send(pedido.build(), HttpResponse.BodyHandlers.ofString());
    }
}
