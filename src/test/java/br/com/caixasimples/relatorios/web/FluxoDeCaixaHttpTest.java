package br.com.caixasimples.relatorios.web;

import static br.com.caixasimples.caixa.CriadorDeSessaoCaixaDeTeste.lancado;
import static br.com.caixasimples.caixa.CriadorDeSessaoCaixaDeTeste.lancadoDaVenda;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.caixasimples.TesteDeIntegracao;
import br.com.caixasimples.cadastro.TipoProduto;
import br.com.caixasimples.cadastro.application.ProdutoService;
import br.com.caixasimples.cadastro.application.ProdutoService.DadosDoProduto;
import br.com.caixasimples.cadastro.internal.ClienteService;
import br.com.caixasimples.cadastro.internal.ClienteService.DadosDoCliente;
import br.com.caixasimples.caixa.CriadorDeSessaoCaixaDeTeste;
import br.com.caixasimples.caixa.TipoMovimentoCaixa;
import br.com.caixasimples.caixa.application.SessaoCaixaService;
import br.com.caixasimples.contas.AutenticadorDeTeste;
import br.com.caixasimples.contas.CriadorDeContaDeTeste;
import br.com.caixasimples.contas.CriadorDeContaDeTeste.ContaCriada;
import br.com.caixasimples.contas.Plano;
import br.com.caixasimples.pagamentos.FormaPagamento;
import br.com.caixasimples.pagamentos.domain.SolicitacaoPagamento;
import br.com.caixasimples.shared.FusoDeReferencia;
import br.com.caixasimples.shared.Money;
import br.com.caixasimples.shared.Perfil;
import br.com.caixasimples.vendas.CriadorDeVendaDeTeste;
import br.com.caixasimples.vendas.application.VendaService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * O contrato HTTP do fluxo de caixa: cada tipo de movimento no seu campo, os consolidados,
 * isolamento entre Contas e recusas (RF23, RNF05).
 *
 * <p>Os valores de cada tipo são diferentes entre si de propósito: um campo trocado na resposta
 * aparece como número errado, e não passa por coincidência. O recebimento de fiado vem do caminho
 * de verdade, uma Venda fiada recebida em dinheiro, porque o movimento dele aponta para o
 * recebimento gravado; os outros quatro tipos vêm da fixture, que fixa o instante de cada um.
 */
class FluxoDeCaixaHttpTest extends TesteDeIntegracao {

    private static final String SENHA = "uma senha longa de teste";

    @Autowired MockMvc http;
    @Autowired CriadorDeContaDeTeste criador;
    @Autowired AutenticadorDeTeste autenticador;
    @Autowired SessaoCaixaService caixas;
    @Autowired ProdutoService produtos;
    @Autowired ClienteService clientes;
    @Autowired VendaService vendaService;
    @Autowired CriadorDeVendaDeTeste vendas;
    @Autowired CriadorDeSessaoCaixaDeTeste sessoes;

