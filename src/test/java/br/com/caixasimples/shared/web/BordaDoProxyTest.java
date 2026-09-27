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
 * Em produção, a requisição passa pela borda da CDN e pelo balanceador da hospedagem antes de
 * chegar à aplicação, e cada um acrescenta ao {@code X-Forwarded-For} quem se conectou a ele. Este
 * teste prova, contra o servidor de verdade, que a origem é lida da direita e que o que o cliente
 * escreve no cabeçalho nunca vira a origem.
 *
 * <p>Sobe o servidor numa porta real porque quem lê os cabeçalhos é o próprio servidor, antes do
 * Spring: pelo MockMvc, que chama o Spring direto, o teste passaria sem ter lido nada. Por isso
 * este contexto é separado do resto da suíte.
 *
 * <p>A cadeia é a medida na hospedagem: o cliente, um endereço da borda dentro das faixas
 * publicadas da Cloudflare e um endereço da rede interna. A conexão chega de {@code 127.0.0.1},
 * que é rede interna, como lá.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class BordaDoProxyTest extends TesteDeIntegracao {

    private static final String CLIENTE = "203.0.113.7";
    private static final String BORDA = "172.71.146.118";
    private static final String BALANCEADOR = "10.26.236.170";
    private static final String CADEIA_DA_HOSPEDAGEM = CLIENTE + ", " + BORDA + ", " + BALANCEADOR;

    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort
    private int porta;

    @Autowired
    private ObjectMapper json;

    @Test
    @DisplayName("atrás da borda e do balanceador, a origem é o cliente e a requisição é segura")
    void cadeiaDaHospedagem() throws Exception {
        JsonNode eco = eco(pedido("/teste/borda")
                .header("X-Forwarded-For", CADEIA_DA_HOSPEDAGEM)
                .header("X-Forwarded-Proto", "https"));

        assertThat(eco.path("origem").asString()).isEqualTo(CLIENTE);
        assertThat(eco.path("esquema").asString()).isEqualTo("https");
        assertThat(eco.path("segura").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("o que o cliente escreve à esquerda do cabeçalho não vira a origem")
    void prefixoForjadoEhIgnorado() throws Exception {
        JsonNode eco = eco(pedido("/teste/borda")
                .header("X-Forwarded-For", "192.0.2.1, " + CADEIA_DA_HOSPEDAGEM));

        assertThat(eco.path("origem").asString()).isEqualTo(CLIENTE);
    }

    @Test
    @DisplayName("um salto fora das faixas confiáveis vira a origem, e o que está antes dele não é lido")
    void saltoDesconhecidoViraAOrigem() throws Exception {
        JsonNode eco = eco(pedido("/teste/borda")
                .header("X-Forwarded-For", CLIENTE + ", 198.51.100.9, " + BALANCEADOR));

        assertThat(eco.path("origem").asString()).isEqualTo("198.51.100.9");
    }

    /**
     * A lista padrão vai dentro de um valor padrão de variável, e metade dela é IPv6, cheia de
     * dois-pontos: este caso prova que ela chega inteira ao servidor.
     */
    @Test
    @DisplayName("a borda que chega por IPv6 é atravessada do mesmo jeito")
    void bordaPorIpv6() throws Exception {
        JsonNode eco = eco(pedido("/teste/borda")
                .header("X-Forwarded-For", "2001:db8::7, 2606:4700::6810:84e5, " + BALANCEADOR));

        assertThat(eco.path("origem").asString()).isEqualTo("2001:db8::7");
    }

    @Test
    @DisplayName("sem proxy, a origem é quem se conectou e a requisição é HTTP")
    void semCabecalhoDeProxy() throws Exception {
        JsonNode eco = eco(pedido("/teste/borda"));

        assertThat(eco.path("origem").asString()).isEqualTo("127.0.0.1");
        assertThat(eco.path("esquema").asString()).isEqualTo("http");
        assertThat(eco.path("segura").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("o HSTS sai numa rota real só quando o proxy diz que a requisição chegou por HTTPS")
    void hstsSoQuandoChegouPorHttps() throws Exception {
        HttpResponse<String> porHttps = enviar(pedido("/")
                .header("X-Forwarded-For", CADEIA_DA_HOSPEDAGEM)
                .header("X-Forwarded-Proto", "https"));
        HttpResponse<String> porHttp = enviar(pedido("/"));

        assertThat(porHttps.statusCode()).isEqualTo(200);
        assertThat(porHttps.headers().firstValue("Strict-Transport-Security")).isPresent();
        assertThat(porHttp.statusCode()).isEqualTo(200);
        assertThat(porHttp.headers().firstValue("Strict-Transport-Security")).isEmpty();
    }

    private HttpRequest.Builder pedido(String caminho) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + porta + caminho)).GET();
    }

    private HttpResponse<String> enviar(HttpRequest.Builder pedido) throws IOException, InterruptedException {
        return http.send(pedido.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode eco(HttpRequest.Builder pedido) throws IOException, InterruptedException {
        HttpResponse<String> resposta = enviar(pedido);
        assertThat(resposta.statusCode()).isEqualTo(200);
        return json.readTree(resposta.body());
    }
}
