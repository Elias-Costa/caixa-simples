package br.com.caixasimples.pagamentos.internal;

import br.com.caixasimples.pagamentos.application.PixIndisponivelException;
import br.com.caixasimples.pagamentos.domain.CobrancaPix;
import br.com.caixasimples.pagamentos.domain.ConsultaPix;
import br.com.caixasimples.pagamentos.domain.PixGateway;
import br.com.caixasimples.shared.Money;
import br.com.caixasimples.shared.TenantContext;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Adapter Efí. Segredos e certificado são resolvidos por Conta somente no ambiente. */
@Component
class EfiPixGateway implements PixGateway {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PRODUCAO = "https://pix.api.efipay.com.br";
    private static final String HOMOLOGACAO = "https://pix-h.api.efipay.com.br";

    @Override
    public String chaveRecebedora() {
        return configuracao().chave();
    }

    @Override
    public String criarCobranca(CobrancaPix cobranca, Money valor) {
        Configuracao config = configuracao();
        if (!config.chave().equals(cobranca.chaveRecebedora())) {
            throw new IllegalStateException("chave Pix da Conta mudou durante a tentativa");
        }
        try {
            HttpClient http = cliente(config);
            String token = token(http, config);
            return criarOuRecuperar(http, config.base(), token, cobranca, valor, config.chave());
        } catch (PixIndisponivelException erro) {
            throw erro;
        } catch (InterruptedException erro) {
            Thread.currentThread().interrupt();
            throw new PixIndisponivelException("consulta Pix interrompida", erro);
        } catch (Exception erro) {
            throw new PixIndisponivelException("resultado da cobranca Pix incerto", erro);
        }
    }

    @Override
    public ConsultaPix consultar(CobrancaPix cobranca) {
        Configuracao config = configuracao();
        if (!config.chave().equals(cobranca.chaveRecebedora())) {
            throw new PixIndisponivelException("chave Pix da Conta mudou durante a tentativa");
        }
        try {
            HttpClient http = cliente(config);
            HttpResponse<String> resposta = enviar(http, config.base(), "GET",
                    "/v2/cob/" + cobranca.txid(), token(http, config), null);
            if (resposta.statusCode() != 200) {
                throw new PixIndisponivelException("consulta da cobranca Pix indisponivel");
            }
            return interpretarConsulta(JSON.readTree(resposta.body()));
        } catch (PixIndisponivelException erro) {
            throw erro;
        } catch (InterruptedException erro) {
            Thread.currentThread().interrupt();
            throw new PixIndisponivelException("consulta Pix interrompida", erro);
        } catch (Exception erro) {
            throw new PixIndisponivelException("consulta Pix incerta", erro);
        }
    }

    static ConsultaPix interpretarConsulta(JsonNode dados) {
        try {
            String txid = dados.path("txid").asText("");
            String chave = dados.path("chave").asText("");
            BigDecimal valor = new BigDecimal(dados.path("valor").path("original").asText());
            String status = dados.path("status").asText("");
            if (txid.isBlank() || chave.isBlank() || valor.signum() <= 0) {
                throw new PixIndisponivelException("consulta Pix sem correlacao verificavel");
            }
            if ("CONCLUIDA".equals(status)) {
                JsonNode recebidos = dados.path("pix");
                if (!recebidos.isArray() || recebidos.isEmpty()) {
                    throw new PixIndisponivelException("cobranca concluida sem Pix recebido verificavel");
                }
                BigDecimal soma = BigDecimal.ZERO;
                for (JsonNode recebido : recebidos) {
                    if (!txid.equals(recebido.path("txid").asText())) {
                        throw new PixIndisponivelException("Pix recebido com txid divergente");
                    }
                    soma = soma.add(new BigDecimal(recebido.path("valor").asText()));
                }
                if (soma.compareTo(valor) < 0) {
                    throw new PixIndisponivelException("Pix recebido nao cobre a cobranca");
                }
                return new ConsultaPix(txid, chave, Money.de(valor), true);
            }
            if (!"ATIVA".equals(status) && !status.startsWith("REMOVIDA_")) {
                throw new PixIndisponivelException("estado da cobranca Pix desconhecido");
            }
            return new ConsultaPix(txid, chave, Money.de(valor), false);
        } catch (PixIndisponivelException erro) {
            throw erro;
        } catch (Exception erro) {
            throw new PixIndisponivelException("consulta Pix com dados invalidos", erro);
        }
    }

