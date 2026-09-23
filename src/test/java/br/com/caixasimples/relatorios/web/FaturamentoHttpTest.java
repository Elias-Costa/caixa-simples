package br.com.caixasimples.relatorios.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.caixasimples.TesteDeIntegracao;
import br.com.caixasimples.cadastro.TipoProduto;
import br.com.caixasimples.cadastro.application.ProdutoService;
import br.com.caixasimples.cadastro.application.ProdutoService.DadosDoProduto;
import br.com.caixasimples.caixa.application.SessaoCaixaService;
import br.com.caixasimples.contas.AutenticadorDeTeste;
import br.com.caixasimples.contas.CriadorDeContaDeTeste;
import br.com.caixasimples.contas.CriadorDeContaDeTeste.ContaCriada;
import br.com.caixasimples.shared.FusoDeReferencia;
import br.com.caixasimples.shared.Money;
import br.com.caixasimples.shared.Perfil;
import br.com.caixasimples.vendas.CriadorDeVendaDeTeste;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/** O contrato HTTP do faturamento preserva o isolamento e a recusa por perfil (RF21, RNF05). */
class FaturamentoHttpTest extends TesteDeIntegracao {

    private static final String SENHA = "uma senha longa de teste";
    private static final LocalDate DIA = LocalDate.of(2026, 9, 15);

    @Autowired MockMvc http;
    @Autowired CriadorDeContaDeTeste criador;
    @Autowired AutenticadorDeTeste autenticador;
    @Autowired SessaoCaixaService sessoes;
    @Autowired ProdutoService produtos;
    @Autowired CriadorDeVendaDeTeste vendas;

    @Test
    void administradorConsultaDiaEPeriodoSemLerOutraConta() throws Exception {
        ContaCriada contaA = criador.criar("Loja do Relatório A", SENHA);
        ContaCriada contaB = criador.criar("Loja do Relatório B", SENHA);
        Cenario cenarioA = prepararCenario(contaA);
        Cenario cenarioB = prepararCenario(contaB);
        prepararVenda(contaA, cenarioA, DIA, "10.00");
        prepararVenda(contaA, cenarioA, DIA.plusDays(1), "20.00");
        prepararVenda(contaB, cenarioB, DIA, "7.00");

        http.perform(get("/api/relatorios/faturamento/dia").param("dia", DIA.toString())
                        .with(autenticador.como(contaA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inicio").value(DIA.toString()))
                .andExpect(jsonPath("$.fim").value(DIA.toString()))
                .andExpect(jsonPath("$.total").value(10.0))
                .andExpect(jsonPath("$.quantidadeDeVendas").value(1));
        http.perform(get("/api/relatorios/faturamento")
                        .param("inicio", DIA.toString())
                        .param("fim", DIA.plusDays(1).toString())
                        .with(autenticador.como(contaA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(30.0))
                .andExpect(jsonPath("$.quantidadeDeVendas").value(2));
        http.perform(get("/api/relatorios/faturamento/dia").param("dia", DIA.toString())
                        .with(autenticador.como(contaB)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(7.0))
                .andExpect(jsonPath("$.quantidadeDeVendas").value(1));
    }

    @Test
    void operadorRecebe403NasDuasConsultas() throws Exception {
        ContaCriada operador = criador.criar("Loja do Operador", SENHA, Perfil.OPERADOR, true);

        http.perform(get("/api/relatorios/faturamento/dia").param("dia", DIA.toString())
                        .with(autenticador.como(operador)))
                .andExpect(status().isForbidden());
        http.perform(get("/api/relatorios/faturamento")
                        .param("inicio", DIA.toString()).param("fim", DIA.toString())
                        .with(autenticador.como(operador)))
                .andExpect(status().isForbidden());
    }

    @Test
    void periodoInvertidoRecebe400() throws Exception {
        ContaCriada admin = criador.criar("Loja do Período", SENHA);
        http.perform(get("/api/relatorios/faturamento")
                        .param("inicio", DIA.toString())
                        .param("fim", DIA.minusDays(1).toString())
                        .with(autenticador.como(admin)))
                .andExpect(status().isBadRequest());
    }

    private Cenario prepararCenario(ContaCriada conta) {
        return conta.comoUsuario(() -> new Cenario(sessoes.abrir(Money.ZERO),
                produtos.cadastrar(TipoProduto.PRODUTO,
                        new DadosDoProduto("Produto do relatório", Money.de("1.00"), null, null,
                                "un", null))));
    }

    private void prepararVenda(ContaCriada conta, Cenario cenario, LocalDate dia, String valor) {
        vendas.criarConcluidaEm(conta.contaId(), cenario.sessaoId(), conta.usuarioId(),
                cenario.produtoId(),
                Money.de(valor), FusoDeReferencia.inicioDoDia(dia).plusSeconds(3600));
    }

    private record Cenario(UUID sessaoId, UUID produtoId) {
    }
}
