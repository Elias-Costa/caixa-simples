package br.com.caixasimples.sincronizacao;

import static br.com.caixasimples.sincronizacao.GestoDeTeste.conteudo;
import static br.com.caixasimples.sincronizacao.GestoDeTeste.ontemAs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.caixasimples.TesteDeIntegracao;
import br.com.caixasimples.contas.AutenticadorDeTeste;
import br.com.caixasimples.contas.CriadorDeContaDeTeste;
import br.com.caixasimples.contas.CriadorDeContaDeTeste.ContaCriada;
import br.com.caixasimples.shared.FusoDeReferencia;
import br.com.caixasimples.shared.Perfil;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Contrato HTTP do lote enviado pelo dispositivo quando a rede volta (RNF01 a RNF03, RNF05).
 *
 * <p>Os gestos têm os nomes, os conteúdos e as dependências que o PWA grava sem rede. O dia
 * inteiro prova que o servidor aceita os ids e os instantes do balcão e que a sangria encontra o
 * dinheiro da Venda concluída no mesmo lote; os outros cenários provam a idempotência por
 * operação e o isolamento entre Contas pela API.
 */
class SincronizacaoHttpTest extends TesteDeIntegracao {

    private static final String SENHA = "uma senha longa de teste";

    @Autowired MockMvc http;
    @Autowired ObjectMapper json;
    @Autowired CriadorDeContaDeTeste criador;
    @Autowired AutenticadorDeTeste autenticador;

