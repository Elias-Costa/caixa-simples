package br.com.caixasimples.contas.web;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.caixasimples.TesteDeIntegracao;
import br.com.caixasimples.contas.AutenticadorDeTeste;
import br.com.caixasimples.contas.CriadorDeContaDeTeste;
import br.com.caixasimples.contas.CriadorDeContaDeTeste.ContaCriada;
import br.com.caixasimples.contas.Plano;
import br.com.caixasimples.contas.internal.AssinaturaDePedido;
import br.com.caixasimples.shared.FusoDeReferencia;
import br.com.caixasimples.shared.Perfil;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * A troca de plano pela API, com o token de verdade: o caminho da tela do plano, os códigos de
 * status de cada recusa e a identidade que o aplicativo lê ao abrir.
 */
class PlanoHttpTest extends TesteDeIntegracao {

    private static final String SENHA = "uma senha longa de teste";

    @Autowired
    private MockMvc http;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private CriadorDeContaDeTeste criador;

    @Autowired
    private AutenticadorDeTeste autenticador;

    @Autowired
    private AssinaturaDePedido assinatura;

    @Test
    @DisplayName("o administrador pede, recebe o pedido e aplica o código; a identidade passa a trazer o plano")
    void pedidoECodigoPelaApi() throws Exception {
        ContaCriada conta = criador.criar("Cafeteria Aurora", SENHA);
        RequestPostProcessor admin = autenticador.como(conta);

        http.perform(get("/api/conta/plano").with(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plano").value("GRATIS"))
                .andExpect(jsonPath("$.situacao").value("SEM_MENSALIDADE"))
                .andExpect(jsonPath("$.vencimento").doesNotExist())
                .andExpect(jsonPath("$.recursos", empty()))
                .andExpect(jsonPath("$.mensalidadeCompleto").value(70.0))
                .andExpect(jsonPath("$.propostas.length()").value(2))
                .andExpect(jsonPath("$.propostas[1].tipo").value("ADESAO"))
                .andExpect(jsonPath("$.propostas[1].plano").value("COMPLETO"))
                .andExpect(jsonPath("$.pedidoAberto").doesNotExist());
        http.perform(get("/api/auth/eu").with(admin))
                .andExpect(jsonPath("$.plano").value("GRATIS"))
                .andExpect(jsonPath("$.situacaoDoPlano").value("SEM_MENSALIDADE"))
                .andExpect(jsonPath("$.vencimentoDoPlano").doesNotExist())
                .andExpect(jsonPath("$.recursos", empty()));

        JsonNode pedido = pedir(admin, "COMPLETO");
        UUID pedidoId = UUID.fromString(pedido.path("id").asText());
        String codigo = assinatura.codigo(pedidoId, Plano.COMPLETO);

        http.perform(aplicar(admin, pedidoId, "0000-0000-0000-0000"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("codigo")));
        // Do jeito que alguém digita: minúsculo e sem os hífens.
        http.perform(aplicar(admin, pedidoId, codigo.replace("-", "").toLowerCase()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plano").value("COMPLETO"))
                .andExpect(jsonPath("$.situacao").value("EM_DIA"))
                .andExpect(jsonPath("$.recursos",
                        containsInAnyOrder("RELATORIOS", "ESTOQUE", "MULTIUSUARIO")))
                .andExpect(jsonPath("$.vencimento").exists())
                .andExpect(jsonPath("$.pedidoAberto").doesNotExist());
        http.perform(aplicar(admin, pedidoId, codigo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plano").value("COMPLETO"));

        LocalDate hoje = LocalDate.now(FusoDeReferencia.DO_BALCAO);
        http.perform(get("/api/auth/eu").with(admin))
                .andExpect(jsonPath("$.plano").value("COMPLETO"))
                .andExpect(jsonPath("$.situacaoDoPlano").value("EM_DIA"))
                .andExpect(jsonPath("$.vencimentoDoPlano").exists())
                .andExpect(jsonPath("$.inicioDaSuspensao").exists())
                .andExpect(jsonPath("$.recursos",
                        containsInAnyOrder("RELATORIOS", "ESTOQUE", "MULTIUSUARIO")));
        http.perform(get("/api/relatorios/faturamento/dia").param("dia", hoje.toString())
                        .with(admin))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("pedido substituído responde 409, pedido de outra Conta 404, operador 403 e pedido sem plano 400")
    void recusasPorStatus() throws Exception {
        ContaCriada conta = criador.criar("Loja da Esquina", SENHA);
        ContaCriada outra = criador.criar("Loja do Outro Lado", SENHA);
        ContaCriada operador = criador.criar("Loja do Operador", SENHA, Perfil.OPERADOR, true);
        RequestPostProcessor admin = autenticador.como(conta);

        UUID primeiro = UUID.fromString(pedir(admin, "CAIXA_SIMPLES").path("id").asText());
        pedir(admin, "COMPLETO");

        http.perform(aplicar(admin, primeiro, assinatura.codigo(primeiro, Plano.CAIXA_SIMPLES)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("substituido")));
        http.perform(aplicar(autenticador.como(outra), primeiro,
                        assinatura.codigo(primeiro, Plano.CAIXA_SIMPLES)))
                .andExpect(status().isNotFound());
        http.perform(get("/api/conta/plano").with(autenticador.como(operador)))
                .andExpect(status().isForbidden());
        http.perform(post("/api/conta/plano/pedidos").with(autenticador.como(operador))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"plano\":\"COMPLETO\"}"))
                .andExpect(status().isForbidden());
        http.perform(post("/api/conta/plano/pedidos").with(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
        http.perform(post("/api/conta/plano/pedidos").with(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"plano\":\"GRATIS\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("no plano grátis, relatório e estoque respondem 409 com o motivo, e o operador continua com 403")
    void recursoForaDoPlanoResponde409() throws Exception {
        ContaCriada conta = criador.criar("Salão Aurora", SENHA);
        ContaCriada operador = criador.criar("Salão do Operador", SENHA, Perfil.OPERADOR, true);
        String hoje = LocalDate.now(FusoDeReferencia.DO_BALCAO).toString();

        http.perform(get("/api/relatorios/faturamento/dia").param("dia", hoje)
                        .with(autenticador.como(conta)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("Caixa Simples")));
        http.perform(get("/api/estoque/produtos").with(autenticador.como(conta)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("plano Completo")));
        http.perform(get("/api/relatorios/faturamento/dia").param("dia", hoje)
                        .with(autenticador.como(operador)))
                .andExpect(status().isForbidden());
    }

    private JsonNode pedir(RequestPostProcessor quem, String plano) throws Exception {
        String corpo = http.perform(post("/api/conta/plano/pedidos").with(quem)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"plano\":\"" + plano + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("/api/conta/plano/pedidos/")))
                .andExpect(jsonPath("$.situacao").value("ABERTO"))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(corpo);
    }

    private RequestBuilder aplicar(RequestPostProcessor quem, UUID pedidoId, String codigo) {
        return post("/api/conta/plano/pedidos/" + pedidoId + "/codigo").with(quem)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"codigo\":\"" + codigo + "\"}");
    }
}
