package br.com.caixasimples.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.caixasimples.TesteDeIntegracao;
import br.com.caixasimples.contas.CriadorDeContaDeTeste;
import br.com.caixasimples.contas.CriadorDeContaDeTeste.ContaCriada;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.ObjectMapper;

/**
 * O limite do corpo contra o servidor de verdade: o corpo enviado em partes, sem tamanho declarado,
 * e o caminho decodificado pelo próprio servidor.
 *
 * <p>Pelo MockMvc todo pedido tem o tamanho do conteúdo, e o caminho é decodificado pelo teste, não
 * pelo servidor. Sobe numa porta real, no mesmo contexto dos outros testes de borda.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class LimiteDoCorpoNaBordaTest extends TesteDeIntegracao {

    private static final String SENHA = "uma senha longa de teste";

    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    @LocalServerPort
    private int porta;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private CriadorDeContaDeTeste criador;

    @Test
    @DisplayName("o login em partes, sem tamanho declarado, passa de 4 KB durante a leitura e recebe 413")
    void loginEmPartesAcimaDoLimite() throws Exception {
        ContaCriada conta = criador.criar("Loja da Esquina", SENHA);

        HttpResponse<String> resposta = enviar(emPartes("/api/auth/login",
                loginCom(conta, LimiteDoCorpoFilter.LIMITE_DO_LOGIN + 1)));

        assertThat(resposta.statusCode()).isEqualTo(413);
        assertThat(json.readTree(resposta.body()).path("detail").asString())
                .isEqualTo("O pedido tem mais de 4 KB, o limite desta rota.");
    }

    @Test
    @DisplayName("o login em partes dentro do limite entra, então a contagem não atrapalha a leitura")
    void loginEmPartesDentroDoLimite() throws Exception {
        ContaCriada conta = criador.criar("Loja da Esquina", SENHA);

        HttpResponse<String> resposta = enviar(emPartes("/api/auth/login",
                loginCom(conta, LimiteDoCorpoFilter.LIMITE_DO_LOGIN)));

        assertThat(resposta.statusCode()).isEqualTo(200);
        assertThat(json.readTree(resposta.body()).path("token").asString()).isNotBlank();
    }

    @Test
    @DisplayName("o login escrito com uma letra codificada chega ao login, com o limite do login")
    void caminhoCodificadoNoServidor() throws Exception {
        ContaCriada conta = criador.criar("Loja da Esquina", SENHA);

        HttpResponse<String> acima = enviar(comTamanho("/api/auth/%6Cogin",
                loginCom(conta, LimiteDoCorpoFilter.LIMITE_DO_LOGIN + 1)));
        HttpResponse<String> dentro = enviar(comTamanho("/api/auth/%6Cogin",
                loginCom(conta, LimiteDoCorpoFilter.LIMITE_DO_LOGIN)));

        assertThat(acima.statusCode()).isEqualTo(413);
        assertThat(dentro.statusCode()).isEqualTo(200);
    }

    /** O pedido de login válido, completado com espaços até ter exatamente o tamanho pedido. */
    private byte[] loginCom(ContaCriada conta, int tamanho) {
        byte[] base = json.writeValueAsBytes(Map.of("email", conta.email(), "senha", conta.senha()));
        byte[] completo = new byte[tamanho];
        System.arraycopy(base, 0, completo, 0, base.length);
        Arrays.fill(completo, base.length, tamanho, (byte) ' ');
        return completo;
    }

    /** Um corpo lido de um fluxo, que o cliente manda em partes, sem o tamanho no cabeçalho. */
    private HttpRequest emPartes(String caminho, byte[] corpo) {
        return pedido(caminho)
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(corpo)))
                .build();
    }

    private HttpRequest comTamanho(String caminho, byte[] corpo) {
        return pedido(caminho).POST(HttpRequest.BodyPublishers.ofByteArray(corpo)).build();
    }

    private HttpRequest.Builder pedido(String caminho) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + porta + caminho))
                .header("Content-Type", "application/json");
    }

    private HttpResponse<String> enviar(HttpRequest pedido) throws IOException, InterruptedException {
        return http.send(pedido, HttpResponse.BodyHandlers.ofString());
    }
}