    @Test
    @DisplayName("um dia inteiro sem rede num lote: ids e instantes do balcão, sangria que só cabe com a Venda")
    void diaInteiroSemRedeNumLote() throws Exception {
        ContaCriada conta = criador.criar("Cafeteria Aurora", SENHA);
        RequestPostProcessor admin = autenticador.como(conta);
        DiaSemRede dia = new DiaSemRede();

        JsonNode resposta = enviar(admin, dia.gestos());

        for (GestoDeTeste gesto : dia.gestos()) {
            assertThat(resultadoDe(resposta, gesto).path("resultado").asText())
                    .as(gesto.tipo()).isEqualTo("APLICADA");
        }
        // A sessão ganha uma revisão por gesto que a altera, inclusive a entrada da Venda.
        assertThat(resultadoDe(resposta, dia.abertura).path("versao").asLong()).isZero();
        assertThat(resultadoDe(resposta, dia.sangria).path("versao").asLong()).isEqualTo(2);
        assertThat(resultadoDe(resposta, dia.fechamento).path("versao").asLong()).isEqualTo(4);
        assertThat(resultadoDe(resposta, dia.conclusao).has("versao")).isFalse();

        http.perform(get("/api/vendas/{id}", dia.vendaId).with(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONCLUIDA"))
                .andExpect(jsonPath("$.clienteId").value(dia.clienteId.toString()))
                .andExpect(jsonPath("$.itens[0].id").value(dia.itemId.toString()))
                .andExpect(jsonPath("$.itens[0].precoUnitario").value(10.0))
                .andExpect(jsonPath("$.total").value(20.0));
        JsonNode comprovante = ler(http.perform(get("/api/vendas/{id}/comprovante", dia.vendaId)
                .with(admin)).andExpect(status().isOk()));
        assertThat(comprovante.path("troco").decimalValue())
                .isEqualByComparingTo(new BigDecimal("30"));
        assertThat(Instant.parse(comprovante.path("concluidoEm").asText()))
                .isEqualTo(dia.concluidaEm);

        JsonNode sessao = ler(http.perform(get("/api/caixa/sessoes/{id}", dia.sessaoId)
                .with(admin)).andExpect(status().isOk()));
        assertThat(sessao.path("status").asText()).isEqualTo("FECHADA");
        assertThat(Instant.parse(sessao.path("abertaEm").asText())).isEqualTo(dia.abertaEm);
        assertThat(Instant.parse(sessao.path("fechadaEm").asText())).isEqualTo(dia.fechadaEm);
        // 20,00 de abertura, mais 20,00 da Venda, menos 35,00, mais 5,00: 10,00, e foi o contado.
        assertThat(sessao.path("valorFechamentoEsperado").decimalValue())
                .isEqualByComparingTo(new BigDecimal("10"));
        assertThat(sessao.path("diferenca").decimalValue()).isEqualByComparingTo(BigDecimal.ZERO);
        List<String> movimentos = new ArrayList<>();
        sessao.path("movimentos").forEach(movimento -> movimentos.add(
                movimento.path("tipo").asText() + " " + movimento.path("valor").decimalValue()
                        .stripTrailingZeros().toPlainString() + " "
                        + Instant.parse(movimento.path("criadoEm").asText())));
        assertThat(movimentos).containsExactly(
                "VENDA 20 " + dia.concluidaEm,
                "SANGRIA 35 " + dia.sangradaEm,
                "SUPRIMENTO 5 " + dia.supridaEm);

        // O faturamento conta a Venda no dia em que ela aconteceu no balcão, e não no da chegada.
        LocalDate ontem = LocalDate.now(FusoDeReferencia.DO_BALCAO).minusDays(1);
        http.perform(get("/api/relatorios/faturamento/dia").param("dia", ontem.toString())
                        .with(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(20.0))
                .andExpect(jsonPath("$.quantidadeDeVendas").value(1));
    }

    @Test
    @DisplayName("o mesmo lote enviado três vezes devolve o mesmo resultado e deixa o mesmo estado")
    void mesmoLoteTresVezes() throws Exception {
        ContaCriada conta = criador.criar("Mercearia da Rua", SENHA);
        RequestPostProcessor admin = autenticador.como(conta);
        DiaSemRede dia = new DiaSemRede();

        JsonNode primeira = enviar(admin, dia.gestos());
        String sessaoDepoisDaPrimeira = ler(http.perform(get("/api/caixa/sessoes/{id}",
                dia.sessaoId).with(admin))).toString();
        String vendaDepoisDaPrimeira = ler(http.perform(get("/api/vendas/{id}", dia.vendaId)
                .with(admin))).toString();

        JsonNode segunda = enviar(admin, dia.gestos());
        JsonNode terceira = enviar(admin, dia.gestos());

        assertThat(segunda).isEqualTo(primeira);
        assertThat(terceira).isEqualTo(primeira);
        assertThat(ler(http.perform(get("/api/caixa/sessoes/{id}", dia.sessaoId).with(admin)))
                .toString()).isEqualTo(sessaoDepoisDaPrimeira);
        assertThat(ler(http.perform(get("/api/vendas/{id}", dia.vendaId).with(admin)))
                .toString()).isEqualTo(vendaDepoisDaPrimeira);
        http.perform(get("/api/produtos").with(admin))
                .andExpect(jsonPath("$.length()").value(1));
        http.perform(get("/api/clientes").with(admin))
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    @DisplayName("o mesmo id de operação com outro conteúdo não é aplicado, e o gravado continua valendo")
    void mesmoIdComOutroConteudo() throws Exception {
        ContaCriada conta = criador.criar("Padaria Central", SENHA);
        RequestPostProcessor admin = autenticador.como(conta);
        GestoDeTeste criacao = GestoDeTeste.de("produto.criar", UUID.randomUUID(),
                produto("Pao de queijo", "4.50", null));

        assertThat(resultadoDe(enviar(admin, List.of(criacao)), criacao)
                .path("resultado").asText()).isEqualTo("APLICADA");

        GestoDeTeste adulterado = criacao.comOutroConteudo(produto("Pao de queijo", "0.50", null));
        JsonNode recusa = resultadoDe(enviar(admin, List.of(adulterado)), adulterado);
        assertThat(recusa.path("resultado").asText()).isEqualTo("NAO_APLICADA");
        assertThat(recusa.path("detalhe").asText()).contains("outro conteudo");

        http.perform(get("/api/produtos").with(admin))
                .andExpect(jsonPath("$[0].preco").value(4.5));
        // O original, reenviado, continua devolvendo o que foi gravado.
        assertThat(resultadoDe(enviar(admin, List.of(criacao)), criacao)
                .path("resultado").asText()).isEqualTo("APLICADA");
    }

    @Test
    @DisplayName("duas Contas com os mesmos ids de operação: cada uma tem o seu resultado (RNF05)")
    void duasContasComOsMesmosIds() throws Exception {
        ContaCriada contaA = criador.criar("Loja A", SENHA);
        ContaCriada contaB = criador.criar("Loja B", SENHA);
        RequestPostProcessor adminA = autenticador.como(contaA);
        RequestPostProcessor adminB = autenticador.como(contaB);
        GestoDeTeste daContaA = GestoDeTeste.de("produto.criar", UUID.randomUUID(),
                produto("Camiseta", "59.90", null));
        // Mesmo id de operação, outro registro e outro conteúdo, na outra Conta.
        GestoDeTeste daContaB = new GestoDeTeste(daContaA.operacaoId(), UUID.randomUUID(),
                "produto.criar", produto("Bermuda", "79.90", null), null, List.of(),
                daContaA.criadoEm());

        assertThat(resultadoDe(enviar(adminA, List.of(daContaA)), daContaA)
                .path("resultado").asText()).isEqualTo("APLICADA");
        assertThat(resultadoDe(enviar(adminB, List.of(daContaB)), daContaB)
                .path("resultado").asText())
                .as("o id gravado na Conta A não existe para a Conta B")
                .isEqualTo("APLICADA");

        http.perform(get("/api/produtos").with(adminA))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].nome").value("Camiseta"));
        http.perform(get("/api/produtos").with(adminB))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].nome").value("Bermuda"));

        // O id do Produto da Conta A usado pela Conta B: recusa genérica, e a Conta A intacta.
        GestoDeTeste invasao = GestoDeTeste.de("produto.criar", daContaA.registroId(),
                produto("Invasor", "1.00", null));
        JsonNode recusa = resultadoDe(enviar(adminB, List.of(invasao)), invasao);
        assertThat(recusa.path("resultado").asText()).isEqualTo("NAO_APLICADA");
        assertThat(recusa.path("detalhe").asText()).contains("conflita");
        http.perform(get("/api/produtos").with(adminA))
                .andExpect(jsonPath("$[0].nome").value("Camiseta"));
        http.perform(get("/api/produtos").with(adminB))
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    @DisplayName("o operador não cadastra produto nem registra Pix pelo lote; o tipo desconhecido é recusado")
    void recusasDePermissaoDePixEDeTipo() throws Exception {
        ContaCriada operador = criador.criar("Loja do Operador", SENHA, Perfil.OPERADOR, true);
        RequestPostProcessor token = autenticador.como(operador);
        UUID sessaoId = UUID.randomUUID();
        UUID vendaId = UUID.randomUUID();
        GestoDeTeste produto = GestoDeTeste.de("produto.criar", UUID.randomUUID(),
                produto("Agua", "3.00", null));
        GestoDeTeste abertura = GestoDeTeste.abertura(sessaoId, "0");
        GestoDeTeste inicio = GestoDeTeste.inicio(vendaId, sessaoId, abertura);
        GestoDeTeste pix = GestoDeTeste.de("venda.registrarPagamento", vendaId,
                conteudo("pagamentoId", UUID.randomUUID(), "forma", "PIX",
                        "valor", new BigDecimal("3.00"), "valorRecebido", null), inicio);
        GestoDeTeste cancelamento = GestoDeTeste.de("venda.cancelar", vendaId, conteudo(), inicio);

        JsonNode resposta = enviar(token, List.of(produto, abertura, inicio, pix, cancelamento));

        assertThat(resultadoDe(resposta, produto).path("resultado").asText())
                .isEqualTo("NAO_APLICADA");
        assertThat(resultadoDe(resposta, produto).path("detalhe").asText())
                .contains("administrador");
        assertThat(resultadoDe(resposta, abertura).path("resultado").asText())
                .isEqualTo("APLICADA");
        assertThat(resultadoDe(resposta, inicio).path("resultado").asText())
                .isEqualTo("APLICADA");
        assertThat(resultadoDe(resposta, pix).path("resultado").asText())
                .isEqualTo("NAO_APLICADA");
        assertThat(resultadoDe(resposta, pix).path("detalhe").asText()).contains("Pix");
        assertThat(resultadoDe(resposta, cancelamento).path("resultado").asText())
                .isEqualTo("NAO_APLICADA");
        http.perform(get("/api/vendas/{id}", vendaId).with(token))
                .andExpect(jsonPath("$.status").value("ABERTA"))
                .andExpect(jsonPath("$.parcelas").isEmpty());
    }

    @Test
    @DisplayName("lote acima do limite, com id repetido ou em ciclo é recusado inteiro com 400; sem token, 401")
    void loteInvalidoInteiro() throws Exception {
        ContaCriada conta = criador.criar("Empório do Bairro", SENHA);
        RequestPostProcessor admin = autenticador.como(conta);

        List<GestoDeTeste> grande = new ArrayList<>();
        for (int i = 0; i <= 100; i++) {
            grande.add(GestoDeTeste.de("cliente.criar", UUID.randomUUID(),
                    conteudo("nome", "Cliente " + i, "contato", null)));
        }
        postar(admin, grande).andExpect(status().isBadRequest());

        GestoDeTeste unico = GestoDeTeste.de("cliente.criar", UUID.randomUUID(),
                conteudo("nome", "Ana", "contato", null));
        postar(admin, List.of(unico, unico)).andExpect(status().isBadRequest());

        GestoDeTeste primeiro = GestoDeTeste.de("cliente.criar", UUID.randomUUID(),
                conteudo("nome", "Bia", "contato", null));
        GestoDeTeste segundo = GestoDeTeste.de("cliente.editar", primeiro.registroId(),
                conteudo("nome", "Bia Souza", "contato", null), primeiro);
        postar(admin, List.of(primeiro.dependendoTambemDe(segundo), segundo))
                .andExpect(status().isBadRequest());

        http.perform(post("/api/sincronizacao").contentType(MediaType.APPLICATION_JSON)
                        .content(corpo(List.of(unico))))
                .andExpect(status().isUnauthorized());
        http.perform(get("/api/clientes").with(admin))
                .andExpect(jsonPath("$").isEmpty());
    }

    /** Os gestos de um expediente inteiro sem rede, na ordem e com as dependências do PWA. */
    private static final class DiaSemRede {

        final UUID produtoId = UUID.randomUUID();
        final UUID clienteId = UUID.randomUUID();
        final UUID sessaoId = UUID.randomUUID();
        final UUID vendaId = UUID.randomUUID();
        final UUID itemId = UUID.randomUUID();
        final Instant abertaEm = ontemAs(8, 0);
        final Instant concluidaEm = ontemAs(10, 5);
        final Instant sangradaEm = ontemAs(11, 0);
        final Instant supridaEm = ontemAs(12, 0);
        final Instant fechadaEm = ontemAs(18, 0);

        final GestoDeTeste produto = GestoDeTeste.de("produto.criar", produtoId,
                produto("Cafe", "10.00", "CF-1")).registradoEm(ontemAs(7, 50));
        final GestoDeTeste cliente = GestoDeTeste.de("cliente.criar", clienteId,
                conteudo("nome", "Maria", "contato", "71 99999-0000"))
                .registradoEm(ontemAs(7, 55));
        final GestoDeTeste abertura = GestoDeTeste.de("caixa.abrir", sessaoId,
                conteudo("valorAbertura", new BigDecimal("20.00"), "abertaEm",
                        abertaEm.toString())).registradoEm(abertaEm);
        final GestoDeTeste inicio = GestoDeTeste.de("venda.iniciar", vendaId,
                conteudo("sessaoCaixaId", sessaoId, "criadoEm", ontemAs(10, 0).toString()),
                abertura).registradoEm(ontemAs(10, 0));
        final GestoDeTeste item = GestoDeTeste.de("venda.adicionarItem", vendaId,
                conteudo("itemId", itemId, "produtoId", produtoId, "nome", "Cafe",
                        "quantidade", 2, "precoUnitario", new BigDecimal("10.00"),
                        "desconto", 0, "versaoProduto", 0),
                inicio, produto).comVersaoBase(0).registradoEm(ontemAs(10, 1));
        final GestoDeTeste vinculo = GestoDeTeste.de("venda.vincularCliente", vendaId,
                conteudo("clienteId", clienteId), item, cliente).comVersaoBase(1)
                .registradoEm(ontemAs(10, 2));
        final GestoDeTeste parcela = GestoDeTeste.de("venda.registrarPagamento", vendaId,
                conteudo("pagamentoId", UUID.randomUUID(), "forma", "DINHEIRO",
                        "valor", new BigDecimal("20.00"), "valorRecebido",
                        new BigDecimal("50.00")),
                vinculo).comVersaoBase(2).registradoEm(ontemAs(10, 3));
        final GestoDeTeste conclusao = GestoDeTeste.de("venda.concluir", vendaId,
                conteudo("concluidoEm", concluidaEm.toString()), parcela, abertura)
                .comVersaoBase(3).registradoEm(concluidaEm);
        final GestoDeTeste sangria = GestoDeTeste.de("caixa.sangrar", sessaoId,
                conteudo("valor", new BigDecimal("35.00"), "motivo", "Deposito",
                        "criadoEm", sangradaEm.toString()), conclusao)
                .comVersaoBase(1).registradoEm(sangradaEm);
        final GestoDeTeste suprimento = GestoDeTeste.de("caixa.suprir", sessaoId,
                conteudo("valor", new BigDecimal("5.00"), "motivo", "Troco",
                        "criadoEm", supridaEm.toString()), sangria)
                .comVersaoBase(2).registradoEm(supridaEm);
        final GestoDeTeste fechamento = GestoDeTeste.de("caixa.fechar", sessaoId,
                conteudo("valorContado", new BigDecimal("10.00"), "fechadaEm",
                        fechadaEm.toString()),
                abertura, inicio, item, vinculo, parcela, conclusao, sangria, suprimento)
                .comVersaoBase(3).registradoEm(fechadaEm);

        List<GestoDeTeste> gestos() {
            return List.of(produto, cliente, abertura, inicio, item, vinculo, parcela, conclusao,
                    sangria, suprimento, fechamento);
        }
    }

    static Map<String, Object> produto(String nome, String preco, String codigo) {
        return conteudo("tipo", "PRODUTO", "nome", nome, "preco", new BigDecimal(preco),
                "codigo", codigo, "categoria", null, "unidade", "un", "atributos", Map.of());
    }

    private JsonNode enviar(RequestPostProcessor quem, List<GestoDeTeste> gestos)
            throws Exception {
        return ler(postar(quem, gestos).andExpect(status().isOk()));
    }

    private ResultActions postar(RequestPostProcessor quem, List<GestoDeTeste> gestos)
            throws Exception {
        return http.perform(post("/api/sincronizacao").with(quem)
                .contentType(MediaType.APPLICATION_JSON).content(corpo(gestos)));
    }

    private String corpo(List<GestoDeTeste> gestos) {
        return json.writeValueAsString(Map.of("operacoes",
                gestos.stream().map(GestoDeTeste::paraJson).toList()));
    }

    private JsonNode ler(ResultActions resultado) throws Exception {
        return json.readTree(resultado.andReturn().getResponse().getContentAsString());
    }

    private static JsonNode resultadoDe(JsonNode resposta, GestoDeTeste gesto) {
        for (JsonNode resultado : resposta.path("resultados")) {
            if (resultado.path("operacaoId").asText().equals(gesto.operacaoId().toString())) {
                return resultado;
            }
        }
        throw new AssertionError("sem resultado para " + gesto.tipo() + " " + gesto.operacaoId());
    }
}
