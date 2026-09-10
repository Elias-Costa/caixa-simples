package br.com.caixasimples.contas.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Adapter da API Pwned Passwords, do Have I Been Pwned.
 *
 * <p>Usa k-anonimato: envia apenas os <strong>5 primeiros caracteres</strong> do SHA-1 da senha e
 * recebe de volta a faixa de sufixos correspondente, comparada aqui. A senha, e mesmo o hash
 * completo dela, nunca saem deste servidor. A API é gratuita e não exige chave.
 *
 * <p>O SHA-1 aqui não é escolha de segurança, e sim o formato que a API define. O armazenamento da
 * senha continua sendo BCrypt.
 */
@Component
class PwnedPasswordsClient implements VerificadorDeSenhaVazada {

    private static final String URL_BASE = "https://api.pwnedpasswords.com/range/";

    /**
     * Timeout curto de propósito: esta chamada fica no caminho de criar conta ou trocar senha, e
     * sem limite uma indisponibilidade da API travaria a operação em vez de recusá-la. Como a
     * política é falhar fechado, esgotar o tempo tem o mesmo efeito de a API dizer que não sabe.
     */
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final RestClient http;

    PwnedPasswordsClient() {
        SimpleClientHttpRequestFactory fabrica = new SimpleClientHttpRequestFactory();
        fabrica.setConnectTimeout(TIMEOUT);
        fabrica.setReadTimeout(TIMEOUT);
        this.http = RestClient.builder().baseUrl(URL_BASE).requestFactory(fabrica).build();
    }

    @Override
    public void exigirNaoVazada(String senha) {
        String hash = sha1EmMaiusculas(senha);
        String prefixo = hash.substring(0, 5);
        String sufixoProcurado = hash.substring(5);

        String faixa;
        try {
            faixa = http.get().uri(prefixo).retrieve().body(String.class);
        } catch (RestClientException erroDeRede) {
            // Falha fechada: sem conseguir verificar, nenhuma senha entra.
            throw new SenhaRecusadaException(
                    "Nao foi possivel verificar a senha contra a lista de vazamentos", erroDeRede);
        }

        if (faixa == null) {
            throw new SenhaRecusadaException(
                    "Nao foi possivel verificar a senha contra a lista de vazamentos");
        }

        // Cada linha vem no formato sufixo, dois-pontos, quantidade. Só interessa se o sufixo
        // aparece; a contagem de ocorrências não muda a decisão.
        for (String linha : faixa.split("\\R")) {
            int separador = linha.indexOf(':');
            String sufixo = separador < 0 ? linha.trim() : linha.substring(0, separador).trim();
            if (sufixo.equalsIgnoreCase(sufixoProcurado)) {
                throw new SenhaRecusadaException(
                        "Esta senha aparece em vazamentos publicos conhecidos. Escolha outra.");
            }
        }
    }

    private static String sha1EmMaiusculas(String senha) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] digest = sha1.digest(senha.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().withUpperCase().formatHex(digest);
        } catch (NoSuchAlgorithmException impossivel) {
            // SHA-1 é obrigatório em toda JVM.
            throw new IllegalStateException("JVM sem SHA-1", impossivel);
        }
    }
}
