package br.com.caixasimples.sincronizacao;

import br.com.caixasimples.shared.FusoDeReferencia;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.ObjectMapper;

/**
 * Um gesto da fila local como o dispositivo o envia, montado para os testes do lote.
 *
 * <p>Os campos e os nomes são os de {@code GestoNaFila} no PWA, e os conteúdos são os que as
 * telas gravam sem rede: é o contrato que o servidor precisa aceitar. As dependências apontam
 * para outros gestos, como o dispositivo faz.
 */
public record GestoDeTeste(UUID operacaoId, UUID registroId, String tipo,
        Map<String, Object> payload, Long versaoBase, List<UUID> dependeDe, Instant criadoEm) {

    public static GestoDeTeste de(String tipo, UUID registroId, Map<String, Object> payload,
            GestoDeTeste... depende) {
        return new GestoDeTeste(UUID.randomUUID(), registroId, tipo, payload, null,
                Arrays.stream(depende).map(GestoDeTeste::operacaoId).toList(),
                Instant.now().truncatedTo(ChronoUnit.MILLIS));
    }

    /** O mesmo gesto, com a versão que o dispositivo leu antes de alterar. */
    public GestoDeTeste comVersaoBase(long versao) {
        return new GestoDeTeste(operacaoId, registroId, tipo, payload, versao, dependeDe,
                criadoEm);
    }

    /** O mesmo gesto, registrado noutro instante do relógio do dispositivo. */
    public GestoDeTeste registradoEm(Instant instante) {
        return new GestoDeTeste(operacaoId, registroId, tipo, payload, versaoBase, dependeDe,
                instante);
    }

    /** O mesmo id de operação, com outro conteúdo. */
    public GestoDeTeste comOutroConteudo(Map<String, Object> outro) {
        return new GestoDeTeste(operacaoId, registroId, tipo, outro, versaoBase, dependeDe,
                criadoEm);
    }

    /** Um gesto que depende também destes outros. */
    public GestoDeTeste dependendoTambemDe(GestoDeTeste... outros) {
        List<UUID> todas = new ArrayList<>(dependeDe);
        Arrays.stream(outros).map(GestoDeTeste::operacaoId).forEach(todas::add);
        return new GestoDeTeste(operacaoId, registroId, tipo, payload, versaoBase, todas,
                criadoEm);
    }

    /** O corpo que o PWA monta para a API. */
    public Map<String, Object> paraJson() {
        Map<String, Object> corpo = new LinkedHashMap<>();
        corpo.put("operacaoId", operacaoId);
        corpo.put("registroId", registroId);
        corpo.put("tipo", tipo);
        corpo.put("payload", payload);
        if (versaoBase != null) {
            corpo.put("versaoBase", versaoBase);
        }
        corpo.put("dependeDe", dependeDe);
        corpo.put("criadoEm", criadoEm.toString());
        return corpo;
    }

    /** O mesmo gesto como o controller o entrega ao serviço. */
    public OperacaoRecebida recebida(ObjectMapper json) {
        return new OperacaoRecebida(operacaoId, registroId, tipo, json.valueToTree(payload),
                versaoBase, dependeDe, criadoEm);
    }

    /** A abertura de caixa gravada sem rede, agora. */
    public static GestoDeTeste abertura(UUID sessaoId, String valor) {
        return de("caixa.abrir", sessaoId, conteudo("valorAbertura", new BigDecimal(valor),
                "abertaEm", Instant.now().toString()));
    }

    /** O início de uma Venda sem rede na sessão, dependendo do que o dispositivo mandar. */
    public static GestoDeTeste inicio(UUID vendaId, UUID sessaoId, GestoDeTeste... depende) {
        return de("venda.iniciar", vendaId, conteudo("sessaoCaixaId", sessaoId,
                "criadoEm", Instant.now().toString()), depende);
    }

    /** Um item com o preço visto no balcão, sem desconto. */
    public static GestoDeTeste item(UUID vendaId, UUID itemId, UUID produtoId, String quantidade,
            String precoVisto, GestoDeTeste... depende) {
        return de("venda.adicionarItem", vendaId, conteudo("itemId", itemId, "produtoId",
                produtoId, "nome", "Item visto", "quantidade", new BigDecimal(quantidade),
                "precoUnitario", new BigDecimal(precoVisto), "desconto",
                BigDecimal.ZERO, "versaoProduto", 0), depende);
    }

    /** Uma parcela sem troco, em cartão. */
    public static GestoDeTeste parcelaNoCartao(UUID vendaId, String valor,
            GestoDeTeste... depende) {
        return de("venda.registrarPagamento", vendaId, conteudo("pagamentoId", UUID.randomUUID(),
                "forma", "CARTAO", "valor", new BigDecimal(valor),
                "valorRecebido", null), depende);
    }

    /** A conclusão com o instante do balcão de agora. */
    public static GestoDeTeste conclusao(UUID vendaId, GestoDeTeste... depende) {
        return de("venda.concluir", vendaId, conteudo("concluidoEm", Instant.now().toString()),
                depende);
    }

    /** Uma sangria com motivo, agora. */
    public static GestoDeTeste sangria(UUID sessaoId, String valor, GestoDeTeste... depende) {
        return de("caixa.sangrar", sessaoId, conteudo("valor", new BigDecimal(valor),
                "motivo", "Deposito", "criadoEm", Instant.now().toString()), depende);
    }

    /** Um instante de ontem no relógio do balcão, para a Venda registrada sem rede. */
    public static Instant ontemAs(int hora, int minuto) {
        return LocalDate.now(FusoDeReferencia.DO_BALCAO).minusDays(1).atTime(hora, minuto)
                .atZone(FusoDeReferencia.DO_BALCAO).toInstant();
    }

    /** Monta um conteúdo com pares chave e valor, na ordem dada; aceita valor nulo. */
    public static Map<String, Object> conteudo(Object... chavesEValores) {
        Map<String, Object> mapa = new LinkedHashMap<>();
        for (int i = 0; i < chavesEValores.length; i += 2) {
            mapa.put((String) chavesEValores[i], chavesEValores[i + 1]);
        }
        return mapa;
    }
}
