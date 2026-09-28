package br.com.caixasimples.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.caixasimples.TesteDeIntegracao;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * A variável que declara um proxy público além da rede interna, para o caso de a entrada da
 * hospedagem ganhar um salto fora dela. Sem novo build, as faixas declaradas passam a ser
 * atravessadas como a rede interna; o que não foi declarado continua virando a origem.
 *
 * <p>Contexto próprio, numa porta real, pelo mesmo motivo de {@link BordaDoProxyTest}: quem lê os
 * cabeçalhos é o servidor, antes do Spring. As faixas são de documentação, uma IPv4 e uma IPv6,
 * para provar que a lista chega inteira ao servidor pela variável, dois-pontos inclusive.
 */
@SpringBootTest(
        webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = "CAIXA_SIMPLES_PROXIES_CONFIAVEIS=198.51.100.0/24, 2001:db8:ffff::/48")
class BordaComProxyDeclaradoTest extends TesteDeIntegracao {

    private static final String CLIENTE = "203.0.113.7";

    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort
    private int porta;

    @Autowired
    private ObjectMapper json;

    @Test
    @DisplayName("um salto público declarado é atravessado, e a origem é o cliente")
    void saltoDeclaradoEhAtravessado() throws Exception {
        JsonNode eco = eco("192.0.2.1, " + CLIENTE + ", 198.51.100.9, 10.26.236.170");

        assertThat(eco.path("origem").asString()).isEqualTo(CLIENTE);
    }

    @Test
    @DisplayName("um salto declarado por IPv6 é atravessado do mesmo jeito")
    void saltoDeclaradoPorIpv6() throws Exception {
        JsonNode eco = eco(CLIENTE + ", 2001:db8:ffff::9");

        assertThat(eco.path("origem").asString()).isEqualTo(CLIENTE);
    }

    @Test
    @DisplayName("um salto fora das faixas declaradas continua virando a origem")
    void saltoNaoDeclaradoViraAOrigem() throws Exception {
        JsonNode eco = eco(CLIENTE + ", 198.51.101.9");

        assertThat(eco.path("origem").asString()).isEqualTo("198.51.101.9");
    }

    private JsonNode eco(String cadeia) throws IOException, InterruptedException {
        HttpRequest pedido = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + porta + "/teste/borda"))
                .header("X-Forwarded-For", cadeia)
                .GET()
                .build();
        HttpResponse<String> resposta = http.send(pedido, HttpResponse.BodyHandlers.ofString());
        assertThat(resposta.statusCode()).isEqualTo(200);
        return json.readTree(resposta.body());
    }
}
