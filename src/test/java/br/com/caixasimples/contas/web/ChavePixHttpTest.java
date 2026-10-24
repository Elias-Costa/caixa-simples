package br.com.caixasimples.contas.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.caixasimples.TesteDeIntegracao;
import br.com.caixasimples.contas.AutenticadorDeTeste;
import br.com.caixasimples.contas.CriadorDeContaDeTeste;
import br.com.caixasimples.contas.CriadorDeContaDeTeste.ContaCriada;
import br.com.caixasimples.contas.Plano;
import br.com.caixasimples.pagamentos.domain.PixGateway;
import br.com.caixasimples.shared.Perfil;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.ObjectMapper;

/**
 * A chave Pix da Conta pela API: só o administrador a troca, confirmando a senha atual pela mesma
 * contenção do login, e a identidade a entrega a todo aparelho da Conta, com a marca de que o Pix
 * integrado vale hoje.
 *
 * <p>A contagem da contenção vive no contexto do Spring, que é o da suíte inteira; cada teste usa uma
 * origem própria, para não somar as falhas de outro teste.
 */
@ExtendWith(OutputCaptureExtension.class)
class ChavePixHttpTest extends TesteDeIntegracao {

    private static final String SENHA = "uma senha longa de teste";
    private static final String ERRADA = "nao e a senha de ninguem";
    private static final String CPF = "529.982.247-25";

    @Autowired MockMvc http;
    @Autowired ObjectMapper json;
    @Autowired CriadorDeContaDeTeste criador;
    @Autowired AutenticadorDeTeste autenticador;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean PixGateway gateway;

    @Test
    @DisplayName("o administrador cadastra a chave com a senha atual; a configuração e a identidade a mostram, e o log não")
    void cadastraTrocaETira(CapturedOutput saida) throws Exception {
        String origem = "198.51.100.40";
        ContaCriada conta = criador.criar("Cafeteria Aurora", SENHA);
        RequestPostProcessor admin = autenticador.como(conta);

        definir(admin, SENHA, "CPF", CPF, " Vitória da Conquista ", origem)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tipoDaChavePix").value("CPF"))
                .andExpect(jsonPath("$.chavePix").value("52998224725"))
                .andExpect(jsonPath("$.cidadePix").value("Vitória da Conquista"));
        http.perform(get("/api/conta/configuracao").with(admin))
                .andExpect(jsonPath("$.chavePix").value("52998224725"));
        http.perform(get("/api/auth/eu").with(admin))
                .andExpect(jsonPath("$.chavePix").value("52998224725"))
                .andExpect(jsonPath("$.cidadePix").value("Vitória da Conquista"));

        definir(admin, SENHA, "EMAIL", "Caixa@Exemplo.COM", "Ilhéus", origem)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tipoDaChavePix").value("EMAIL"))
                .andExpect(jsonPath("$.chavePix").value("caixa@exemplo.com"));

        definir(admin, SENHA, null, "", null, origem)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chavePix").doesNotExist())
                .andExpect(jsonPath("$.cidadePix").doesNotExist())
                .andExpect(jsonPath("$.tipoDaChavePix").doesNotExist());
        http.perform(get("/api/auth/eu").with(admin))
                .andExpect(jsonPath("$.chavePix").doesNotExist())
                .andExpect(jsonPath("$.cidadePix").doesNotExist());

        String quem = "conta=" + conta.contaId() + " usuario=" + conta.usuarioId();
        assertThat(saida.getAll())
                .contains("evento=configuracao.alterada " + quem + " campo=chave_pix origem="
                        + origem)
                .doesNotContain("52998224725")
                .doesNotContain("caixa@exemplo.com")
                .doesNotContain("Conquista");
    }