    static String criarOuRecuperar(HttpClient http, String base, String token,
            CobrancaPix cobranca, Money valor, String chave) throws Exception {
        String caminho = "/v2/cob/" + cobranca.txid();
        String corpo = JSON.writeValueAsString(Map.of(
                "calendario", Map.of("expiracao", 900),
                "valor", Map.of("original", valor.valor().toPlainString()),
                "chave", chave));
        HttpResponse<String> resposta = enviar(http, base, "PUT", caminho, token, corpo);
        if (resposta.statusCode() == 409) {
            // O mesmo txid pode já ter sido criado antes da resposta anterior se perder.
            resposta = enviar(http, base, "GET", caminho, token, null);
        }
        if (resposta.statusCode() < 200 || resposta.statusCode() >= 300) {
            throw new PixIndisponivelException("nao foi possivel verificar a cobranca Pix");
        }
        JsonNode dados = JSON.readTree(resposta.body());
        if (!cobranca.txid().equals(dados.path("txid").asText())
                || !chave.equals(dados.path("chave").asText())
                || valor.valor().compareTo(new BigDecimal(dados.path("valor")
                        .path("original").asText("-1"))) != 0
                || !"ATIVA".equals(dados.path("status").asText())) {
            throw new PixIndisponivelException("cobranca Pix divergente ou sem estado ativo");
        }
        String copiaECola = dados.path("pixCopiaECola").asText("");
        if (copiaECola.isBlank()) {
            long locationId = dados.path("loc").path("id").asLong(0);
            if (locationId <= 0) {
                throw new PixIndisponivelException("cobranca Pix sem QR verificavel");
            }
            HttpResponse<String> qr = enviar(http, base, "GET",
                    "/v2/loc/" + locationId + "/qrcode", token, null);
            if (qr.statusCode() != 200) {
                throw new PixIndisponivelException("QR Pix indisponivel");
            }
            copiaECola = JSON.readTree(qr.body()).path("qrcode").asText("");
        }
        if (copiaECola.isBlank()) {
            throw new PixIndisponivelException("QR Pix vazio");
        }
        return copiaECola;
    }

    private static Configuracao configuracao() {
        String sufixo = TenantContext.exigirAtual().valor().toString().replace("-", "").toUpperCase();
        String prefixo = "CAIXA_SIMPLES_EFI_" + sufixo + "_";
        String ambiente = System.getenv(prefixo + "AMBIENTE");
        if (!"HOMOLOGACAO".equals(ambiente) && !"PRODUCAO".equals(ambiente)) {
            throw new IllegalStateException("ambiente Efí nao configurado para esta Conta");
        }
        return new Configuracao("PRODUCAO".equals(ambiente) ? PRODUCAO : HOMOLOGACAO,
                obrigatoria(prefixo + "CLIENT_ID"), obrigatoria(prefixo + "CLIENT_SECRET"),
                obrigatoria(prefixo + "CHAVE_PIX"), obrigatoria(prefixo + "CERTIFICADO_P12"),
                System.getenv().getOrDefault(prefixo + "CERTIFICADO_SENHA", ""));
    }

    private static String obrigatoria(String nome) {
        String valor = System.getenv(nome);
        if (valor == null || valor.isBlank()) {
            throw new IllegalStateException("configuracao Efí incompleta para esta Conta");
        }
        return valor;
    }

    private static HttpClient cliente(Configuracao config) throws Exception {
        KeyStore certificado = KeyStore.getInstance("PKCS12");
        char[] senha = config.senhaCertificado().toCharArray();
        try (InputStream arquivo = Files.newInputStream(Path.of(config.certificado()))) {
            certificado.load(arquivo, senha);
        }
        KeyManagerFactory chaves = KeyManagerFactory.getInstance(
                KeyManagerFactory.getDefaultAlgorithm());
        chaves.init(certificado, senha);
        SSLContext tls = SSLContext.getInstance("TLS");
        tls.init(chaves.getKeyManagers(), null, new SecureRandom());
        return HttpClient.newBuilder().sslContext(tls).connectTimeout(Duration.ofSeconds(10)).build();
    }

    private static String token(HttpClient http, Configuracao config) throws Exception {
        String basico = Base64.getEncoder().encodeToString((config.clientId() + ":"
                + config.clientSecret()).getBytes(StandardCharsets.UTF_8));
        HttpRequest pedido = HttpRequest.newBuilder(URI.create(config.base() + "/oauth/token"))
                .timeout(Duration.ofSeconds(15)).header("Authorization", "Basic " + basico)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"grant_type\":\"client_credentials\"}"))
                .build();
        HttpResponse<String> resposta = http.send(pedido, HttpResponse.BodyHandlers.ofString());
        if (resposta.statusCode() != 200) {
            throw new PixIndisponivelException("autorizacao Pix indisponivel");
        }
        String token = JSON.readTree(resposta.body()).path("access_token").asText("");
        if (token.isBlank()) {
            throw new PixIndisponivelException("autorizacao Pix sem token");
        }
        return token;
    }

    private static HttpResponse<String> enviar(HttpClient http, String base, String metodo,
            String caminho, String token, String corpo) throws Exception {
        HttpRequest.Builder pedido = HttpRequest.newBuilder(URI.create(base + caminho))
                .timeout(Duration.ofSeconds(15)).header("Authorization", "Bearer " + token)
                .header("Accept", "application/json");
        if ("PUT".equals(metodo)) {
            pedido.header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(corpo));
        } else {
            pedido.GET();
        }
        return http.send(pedido.build(), HttpResponse.BodyHandlers.ofString());
    }

    private record Configuracao(String base, String clientId, String clientSecret, String chave,
            String certificado, String senhaCertificado) {
    }
}