    @Test
    void administradorConsultaCadaTipoNoSeuCampoSemLerOutraConta() throws Exception {
        // O recebimento grava o instante corrente; o resto do cenário vai para o mesmo dia.
        LocalDate hoje = LocalDate.now(FusoDeReferencia.DO_BALCAO);
        Instant inicioDeHoje = FusoDeReferencia.inicioDoDia(hoje);
        ContaCriada contaA = criador.criar("Cafeteria Aurora", SENHA);
        criador.contratar(contaA.contaId(), Plano.CAIXA_SIMPLES);
        ContaCriada contaB = criador.criar("Loja da Esquina", SENHA);
        criador.contratar(contaB.contaId(), Plano.CAIXA_SIMPLES);

        UUID caixaAberto = contaA.comoUsuario(() -> caixas.abrir(Money.ZERO));
        receberFiadoEmDinheiro(contaA, caixaAberto, "7.00");
        UUID venda = vendas.criarAbertaEm(contaA.contaId(), caixaAberto, contaA.usuarioId());
        sessoes.criarFechadaComMovimentos(contaA.contaId(), contaA.usuarioId(), inicioDeHoje,
                Money.ZERO, List.of(
                        lancado(TipoMovimentoCaixa.SUPRIMENTO, Money.de("50.00"), "troco",
                                inicioDeHoje.plusSeconds(1)),
                        lancadoDaVenda(TipoMovimentoCaixa.VENDA, Money.de("30.00"), venda,
                                inicioDeHoje.plusSeconds(2)),
                        lancado(TipoMovimentoCaixa.SANGRIA, Money.de("20.00"), "deposito",
                                inicioDeHoje.plusSeconds(3)),
                        lancadoDaVenda(TipoMovimentoCaixa.ESTORNO, Money.de("4.00"), venda,
                                inicioDeHoje.plusSeconds(4))));
        sessoes.criarFechadaComMovimentos(contaB.contaId(), contaB.usuarioId(), inicioDeHoje,
                Money.ZERO, List.of(lancado(TipoMovimentoCaixa.SUPRIMENTO, Money.de("15.00"),
                        "troco", inicioDeHoje.plusSeconds(1))));

        fluxo(contaA, hoje, hoje)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inicio").value(hoje.toString()))
                .andExpect(jsonPath("$.fim").value(hoje.toString()))
                .andExpect(jsonPath("$.vendas").value(30.0))
                .andExpect(jsonPath("$.suprimentos").value(50.0))
                .andExpect(jsonPath("$.sangrias").value(20.0))
                .andExpect(jsonPath("$.estornos").value(4.0))
                .andExpect(jsonPath("$.recebimentos").value(7.0))
                .andExpect(jsonPath("$.entradas").value(87.0))
                .andExpect(jsonPath("$.saidas").value(24.0))
                .andExpect(jsonPath("$.saldo").value(63.0));
        fluxo(contaB, hoje, hoje)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.suprimentos").value(15.0))
                .andExpect(jsonPath("$.vendas").value(0.0))
                .andExpect(jsonPath("$.recebimentos").value(0.0))
                .andExpect(jsonPath("$.saldo").value(15.0));
    }

    @Test
    void recusaOperadorPlanoSemRelatorioEPeriodoInvalido() throws Exception {
        LocalDate dia = LocalDate.of(2026, 9, 15);
        ContaCriada operador = criador.criar("Loja do Operador", SENHA, Perfil.OPERADOR, true);
        ContaCriada gratis = criador.criar("Loja Gratuita", SENHA);
        ContaCriada admin = criador.criar("Loja do Fluxo", SENHA);
        criador.contratar(admin.contaId(), Plano.CAIXA_SIMPLES);

        fluxo(operador, dia, dia).andExpect(status().isForbidden());
        fluxo(gratis, dia, dia).andExpect(status().isConflict());
        fluxo(admin, dia, dia.minusDays(1)).andExpect(status().isBadRequest());
        http.perform(get("/api/relatorios/fluxo-de-caixa").param("inicio", dia.toString())
                        .with(autenticador.como(admin)))
                .andExpect(status().isBadRequest());
    }

    private ResultActions fluxo(ContaCriada conta, LocalDate inicio, LocalDate fim)
            throws Exception {
        return http.perform(get("/api/relatorios/fluxo-de-caixa")
                .param("inicio", inicio.toString())
                .param("fim", fim.toString())
                .with(autenticador.como(conta)));
    }

    /**
     * Uma Venda fiada de vinte, concluída pelo administrador, e um recebimento em dinheiro do valor
     * pedido no caixa aberto: é o que lança o movimento RECEBIMENTO na gaveta.
     */
    private void receberFiadoEmDinheiro(ContaCriada conta, UUID caixaAberto, String valor) {
        UUID clienteId = conta.comoUsuario(() -> clientes.cadastrar(new DadosDoCliente("Lia", null)));
        UUID produtoId = conta.comoUsuario(() -> produtos.cadastrar(TipoProduto.PRODUTO,
                new DadosDoProduto("Bolo de laranja", Money.de("20.00"), null, null, "un", null)));
        conta.comoUsuario(() -> {
            UUID vendaId = vendaService.iniciar(caixaAberto);
            vendaService.adicionarItem(vendaId, produtoId, BigDecimal.ONE, Money.ZERO);
            vendaService.vincularCliente(vendaId, clienteId);
            vendaService.registrarPagamento(vendaId,
                    SolicitacaoPagamento.de(FormaPagamento.FIADO, Money.de("20.00")));
            vendaService.concluir(vendaId);
            vendaService.receber(vendaId, Money.de(valor), FormaPagamento.DINHEIRO);
        });
    }
}
