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
import br.com.caixasimples.contas.CriadorDeContaDeTeste.UsuarioCriado;
import br.com.caixasimples.contas.Plano;
import br.com.caixasimples.pagamentos.FormaPagamento;
import br.com.caixasimples.shared.FusoDeReferencia;
import br.com.caixasimples.shared.Money;
import br.com.caixasimples.shared.Perfil;
import br.com.caixasimples.vendas.CriadorDeVendaDeTeste;
import br.com.caixasimples.vendas.CriadorDeVendaDeTeste.ItemDeTeste;
import br.com.caixasimples.vendas.CriadorDeVendaDeTeste.ParcelaDeTeste;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * O contrato HTTP do faturamento preserva o isolamento e a recusa por perfil (RF21, RNF05), e a
 * rota do período aceita os filtros por forma e operador (RF24).
 */
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
        criador.contratar(contaA.contaId(), Plano.CAIXA_SIMPLES);
        ContaCriada contaB = criador.criar("Loja do Relatório B", SENHA);
        criador.contratar(contaB.contaId(), Plano.CAIXA_SIMPLES);
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
        criador.contratar(admin.contaId(), Plano.CAIXA_SIMPLES);
        http.perform(get("/api/relatorios/faturamento")
                        .param("inicio", DIA.toString())
                        .param("fim", DIA.minusDays(1).toString())
                        .with(autenticador.como(admin)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rotaDoPeriodoFiltraPorFormaEOperadorSemAtravessarContas() throws Exception {
        ContaCriada conta = criador.criar("Loja dos Filtros", SENHA);
        criador.contratar(conta.contaId(), Plano.CAIXA_SIMPLES);
        ContaCriada outra = criador.criar("Loja de Fora", SENHA);
        UsuarioCriado operador = criador.criarOperadorEm(conta.contaId(), "Atendente");
        Cenario doTitular = prepararCenario(conta);
        UUID caixaDoOperador = operador.comoUsuario(() -> sessoes.abrir(Money.ZERO));
        Instant noDia = FusoDeReferencia.inicioDoDia(DIA).plusSeconds(3600);

        // O titular vende trinta, dez em dinheiro e vinte em Pix; o operador, sete no cartão.
        vendas.criarConcluidaComParcelasEm(conta.contaId(), doTitular.sessaoId(),
                conta.usuarioId(),
                List.of(ItemDeTeste.unitario(doTitular.produtoId(), Money.de("30.00"))),
                List.of(ParcelaDeTeste.confirmada(FormaPagamento.DINHEIRO, Money.de("10.00")),
                        ParcelaDeTeste.confirmada(FormaPagamento.PIX, Money.de("20.00"))),
                noDia);
        vendas.criarConcluidaComParcelasEm(conta.contaId(), caixaDoOperador,
                operador.usuarioId(),
                List.of(ItemDeTeste.unitario(doTitular.produtoId(), Money.de("7.00"))),
                List.of(ParcelaDeTeste.confirmada(FormaPagamento.CARTAO, Money.de("7.00"))),
                noDia.plusSeconds(60));

        esperar(faturamentoDoDia(conta, null, null), 37.0, 2);
        // As quatro formas somam o total; a venda dividida conta uma vez em cada forma que usou.
        esperar(faturamentoDoDia(conta, "DINHEIRO", null), 10.0, 1);
        esperar(faturamentoDoDia(conta, "PIX", null), 20.0, 1);
        esperar(faturamentoDoDia(conta, "CARTAO", null), 7.0, 1);
        esperar(faturamentoDoDia(conta, "FIADO", null), 0.0, 0);
        esperar(faturamentoDoDia(conta, null, operador.usuarioId()), 7.0, 1);
        esperar(faturamentoDoDia(conta, "PIX", conta.usuarioId()), 20.0, 1);
        esperar(faturamentoDoDia(conta, "PIX", operador.usuarioId()), 0.0, 0);
        // O id existe, mas é de outra Conta: zero, e não a venda de lá nem erro (RNF05).
        esperar(faturamentoDoDia(conta, null, outra.usuarioId()), 0.0, 0);

        faturamentoDoDia(conta, "BOLETO", null).andExpect(status().isBadRequest());
        http.perform(get("/api/relatorios/faturamento")
                        .param("inicio", DIA.toString()).param("fim", DIA.toString())
                        .param("operadorId", "nao-e-um-id")
                        .with(autenticador.como(conta)))
                .andExpect(status().isBadRequest());
    }

    /** O faturamento do dia pela rota do período, com os filtros que não forem nulos. */
    private ResultActions faturamentoDoDia(ContaCriada conta, String forma, UUID operadorId)
            throws Exception {
        MockHttpServletRequestBuilder pedido = get("/api/relatorios/faturamento")
                .param("inicio", DIA.toString())
                .param("fim", DIA.toString())
                .with(autenticador.como(conta));
        if (forma != null) {
            pedido.param("forma", forma);
        }
        if (operadorId != null) {
            pedido.param("operadorId", operadorId.toString());
        }
        return http.perform(pedido);
    }

    private static void esperar(ResultActions resposta, double total, int quantidadeDeVendas)
            throws Exception {
        resposta.andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(total))
                .andExpect(jsonPath("$.quantidadeDeVendas").value(quantidadeDeVendas));
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
