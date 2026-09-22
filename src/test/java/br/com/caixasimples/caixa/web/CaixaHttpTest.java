package br.com.caixasimples.caixa.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.caixasimples.TesteDeIntegracao;
import br.com.caixasimples.contas.AutenticadorDeTeste;
import br.com.caixasimples.contas.CriadorDeContaDeTeste;
import br.com.caixasimples.contas.CriadorDeContaDeTeste.ContaCriada;
import br.com.caixasimples.contas.CriadorDeContaDeTeste.UsuarioCriado;
import br.com.caixasimples.shared.FusoDeReferencia;
import br.com.caixasimples.shared.Money;
import br.com.caixasimples.shared.Perfil;
import br.com.caixasimples.caixa.application.SessaoCaixaService;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.ObjectMapper;

/** A tela chama o mesmo contrato HTTP que protege o próprio caixa (RF13 a RF16, RNF05). */
class CaixaHttpTest extends TesteDeIntegracao {

    private static final String SENHA = "uma senha longa de teste";

    @Autowired MockMvc http;
    @Autowired CriadorDeContaDeTeste criador;
    @Autowired AutenticadorDeTeste autenticador;
    @Autowired SessaoCaixaService sessoes;
    @Autowired ObjectMapper json;

    @Test
    void operadorAbreMovimentaFechaEConsultaHistorico() throws Exception {
        ContaCriada conta = criador.criar("Loja do Caixa", SENHA, Perfil.OPERADOR, true);
        RequestPostProcessor operador = autenticador.como(conta);
        http.perform(get("/api/caixa/sessoes/aberta").with(operador))
                .andExpect(status().isNoContent());

        UUID id = abrir(operador, "20.00");
        http.perform(get("/api/caixa/sessoes/aberta").with(operador))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.valorFechamentoEsperado").value(20.0));
        http.perform(post("/api/caixa/sessoes").with(operador)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"valorAbertura\":0}"))
                .andExpect(status().isConflict());
        http.perform(post("/api/caixa/sessoes/{id}/suprimentos", id).with(operador)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"valor\":10.00,\"motivo\":\"Troco\"}"))
                .andExpect(status().isNoContent());
        http.perform(post("/api/caixa/sessoes/{id}/sangrias", id).with(operador)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"valor\":5.00,\"motivo\":\"Retirada\"}"))
                .andExpect(status().isNoContent());
        http.perform(get("/api/caixa/sessoes/{id}", id).with(operador))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valorFechamentoEsperado").value(25.0))
                .andExpect(jsonPath("$.movimentos.length()").value(2))
                .andExpect(jsonPath("$.movimentos[0].tipo").value("SUPRIMENTO"))
                .andExpect(jsonPath("$.movimentos[0].motivo").value("Troco"))
                .andExpect(jsonPath("$.movimentos[1].tipo").value("SANGRIA"));
        http.perform(post("/api/caixa/sessoes/{id}/fechamento", id).with(operador)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"valorContado\":23.00}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.diferenca").value(2.0));
        http.perform(get("/api/caixa/sessoes/aberta").with(operador))
                .andExpect(status().isNoContent());
        String dia = LocalDate.now(FusoDeReferencia.DO_BALCAO).toString();
        http.perform(get("/api/caixa/sessoes").param("dia", dia).with(operador))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(id.toString()))
                .andExpect(jsonPath("$[0].diferenca").value(2.0));
    }

    @Test
    void administradorFechaCaixaDoOperadorEOperadorNaoVeODeColega() throws Exception {
        ContaCriada conta = criador.criar("Loja de Dois Caixas", SENHA);
        UsuarioCriado operador = criador.criarOperadorEm(conta.contaId(), "Atendente");
        UUID id = operador.comoUsuario(() -> sessoes.abrir(Money.de("10.00")));
        RequestPostProcessor admin = autenticador.como(conta);
        http.perform(get("/api/caixa/sessoes/{id}", id).with(admin))
                .andExpect(status().isOk());
        http.perform(get("/api/caixa/sessoes").param("dia",
                        LocalDate.now(FusoDeReferencia.DO_BALCAO).toString()).with(admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(id.toString()));
        http.perform(post("/api/caixa/sessoes/{id}/fechamento", id).with(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"valorContado\":10.00}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.diferenca").value(0.0));

        ContaCriada outra = criador.criar("Loja do Operador", SENHA, Perfil.OPERADOR, true);
        UsuarioCriado colega = criador.criarAdminEm(outra.contaId(), "Outro atendente");
        UUID caixaDoColega = colega.comoUsuario(() -> sessoes.abrir(Money.de("5.00")));
        RequestPostProcessor tokenOperador = autenticador.como(outra);
        http.perform(get("/api/caixa/sessoes/{id}", caixaDoColega).with(tokenOperador))
                .andExpect(status().isForbidden());
        http.perform(post("/api/caixa/sessoes/{id}/fechamento", caixaDoColega)
                        .with(tokenOperador).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"valorContado\":5.00}"))
                .andExpect(status().isForbidden());
        http.perform(get("/api/caixa/sessoes").param("dia",
                        LocalDate.now(FusoDeReferencia.DO_BALCAO).toString())
                        .param("operadorId", colega.usuarioId().toString()).with(tokenOperador))
                .andExpect(status().isForbidden());
    }

    @Test
    void outraContaNaoEnxergaSessaoNemAchaSuaSessaoAberta() throws Exception {
        ContaCriada contaA = criador.criar("Caixa A", SENHA);
        ContaCriada contaB = criador.criar("Caixa B", SENHA);
        UUID id = abrir(autenticador.como(contaA), "8.00");
        RequestPostProcessor outraConta = autenticador.como(contaB);
        http.perform(get("/api/caixa/sessoes/aberta").with(outraConta))
                .andExpect(status().isNoContent());
        http.perform(get("/api/caixa/sessoes/{id}", id).with(outraConta))
                .andExpect(status().isNotFound());
        http.perform(get("/api/caixa/sessoes").param("dia",
                        LocalDate.now(FusoDeReferencia.DO_BALCAO).toString()).with(outraConta))
                .andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void pedidoInvalidoRecebe400ComCampo() throws Exception {
        RequestPostProcessor admin = autenticador.como(criador.criar("Caixa Validado", SENHA));
        http.perform(post("/api/caixa/sessoes").with(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"valorAbertura\":-1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos.valorAbertura").exists());
    }

    private UUID abrir(RequestPostProcessor usuario, String valor) throws Exception {
        String corpo = http.perform(post("/api/caixa/sessoes").with(usuario)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"valorAbertura\":" + valor + "}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(corpo).get("id").asText());
    }
}
