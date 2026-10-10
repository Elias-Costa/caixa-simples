package br.com.caixasimples.shared.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.caixasimples.TesteDeIntegracao;
import br.com.caixasimples.contas.AutenticadorDeTeste;
import br.com.caixasimples.contas.CriadorDeContaDeTeste;
import br.com.caixasimples.contas.CriadorDeContaDeTeste.ContaCriada;
import br.com.caixasimples.sincronizacao.GestoDeTeste;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * O limite do corpo por rota, para o pedido que declara o tamanho.
 *
 * <p>O que chega em partes, sem tamanho declarado, só existe com o servidor de verdade e está em
 * {@link LimiteDoCorpoNaBordaTest}.
 */
class LimiteDoCorpoTest extends TesteDeIntegracao {

    private static final String SENHA = "uma senha longa de teste";

    @Autowired MockMvc http;
    @Autowired ObjectMapper json;
    @Autowired CriadorDeContaDeTeste criador;
    @Autowired AutenticadorDeTeste autenticador;

    @Test
    @DisplayName("o login aceita até 4 KB e recusa um byte a mais com 413, sem chegar à conferência")
    void loginNoLimite() throws Exception {
        ContaCriada conta = criador.criar("Loja da Esquina", SENHA);

        http.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(loginCom(conta, LimiteDoCorpoFilter.LIMITE_DO_LOGIN)))
                .andExpect(status().isOk());

        http.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(loginCom(conta, LimiteDoCorpoFilter.LIMITE_DO_LOGIN + 1)))
                .andExpect(status().isContentTooLarge())
                .andExpect(jsonPath("$.status").value(413))
                .andExpect(jsonPath("$.detail").value("O pedido tem mais de 4 KB, o limite desta rota."));
    }

    @Test
    @DisplayName("o login escrito com uma letra codificada continua com o limite do login")
    void caminhoCodificadoNaoEscapaDoLimite() throws Exception {
        ContaCriada conta = criador.criar("Loja da Esquina", SENHA);

        http.perform(post(URI.create("/api/auth/%6Cogin")).contentType(MediaType.APPLICATION_JSON)
                        .content(loginCom(conta, LimiteDoCorpoFilter.LIMITE_DO_LOGIN + 1)))
                .andExpect(status().isContentTooLarge());
    }

    /**
     * O pedido da auditoria: anônimo, de 40 MB, ao aviso de pagamento. É recusado pelo cabeçalho, sem
     * o corpo ser lido.
     */
    @Test
    @DisplayName("o aviso Pix anônimo de 40 MB recebe 413")
    void avisoPixDe40Mb() throws Exception {
        http.perform(post("/api/webhooks/pix/qualquer?hmac=errado")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new byte[40 * 1024 * 1024]))
                .andExpect(status().isContentTooLarge())
                .andExpect(jsonPath("$.detail").value("O pedido tem mais de 256 KB, o limite desta rota."));
    }

    @Test
    @DisplayName("o aviso Pix passa de 256 KB com um byte a mais e é recusado")
    void avisoPixNoLimite() throws Exception {
        http.perform(post("/api/webhooks/pix/qualquer?hmac=errado")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new byte[LimiteDoCorpoFilter.LIMITE_DO_AVISO_PIX + 1]))
                .andExpect(status().isContentTooLarge());
    }

    /**
     * Cem produtos criados sem rede, cada um com quarenta atributos: muito mais do que os formulários
     * do aplicativo costumam produzir, e ainda abaixo do limite.
     */
    @Test
    @DisplayName("um lote de cem gestos perto de 512 KB é aplicado inteiro")
    void loteGrandeAbaixoDoLimitePassa() throws Exception {
        ContaCriada conta = criador.criar("Mercearia da Rua", SENHA);
        RequestPostProcessor admin = autenticador.como(conta);
        byte[] corpo = loteDeProdutosComAtributos(100, 40, 100);
        assertThat(corpo.length).isBetween(480 * 1024, LimiteDoCorpoFilter.LIMITE_PADRAO);

        String resposta = http.perform(post("/api/sincronizacao").with(admin)
                        .contentType(MediaType.APPLICATION_JSON).content(corpo))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode resultados = json.readTree(resposta).path("resultados");
        assertThat(resultados).hasSize(100);
        resultados.forEach(resultado ->
                assertThat(resultado.path("resultado").asString()).isEqualTo("APLICADA"));
    }

    @Test
    @DisplayName("o lote com um byte além de 512 KB é recusado com 413, e nada é aplicado")
    void loteAcimaDoLimite() throws Exception {
        ContaCriada conta = criador.criar("Mercearia da Rua", SENHA);
        RequestPostProcessor admin = autenticador.como(conta);

        http.perform(post("/api/sincronizacao").with(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new byte[LimiteDoCorpoFilter.LIMITE_PADRAO + 1]))
                .andExpect(status().isContentTooLarge())
                .andExpect(jsonPath("$.detail").value("O pedido tem mais de 512 KB, o limite desta rota."));
    }

    @Test
    @DisplayName("as outras rotas da API têm o limite padrão, com ou sem token")
    void outraRotaComLimitePadrao() throws Exception {
        ContaCriada conta = criador.criar("Mercearia da Rua", SENHA);
        byte[] acima = new byte[LimiteDoCorpoFilter.LIMITE_PADRAO + 1];

        http.perform(post("/api/produtos").with(autenticador.como(conta))
                        .contentType(MediaType.APPLICATION_JSON).content(acima))
                .andExpect(status().isContentTooLarge());
        http.perform(post("/api/produtos").contentType(MediaType.APPLICATION_JSON).content(acima))
                .andExpect(status().isContentTooLarge());
    }

    /** O pedido de login válido, completado com espaços até ter exatamente o tamanho pedido. */
    private byte[] loginCom(ContaCriada conta, int tamanho) {
        byte[] base = json.writeValueAsBytes(Map.of("email", conta.email(), "senha", conta.senha()));
        byte[] completo = new byte[tamanho];
        System.arraycopy(base, 0, completo, 0, base.length);
        Arrays.fill(completo, base.length, tamanho, (byte) ' ');
        return completo;
    }

    private byte[] loteDeProdutosComAtributos(int produtos, int atributos, int tamanhoDoValor) {
        List<Map<String, Object>> operacoes = new ArrayList<>();
        for (int i = 0; i < produtos; i++) {
            Map<String, Object> pares = new LinkedHashMap<>();
            for (int j = 0; j < atributos; j++) {
                pares.put("atributo " + j, "a".repeat(tamanhoDoValor));
            }
            Map<String, Object> conteudo = GestoDeTeste.conteudo("tipo", "PRODUTO",
                    "nome", "Produto da carga " + i, "preco", new BigDecimal("4.75"),
                    "codigo", null, "categoria", null, "unidade", null, "atributos", pares);
            operacoes.add(GestoDeTeste.de("produto.criar", UUID.randomUUID(), conteudo).paraJson());
        }
        return json.writeValueAsString(Map.of("operacoes", operacoes)).getBytes(StandardCharsets.UTF_8);
    }
}
