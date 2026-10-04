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
import br.com.caixasimples.shared.FusoDeReferencia;
import br.com.caixasimples.shared.Money;
import br.com.caixasimples.shared.Perfil;
import br.com.caixasimples.vendas.CriadorDeVendaDeTeste;
import br.com.caixasimples.vendas.CriadorDeVendaDeTeste.ItemDeTeste;
import java.math.BigDecimal;
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
 * O contrato HTTP dos mais vendidos: ordem, limite, filtro de operador, isolamento entre Contas e
 * recusas (RF22, RF24, RNF05). As regras do ranking em si têm os cenários contra o banco no teste
 * do caso de uso; aqui se prova o que a rota acrescenta.
 */
class MaisVendidosHttpTest extends TesteDeIntegracao {

    private static final String SENHA = "uma senha longa de teste";
    private static final LocalDate DIA = LocalDate.of(2026, 9, 15);

    @Autowired MockMvc http;
    @Autowired CriadorDeContaDeTeste criador;
    @Autowired AutenticadorDeTeste autenticador;
    @Autowired SessaoCaixaService sessoes;
    @Autowired ProdutoService produtos;
    @Autowired CriadorDeVendaDeTeste vendas;

    @Test
    void administradorConsultaORankingComLimiteSemLerOutraConta() throws Exception {
        ContaCriada contaA = criador.criar("Cafeteria Aurora", SENHA);
        criador.contratar(contaA.contaId(), Plano.CAIXA_SIMPLES);
        ContaCriada contaB = criador.criar("Loja da Esquina", SENHA);
        criador.contratar(contaB.contaId(), Plano.CAIXA_SIMPLES);
        UUID caixaDeA = caixa(contaA);
        UUID caixaDeB = caixa(contaB);
        UUID cafe = produto(contaA, "Cafe coado", "un");
        UUID queijo = produto(contaA, "Queijo minas", "kg");
        UUID entrega = produto(contaA, "Taxa de entrega", null);
        UUID cafeDeB = produto(contaB, "Cafe coado", "un");

        concluida(contaA, caixaDeA, contaA.usuarioId(),
                item(cafe, "3", "4.50"), item(entrega, "1", "5.00"));
        concluida(contaA, caixaDeA, contaA.usuarioId(),
                item(cafe, "2", "4.50"), item(queijo, "1.500", "39.90"));
        concluida(contaB, caixaDeB, contaB.usuarioId(), item(cafeDeB, "7", "4.50"));

        maisVendidos(contaA, 10, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inicio").value(DIA.toString()))
                .andExpect(jsonPath("$.fim").value(DIA.toString()))
                .andExpect(jsonPath("$.posicoes.length()").value(3))
                .andExpect(jsonPath("$.posicoes[0].produtoId").value(cafe.toString()))
                .andExpect(jsonPath("$.posicoes[0].nome").value("Cafe coado"))
                .andExpect(jsonPath("$.posicoes[0].unidade").value("un"))
                .andExpect(jsonPath("$.posicoes[0].quantidade").value(5.0))
                .andExpect(jsonPath("$.posicoes[0].valor").value(22.5))
                .andExpect(jsonPath("$.posicoes[1].produtoId").value(queijo.toString()))
                .andExpect(jsonPath("$.posicoes[1].quantidade").value(1.5))
                .andExpect(jsonPath("$.posicoes[1].valor").value(59.85))
                // O item sem unidade cadastrada vem sem o campo, e não com um texto vazio.
                .andExpect(jsonPath("$.posicoes[2].produtoId").value(entrega.toString()))
                .andExpect(jsonPath("$.posicoes[2].unidade").doesNotExist());
        maisVendidos(contaA, 2, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.posicoes.length()").value(2))
                .andExpect(jsonPath("$.posicoes[1].produtoId").value(queijo.toString()));
        maisVendidos(contaB, 10, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.posicoes.length()").value(1))
                .andExpect(jsonPath("$.posicoes[0].produtoId").value(cafeDeB.toString()))
                .andExpect(jsonPath("$.posicoes[0].quantidade").value(7.0));
    }

    @Test
    void filtroDoOperadorSoContaAsVendasDeleENaoAtravessaContas() throws Exception {
        ContaCriada conta = criador.criar("Cafeteria Aurora", SENHA);
        criador.contratar(conta.contaId(), Plano.CAIXA_SIMPLES);
        ContaCriada outra = criador.criar("Loja de Fora", SENHA);
        UsuarioCriado operador = criador.criarOperadorEm(conta.contaId(), "Beatriz");
        UUID caixaDoTitular = caixa(conta);
        UUID caixaDoOperador = operador.comoUsuario(() -> sessoes.abrir(Money.ZERO));
        UUID cafe = produto(conta, "Cafe coado", "un");
        UUID bolo = produto(conta, "Bolo de laranja", "fatia");

        concluida(conta, caixaDoTitular, conta.usuarioId(), item(cafe, "3", "4.50"));
        concluida(conta, caixaDoOperador, operador.usuarioId(), item(bolo, "5", "12.00"));

        maisVendidos(conta, 10, null)
                .andExpect(jsonPath("$.posicoes.length()").value(2))
                .andExpect(jsonPath("$.posicoes[0].produtoId").value(bolo.toString()));
        maisVendidos(conta, 10, operador.usuarioId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.posicoes.length()").value(1))
                .andExpect(jsonPath("$.posicoes[0].produtoId").value(bolo.toString()));
        maisVendidos(conta, 10, conta.usuarioId())
                .andExpect(jsonPath("$.posicoes.length()").value(1))
                .andExpect(jsonPath("$.posicoes[0].produtoId").value(cafe.toString()));
        // O id existe, mas é de outra Conta: lista vazia, e não erro (RNF05).
        maisVendidos(conta, 10, outra.usuarioId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.posicoes.length()").value(0));
    }

    @Test
    void recusaOperadorPlanoSemRelatorioEParametroInvalido() throws Exception {
        ContaCriada operador = criador.criar("Loja do Operador", SENHA, Perfil.OPERADOR, true);
        ContaCriada gratis = criador.criar("Loja Gratuita", SENHA);
        ContaCriada admin = criador.criar("Loja do Ranking", SENHA);
        criador.contratar(admin.contaId(), Plano.CAIXA_SIMPLES);

        maisVendidos(operador, 10, null).andExpect(status().isForbidden());
        maisVendidos(gratis, 10, null).andExpect(status().isConflict());
        maisVendidos(admin, 0, null).andExpect(status().isBadRequest());
        http.perform(get("/api/relatorios/mais-vendidos")
                        .param("inicio", DIA.toString())
                        .param("fim", DIA.minusDays(1).toString())
                        .param("limite", "10")
                        .with(autenticador.como(admin)))
                .andExpect(status().isBadRequest());
        http.perform(get("/api/relatorios/mais-vendidos")
                        .param("inicio", DIA.toString()).param("fim", DIA.toString())
                        .with(autenticador.como(admin)))
                .andExpect(status().isBadRequest());
        http.perform(get("/api/relatorios/mais-vendidos")
                        .param("inicio", DIA.toString()).param("fim", DIA.toString())
                        .param("limite", "10").param("operadorId", "nao-e-um-id")
                        .with(autenticador.como(admin)))
                .andExpect(status().isBadRequest());
    }

    /** O ranking do dia, com o operador quando não for nulo. */
    private ResultActions maisVendidos(ContaCriada conta, int limite, UUID operadorId)
            throws Exception {
        MockHttpServletRequestBuilder pedido = get("/api/relatorios/mais-vendidos")
                .param("inicio", DIA.toString())
                .param("fim", DIA.toString())
                .param("limite", Integer.toString(limite))
                .with(autenticador.como(conta));
        if (operadorId != null) {
            pedido.param("operadorId", operadorId.toString());
        }
        return http.perform(pedido);
    }

    private UUID caixa(ContaCriada conta) {
        return conta.comoUsuario(() -> sessoes.abrir(Money.ZERO));
    }

    private UUID produto(ContaCriada conta, String nome, String unidade) {
        return conta.comoUsuario(() -> produtos.cadastrar(TipoProduto.PRODUTO,
                new DadosDoProduto(nome, Money.de("1.00"), null, null, unidade, null)));
    }

    private static ItemDeTeste item(UUID produtoId, String quantidade, String preco) {
        return new ItemDeTeste(produtoId, new BigDecimal(quantidade), Money.de(preco), Money.ZERO);
    }

    private void concluida(ContaCriada conta, UUID sessaoCaixaId, UUID usuarioId,
            ItemDeTeste... itens) {
        Instant noDia = FusoDeReferencia.inicioDoDia(DIA).plusSeconds(3600);
        vendas.criarConcluidaComItensEm(conta.contaId(), sessaoCaixaId, usuarioId,
                List.of(itens), noDia);
    }
}