    @Test
    @DisplayName("o operador vê a chave na identidade, para montar o QR, mas não a troca, nem com a senha certa")
    void operadorNaoTroca() throws Exception {
        ContaCriada operador = criador.criar("Padaria Aurora", SENHA, Perfil.OPERADOR, true);
        // A Conta do operador recebe a chave por fora: ele não teria como cadastrá-la.
        jdbc.update("UPDATE conta SET chave_pix = ?, cidade_pix = ? WHERE id = ?",
                "52998224725", "Salvador", operador.contaId().valor());
        RequestPostProcessor token = autenticador.como(operador);

        definir(token, SENHA, "EMAIL", "outra@exemplo.com", "Salvador", "198.51.100.41")
                .andExpect(status().isForbidden());
        http.perform(get("/api/conta/configuracao").with(token))
                .andExpect(status().isForbidden());
        http.perform(get("/api/auth/eu").with(token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chavePix").value("52998224725"))
                .andExpect(jsonPath("$.cidadePix").value("Salvador"));
    }

    @Test
    @DisplayName("a senha atual errada responde 400 sem gravar, vai ao log e conta na contenção do login")
    void senhaErradaContaNaContencao(CapturedOutput saida) throws Exception {
        String origem = "198.51.100.42";
        ContaCriada conta = criador.criar("Mercearia Aurora", SENHA);
        RequestPostProcessor admin = autenticador.como(conta);

        for (int i = 0; i < 10; i++) {
            definir(admin, ERRADA, "CPF", CPF, "Salvador", origem)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail").value("A senha atual não confere."));
        }
        definir(admin, SENHA, "CPF", CPF, "Salvador", origem)
                .andExpect(status().isTooManyRequests());

        http.perform(get("/api/conta/configuracao").with(admin))
                .andExpect(jsonPath("$.chavePix").doesNotExist());
        assertThat(saida.getAll()).contains("evento=chave_pix.troca_recusada conta="
                + conta.contaId() + " usuario=" + conta.usuarioId()
                + " motivo=senha_atual origem=" + origem);
    }

    @Test
    @DisplayName("a chave que não serve ao tipo é recusada antes da senha, sem gastar tentativa da contenção")
    void chaveInvalidaNaoGastaTentativa() throws Exception {
        String origem = "198.51.100.43";
        ContaCriada conta = criador.criar("Empório Aurora", SENHA);
        RequestPostProcessor admin = autenticador.como(conta);

        for (int i = 0; i < 6; i++) {
            definir(admin, ERRADA, "CPF", "529.982.247-24", "Salvador", origem)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail")
                            .value("Chave Pix: o CPF não confere com os dígitos verificadores."));
            definir(admin, ERRADA, "CPF", CPF, " ", origem)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail")
                            .value("Chave Pix: informe a cidade do recebedor."));
        }
        definir(admin, SENHA, "TELEFONE", "(71) 99123-4567", "Salvador", origem)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chavePix").value("+5571991234567"));
    }

    @Test
    @DisplayName("a identidade só marca o Pix integrado no Completo em dia, com o provedor configurado")
    void marcaDoPixIntegrado() throws Exception {
        when(gateway.configurada()).thenReturn(true);
        ContaCriada gratuita = criador.criar("Lanchonete Aurora", SENHA);
        ContaCriada completa = criador.criar("Quitanda Aurora", SENHA);
        criador.contratar(completa.contaId(), Plano.COMPLETO);
        ContaCriada suspensa = criador.criar("Armazém Aurora", SENHA);
        criador.contratar(suspensa.contaId(), Plano.COMPLETO);
        criador.vencerPlano(suspensa.contaId(), 8);

        pixIntegrado(gratuita).andExpect(jsonPath("$.pixIntegrado").value(false));
        pixIntegrado(completa).andExpect(jsonPath("$.pixIntegrado").value(true))
                .andExpect(jsonPath("$.recursos").value(org.hamcrest.Matchers.hasItem(
                        "PIX_INTEGRADO")));
        pixIntegrado(suspensa).andExpect(jsonPath("$.pixIntegrado").value(false));

        when(gateway.configurada()).thenReturn(false);
        pixIntegrado(completa).andExpect(jsonPath("$.pixIntegrado").value(false));
    }

    private ResultActions pixIntegrado(ContaCriada conta) throws Exception {
        return http.perform(get("/api/auth/eu").with(autenticador.como(conta)))
                .andExpect(status().isOk());
    }

    private ResultActions definir(RequestPostProcessor quem, String senhaAtual, String tipo,
            String chave, String cidade, String origem) throws Exception {
        Map<String, Object> corpo = new LinkedHashMap<>();
        corpo.put("senhaAtual", senhaAtual);
        corpo.put("tipo", tipo);
        corpo.put("chave", chave);
        corpo.put("cidade", cidade);
        return http.perform(put("/api/conta/configuracao/chave-pix").with(quem)
                .with(requisicao -> {
                    requisicao.setRemoteAddr(origem);
                    return requisicao;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(corpo)));
    }
}
