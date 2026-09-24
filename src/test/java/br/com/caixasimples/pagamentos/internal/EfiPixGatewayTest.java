package br.com.caixasimples.pagamentos.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import br.com.caixasimples.pagamentos.application.PixIndisponivelException;
import br.com.caixasimples.pagamentos.domain.CobrancaPix;
import br.com.caixasimples.shared.Money;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Exercita o contrato HTTP da Efí sem credenciais de uma Conta real. */
class EfiPixGatewayTest {
    @Test
    void putRepetidoConsultaMesmoTxidEConfereValorEChave() throws Exception {
        UUID pagamentoId = UUID.randomUUID();
        CobrancaPix cobranca = CobrancaPix.aguardando(pagamentoId, "chave-teste",
                Instant.now().plusSeconds(900));
        AtomicInteger put = new AtomicInteger();
        AtomicInteger get = new AtomicInteger();
        AtomicReference<String> corpo = new AtomicReference<>();
        HttpServer servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servidor.createContext("/v2/cob/" + cobranca.txid(), troca -> {
            int status;
            String resposta;
            if ("PUT".equals(troca.getRequestMethod())) {
                corpo.set(new String(troca.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                status = put.incrementAndGet() == 1 ? 201 : 409;
            } else {
                get.incrementAndGet();
                status = 200;
            }
            resposta = status == 409 ? "{\"nome\":\"txid_duplicado\"}"
                    : "{\"txid\":\"" + cobranca.txid() + "\",\"chave\":\"chave-teste\","
                    + "\"valor\":{\"original\":\"12.50\"},\"status\":\"ATIVA\","
                    + "\"pixCopiaECola\":\"codigo-pix\"}";
            byte[] bytes = resposta.getBytes(StandardCharsets.UTF_8);
            troca.sendResponseHeaders(status, bytes.length);
            try (var saida = troca.getResponseBody()) { saida.write(bytes); }
        });
        servidor.start();
        try {
            String base = "http://127.0.0.1:" + servidor.getAddress().getPort();
            HttpClient http = HttpClient.newHttpClient();
            for (int i = 0; i < 2; i++) {
                assertThat(EfiPixGateway.criarOuRecuperar(http, base, "token", cobranca,
                        Money.de("12.50"), "chave-teste")).isEqualTo("codigo-pix");
            }
            assertThat(put).hasValue(2);
            assertThat(get).hasValue(1);
            assertThat(corpo.get()).contains("\"expiracao\":900", "\"original\":\"12.50\"");
            assertThatExceptionOfType(PixIndisponivelException.class).isThrownBy(() ->
                    EfiPixGateway.criarOuRecuperar(http, base, "token", cobranca,
                            Money.de("12.00"), "chave-teste"));
        } finally {
            servidor.stop(0);
        }
    }

    @Test
    void patchDeRemocaoUsaStatusDocumentadoEAceitaRepeticaoJaResolvida() throws Exception {
        CobrancaPix cobranca = CobrancaPix.aguardando(UUID.randomUUID(), "chave-teste",
                Instant.now().plusSeconds(900));
        AtomicInteger chamadas = new AtomicInteger();
        AtomicReference<String> corpo = new AtomicReference<>();
        HttpServer servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servidor.createContext("/v2/cob/" + cobranca.txid(), troca -> {
            corpo.set(new String(troca.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            int status = chamadas.incrementAndGet() == 1 ? 200 : 400;
            byte[] bytes = "{}".getBytes(StandardCharsets.UTF_8);
            troca.sendResponseHeaders(status, bytes.length);
            try (var saida = troca.getResponseBody()) { saida.write(bytes); }
        });
        servidor.start();
        try {
            String base = "http://127.0.0.1:" + servidor.getAddress().getPort();
            HttpClient http = HttpClient.newHttpClient();
            EfiPixGateway.remover(http, base, "token", cobranca);
            EfiPixGateway.remover(http, base, "token", cobranca);
            assertThat(chamadas).hasValue(2);
            assertThat(corpo.get()).contains("REMOVIDA_PELO_USUARIO_RECEBEDOR");
        } finally {
            servidor.stop(0);
        }
    }
}
