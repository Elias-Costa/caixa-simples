package br.com.caixasimples.contas.web;

import static java.util.stream.Collectors.counting;
import static java.util.stream.Collectors.groupingBy;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.caixasimples.TesteDeIntegracao;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.ObjectMapper;

/**
 * Contenção do login pelo servidor de verdade, numa porta real, como chega em produção: atrás da
 * entrada da hospedagem, que acrescenta ao {@code X-Forwarded-For} o endereço de quem se conectou a
 * ela. A origem que conta é a lida da direita, e o que o cliente escreve à esquerda não troca a
 * contagem. Pelo MockMvc, que chama o Spring direto, o cabeçalho nunca seria lido.
 *
 * <p>Mesmas anotações do teste da borda, para o Spring reaproveitar aquele contexto em vez de subir
 * outro, com outro banco. Os e-mails não existem: a recusa conta igual, e nenhuma Conta é preciso.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class ContencaoDoLoginNaBordaTest extends TesteDeIntegracao {

    private static final String SENHA_ERRADA = "outra senha bem longa";

    // Uma conexão por pedido, sem negociar outro protocolo antes: os pedidos simultâneos chegam
    // juntos ao servidor.
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    @LocalServerPort
    private int porta;

    @Autowired
    private ObjectMapper json;

    @Test
    @DisplayName("o prefixo forjado no cabeçalho do proxy não escapa da contagem da origem real")
    void prefixoForjadoNaoEscapa() throws Exception {
        String email = inexistente();
        String cliente = "198.51.100.31";

        for (int i = 0; i < 10; i++) {
            assertThat(enviar(login(email, "192.0.2." + i + ", " + cliente)).statusCode()).isEqualTo(401);
        }

        assertThat(enviar(login(email, "192.0.2.200, " + cliente)).statusCode()).isEqualTo(429);
        // Outro endereço à direita é outra origem, com contagem própria.
        assertThat(enviar(login(email, "192.0.2.201, 198.51.100.32")).statusCode()).isEqualTo(401);
    }

    /**
     * Conferir o limite e só contar a falha depois do BCrypt deixaria os vinte passarem pela
     * conferência antes de qualquer um ser contado. Reservada a tentativa antes, só dez chegam a
     * conferir a senha, em qualquer ordem de chegada.
     */
    @Test
    @DisplayName("vinte tentativas simultâneas do mesmo e-mail e origem: dez conferidas e dez recusadas")
    void tentativasSimultaneas() throws Exception {
        String email = inexistente();
        String cliente = "198.51.100.33";

        List<CompletableFuture<HttpResponse<String>>> pedidos = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            pedidos.add(http.sendAsync(login(email, cliente).build(), HttpResponse.BodyHandlers.ofString()));
        }
        Map<Integer, Long> porStatus = pedidos.stream()
                .map(CompletableFuture::join)
                .collect(groupingBy(HttpResponse::statusCode, counting()));

        assertThat(porStatus).isEqualTo(Map.of(401, 10L, 429, 10L));
    }

    private static String inexistente() {
        return "ninguem-" + UUID.randomUUID() + "@exemplo.test";
    }

    private HttpRequest.Builder login(String email, String cadeiaDoProxy) {
        String corpo = json.writeValueAsString(Map.of("email", email, "senha", SENHA_ERRADA));
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + porta + "/api/auth/login"))
                .header("Content-Type", "application/json")
                .header("X-Forwarded-For", cadeiaDoProxy)
                .POST(HttpRequest.BodyPublishers.ofString(corpo));
    }

    private HttpResponse<String> enviar(HttpRequest.Builder pedido) throws IOException, InterruptedException {
        return http.send(pedido.build(), HttpResponse.BodyHandlers.ofString());
    }
}
