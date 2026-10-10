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
 * Em produção, a requisição passa pelo proxy da mesma máquina antes de chegar à aplicação: ele
 * termina o HTTPS, descarta o {@code X-Forwarded-For} que o cliente mandou, põe no lugar o
 * endereço de quem se conectou a ele e fala com a aplicação pela rede interna dos contêineres.
 * Este teste prova, contra o servidor de verdade, que a origem é lida da direita e que o que o
 * cliente escreve no cabeçalho nunca vira a origem, mesmo que chegue até a aplicação.
 *
 * <p>Sobe o servidor numa porta real porque quem lê os cabeçalhos é o próprio servidor, antes do
 * Spring: pelo MockMvc, que chama o Spring direto, o teste passaria sem ter lido nada. Por isso
 * este contexto é separado do resto da suíte.
 *
 * <p>Nenhum proxy além da rede interna é declarado confiável aqui, como na configuração padrão. A
 * conexão chega de {@code 127.0.0.1}, que é rede interna, como a do proxy lá.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class BordaDoProxyTest extends TesteDeIntegracao {

    private static final String CLIENTE = "203.0.113.7";

    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort
    private int porta;

    @Autowired
    private ObjectMapper json;

    @Test
    @DisplayName("atrás do proxy da máquina, a origem é o cliente e a requisição é segura")
    void cadeiaDoProxy() throws Exception {
        JsonNode eco = eco(pedido("/teste/borda")
                .header("X-Forwarded-For", CLIENTE)
                .header("X-Forwarded-Proto", "https"));

        assertThat(eco.path("origem").asString()).isEqualTo(CLIENTE);
        assertThat(eco.path("esquema").asString()).isEqualTo("https");
        assertThat(eco.path("segura").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("o que o cliente escreve à esquerda do cabeçalho não vira a origem")
    void prefixoForjadoEhIgnorado() throws Exception {
        JsonNode eco = eco(pedido("/teste/borda")
                .header("X-Forwarded-For", "192.0.2.1, " + CLIENTE));

        assertThat(eco.path("origem").asString()).isEqualTo(CLIENTE);
    }

    /**
     * Entre o proxy e a aplicação pode haver outro salto dentro da rede interna, como um segundo
     * proxy na mesma máquina. Endereço privado no cabeçalho é atravessado como a própria conexão.
     */
    @Test
    @DisplayName("um salto da rede interna no cabeçalho é atravessado")
    void saltoInternoEhAtravessado() throws Exception {
        JsonNode eco = eco(pedido("/teste/borda")
                .header("X-Forwarded-For", CLIENTE + ", 10.26.236.170"));

        assertThat(eco.path("origem").asString()).isEqualTo(CLIENTE);
    }

    /**
     * Se aparecer na frente da aplicação um salto público que ninguém declarou, a origem vira esse
     * salto: o aviso de pagamento que confere o endereço é recusado, e nada escrito pelo cliente é
     * aceito.
     */
    @Test
    @DisplayName("um salto público não declarado vira a origem, e o que está antes dele não é lido")
    void saltoPublicoNaoDeclaradoViraAOrigem() throws Exception {
        JsonNode eco = eco(pedido("/teste/borda")
                .header("X-Forwarded-For", CLIENTE + ", 198.51.100.9"));

        assertThat(eco.path("origem").asString()).isEqualTo("198.51.100.9");
    }

    @Test
    @DisplayName("o cliente que chega por IPv6 atravessa um salto interno IPv6 do mesmo jeito")
    void clientePorIpv6() throws Exception {
        JsonNode eco = eco(pedido("/teste/borda")
                .header("X-Forwarded-For", "2001:db8::7, fd00::5"));

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
                .header("X-Forwarded-For", CLIENTE)
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
