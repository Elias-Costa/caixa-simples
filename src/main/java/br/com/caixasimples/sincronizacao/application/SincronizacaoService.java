package br.com.caixasimples.sincronizacao.application;

import br.com.caixasimples.shared.AcessoNegadoException;
import br.com.caixasimples.shared.UsuarioContext;
import br.com.caixasimples.sincronizacao.AplicadorDeOperacoes;
import br.com.caixasimples.sincronizacao.Aplicacao;
import br.com.caixasimples.sincronizacao.OperacaoRecebida;
import br.com.caixasimples.sincronizacao.OperacaoRecusadaException;
import br.com.caixasimples.sincronizacao.application.ResultadoDaOperacao.Resultado;
import br.com.caixasimples.sincronizacao.internal.OperacaoSincronizada;
import br.com.caixasimples.sincronizacao.internal.OperacaoSincronizadaRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Recebe o lote que o dispositivo acumulou sem rede e aplica cada operação uma vez só (RNF02,
 * RNF03).
 *
 * <h2>Uma transação por operação, com o resultado dentro dela</h2>
 *
 * <p>Cada operação roda na própria transação: o módulo dono aplica o gesto pelos casos de uso dele,
 * e o resultado é gravado na mesma transação, com índice único pela conta e pelo id da operação.
 * Se a resposta se perde depois do commit, o reenvio encontra o resultado gravado e o devolve, sem
 * aplicar de novo. Se dois envios da mesma operação correm juntos, o índice recusa o segundo, e o
 * efeito dele sai junto. Uma operação recusada não desfaz as anteriores do lote, que já
 * confirmaram.
 * Não há anotação transacional nesta classe de propósito: quem abre as transações é ela, uma por
 * operação, com o {@link TransactionTemplate}.
 *
 * <p>O mesmo id com conteúdo diferente, ou enviado por outro usuário da conta, não é aplicado nem
 * regravado: o resultado gravado continua valendo, e a resposta diz que houve conflito.
 *
 * <h2>Ordem e dependências</h2>
 *
 * <p>O lote é aplicado na ordem das dependências e, entre as operações livres, na ordem em que
 * vieram. A dependência que não está no lote nem gravada deixa a operação para um próximo envio,
 * como erro transitório. A dependência recusada do mesmo registro recusa a operação sem tentar: a
 * Venda para no primeiro gesto recusado. A dependência de outro registro só ordena, e a raiz
 * decide; assim uma Venda recusada não recusa sozinha a sangria seguinte nem o fechamento.
 *
 * <h2>O que se repete e o que vai para revisão</h2>
 *
 * <p>Falha de banco, de concorrência e de transação é transitória: nada é gravado, e o dispositivo
 * reenvia o mesmo id. Violação de integridade devolve a gravação concorrente da mesma operação, se
 * houver; senão, o registro conflita com outro existente, e a operação é recusada. Qualquer outra
 * exceção é recusa, gravada com a mensagem. As recusas previstas vão ao log como aviso; qualquer
 * outra exceção vai como erro, porque pode ser defeito.
 *
 * <h2>Relógio</h2>
 *
 * <p>O gesto registrado mais de {@link #TOLERANCIA_DO_RELOGIO} depois do instante em que chegou
 * ao servidor veio de um relógio adiantado. É aplicado com o instante do balcão, que decide o dia
 * dos relatórios, e vai para revisão, para o dia não mudar em silêncio.
 *
 * <p>Quem envia vem do contexto autenticado; o conteúdo do gesto nunca escolhe conta nem usuário
 * (RNF05).
 */
@Service
public class SincronizacaoService {

    /** Operações por envio; acima disso o dispositivo divide a fila. */
    public static final int LIMITE_DO_LOTE = 100;

    /** Quanto o relógio do dispositivo pode estar adiantado sem que o gesto vá para revisão. */
    public static final Duration TOLERANCIA_DO_RELOGIO = Duration.ofMinutes(5);

    private static final Logger log = LoggerFactory.getLogger(SincronizacaoService.class);

    private final Map<String, AplicadorDeOperacoes> aplicadorPorTipo;
    private final OperacaoSincronizadaRepository registros;
    private final TransactionTemplate transacao;
    private final ObjectMapper json;

    /**
     * Cada módulo dono registra os tipos de gesto que aplica; dois módulos com o mesmo tipo
     * impedem a aplicação de subir, em vez de um deles ser escolhido por acaso.
     */
    SincronizacaoService(List<AplicadorDeOperacoes> aplicadores,
            OperacaoSincronizadaRepository registros, TransactionTemplate transacao,
            ObjectMapper json) {
        Map<String, AplicadorDeOperacoes> porTipo = new HashMap<>();
        for (AplicadorDeOperacoes aplicador : aplicadores) {
            for (String tipo : aplicador.tipos()) {
                if (porTipo.putIfAbsent(tipo, aplicador) != null) {
                    throw new IllegalStateException("dois modulos aplicam o gesto " + tipo);
                }
            }
        }
        this.aplicadorPorTipo = Map.copyOf(porTipo);
        this.registros = registros;
        this.transacao = transacao;
        this.json = json;
    }

    /**
     * Aplica o lote e devolve um resultado por operação, na ordem em que as operações vieram.
     *
     * @throws IllegalArgumentException se o lote passa do limite, repete um id de operação ou tem
     *                                  dependências em ciclo; nesse caso nada é aplicado
     */
    public List<ResultadoDaOperacao> sincronizar(List<OperacaoRecebida> operacoes) {
        Objects.requireNonNull(operacoes, "operacoes nao podem ser nulas");
        UUID usuarioId = UsuarioContext.exigirAtual().usuarioId();
        if (operacoes.size() > LIMITE_DO_LOTE) {
            throw new IllegalArgumentException("o lote aceita ate " + LIMITE_DO_LOTE
                    + " operacoes e chegou com " + operacoes.size());
        }

        Map<UUID, ResultadoDaOperacao> porOperacao = new HashMap<>();
        for (OperacaoRecebida operacao : ordenarPorDependencia(operacoes)) {
            porOperacao.put(operacao.operacaoId(), processar(operacao, usuarioId, porOperacao));
        }
        return operacoes.stream().map(operacao -> porOperacao.get(operacao.operacaoId())).toList();
    }

    /**
     * Cada operação depois das dependências que estão no lote e, entre as livres, a que veio
     * primeiro. É a mesma ordem em que o dispositivo reaplica a fila.
     */
    static List<OperacaoRecebida> ordenarPorDependencia(List<OperacaoRecebida> operacoes) {
        Set<UUID> ids = new HashSet<>();
        for (OperacaoRecebida operacao : operacoes) {
            if (!ids.add(operacao.operacaoId())) {
                throw new IllegalArgumentException(
                        "operacao repetida no lote: " + operacao.operacaoId());
            }
        }
        List<OperacaoRecebida> restantes = new ArrayList<>(operacoes);
        List<OperacaoRecebida> ordenadas = new ArrayList<>();
        while (!restantes.isEmpty()) {
            Set<UUID> pendentes = new HashSet<>();
            restantes.forEach(operacao -> pendentes.add(operacao.operacaoId()));
            OperacaoRecebida livre = restantes.stream()
                    .filter(operacao -> operacao.dependeDe().stream()
                            .noneMatch(pendentes::contains))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "o lote tem dependencias em ciclo"));
            restantes.remove(livre);
            ordenadas.add(livre);
        }
        return ordenadas;
    }

    private ResultadoDaOperacao processar(OperacaoRecebida operacao, UUID usuarioId,
            Map<UUID, ResultadoDaOperacao> doLote) {
        for (UUID dependencia : operacao.dependeDe()) {
            ResultadoDaOperacao anterior = doLote.get(dependencia);
            if (anterior != null && anterior.resultado() == Resultado.ERRO_TRANSITORIO) {
                return ResultadoDaOperacao.transitorio(operacao.operacaoId(),
                        "depende da operacao " + dependencia + ", que ainda nao foi aplicada");
            }
        }
        try {
            return transacao.execute(status -> aplicarNaTransacao(operacao, usuarioId));
        } catch (DataIntegrityViolationException conflito) {
            return depoisDeConflito(operacao, usuarioId, conflito);
        } catch (DataAccessException | TransactionException falha) {
            // Esperada em reenvio simultâneo e em queda do banco: a mensagem basta, sem a pilha.
            log.warn("operacao {} do tipo {} nao aplicada por falha transitoria: {}",
                    operacao.operacaoId(), operacao.tipo(), falha.getMessage());
            return ResultadoDaOperacao.transitorio(operacao.operacaoId(),
                    "falha temporaria no servidor; a operacao pode ser reenviada");
        } catch (RuntimeException recusa) {
            registrarRecusaNoLog(operacao, recusa);
            return gravarRecusa(operacao, usuarioId, mensagemDe(recusa));
        }
    }

    private ResultadoDaOperacao aplicarNaTransacao(OperacaoRecebida operacao, UUID usuarioId) {
        Optional<OperacaoSincronizada> gravada = registros.findByOperacaoId(operacao.operacaoId());
        if (gravada.isPresent()) {
            return respostaAoReenvio(gravada.get(), operacao, usuarioId);
        }

        List<OperacaoSincronizada> dependencias = operacao.dependeDe().isEmpty()
                ? List.of()
                : registros.findByOperacaoIdIn(operacao.dependeDe());
        if (dependencias.size() < operacao.dependeDe().size()) {
            return ResultadoDaOperacao.transitorio(operacao.operacaoId(),
                    "aguardando uma operacao da qual esta depende");
        }

        Instant recebidaEm = Instant.now();
        for (OperacaoSincronizada dependencia : dependencias) {
            if (dependencia.naoFoiAplicada()
                    && dependencia.getRegistroId().equals(operacao.registroId())) {
                return gravar(operacao, usuarioId, recebidaEm, Resultado.NAO_APLICADA, null,
                        "o gesto anterior deste registro, " + dependencia.getOperacaoId()
                                + ", nao foi aplicado");
            }
        }

        AplicadorDeOperacoes aplicador = aplicadorPorTipo.get(operacao.tipo());
        if (aplicador == null) {
            throw new OperacaoRecusadaException("o servidor nao aplica o gesto "
                    + operacao.tipo() + " recebido do dispositivo");
        }
        Aplicacao aplicacao = aplicador.aplicar(operacao);

        List<String> revisoes = new ArrayList<>();
        if (aplicacao.revisao() != null) {
            revisoes.add(aplicacao.revisao());
        }
        if (operacao.criadoEm().isAfter(recebidaEm.plus(TOLERANCIA_DO_RELOGIO))) {
            revisoes.add("o relogio do dispositivo estava adiantado em relacao ao servidor; o"
                    + " instante do balcao foi mantido, e o dia do registro precisa ser conferido");
        }
        return revisoes.isEmpty()
                ? gravar(operacao, usuarioId, recebidaEm, Resultado.APLICADA,
                        aplicacao.versao(), null)
                : gravar(operacao, usuarioId, recebidaEm, Resultado.APLICADA_COM_REVISAO,
                        aplicacao.versao(), String.join("; ", revisoes));
    }

    private ResultadoDaOperacao gravar(OperacaoRecebida operacao, UUID usuarioId,
            Instant recebidaEm, Resultado resultado, Long versao, String detalhe) {
        OperacaoSincronizada registro = new OperacaoSincronizada(operacao, usuarioId,
                json.writeValueAsString(operacao.payload()),
                json.writeValueAsString(operacao.dependeDe()), recebidaEm, resultado, versao,
                detalhe);
        registros.save(registro);
        return registro.paraResultado();
    }

    /**
     * O reenvio de uma operação gravada devolve o que foi gravado, se for a mesma operação. Com
     * outro conteúdo ou outro autor, o gravado continua valendo e o novo não é aplicado.
     */
    private ResultadoDaOperacao respostaAoReenvio(OperacaoSincronizada gravada,
            OperacaoRecebida operacao, UUID usuarioId) {
        if (mesmaOperacao(gravada, operacao, usuarioId)) {
            return gravada.paraResultado();
        }
        return ResultadoDaOperacao.naoAplicada(operacao.operacaoId(),
                "o id desta operacao ja foi usado com outro conteudo ou por outro usuario; o"
                        + " resultado gravado continua valendo");
    }

    /**
     * Compara campo a campo. O conteúdo e as dependências voltam do banco como texto e são lidos
     * de novo, porque o banco não guarda a ordem das chaves; o conteúdo recebido passa pelo mesmo
     * caminho, escrito e lido, para os dois lados terem os números no mesmo tipo. O instante é
     * comparado em microssegundos, a precisão da coluna.
     */
    private boolean mesmaOperacao(OperacaoSincronizada gravada, OperacaoRecebida operacao,
            UUID usuarioId) {
        JsonNode dependenciasGravadas = json.readTree(gravada.getDependeDe());
        Set<UUID> dependeDeGravado = new HashSet<>();
        dependenciasGravadas.forEach(id -> dependeDeGravado.add(UUID.fromString(id.asText())));

        return gravada.getUsuarioId().equals(usuarioId)
                && gravada.getTipo().equals(operacao.tipo())
                && gravada.getRegistroId().equals(operacao.registroId())
                && Objects.equals(gravada.getVersaoBase(), operacao.versaoBase())
                && gravada.getCriadaEm().truncatedTo(ChronoUnit.MICROS)
                        .equals(operacao.criadoEm().truncatedTo(ChronoUnit.MICROS))
                && dependeDeGravado.equals(new HashSet<>(operacao.dependeDe()))
                && json.readTree(gravada.getPayload())
                        .equals(json.readTree(json.writeValueAsString(operacao.payload())));
    }

    /**
     * A violação de integridade tem duas origens. Se outra requisição gravou a mesma operação ao
     * mesmo tempo, o índice único recusou esta, o efeito dela saiu junto, e a resposta é a
     * gravação da outra. Senão, o gesto conflita com um registro que já existe, como o id de
     * Produto já usado ou o código repetido, e é recusado.
     */
    private ResultadoDaOperacao depoisDeConflito(OperacaoRecebida operacao, UUID usuarioId,
            DataIntegrityViolationException conflito) {
        Optional<ResultadoDaOperacao> daOutraRequisicao = lerGravada(operacao, usuarioId);
        if (daOutraRequisicao.isPresent()) {
            return daOutraRequisicao.get();
        }
        log.warn("operacao {} do tipo {} recusada por conflito com registro existente: {}",
                operacao.operacaoId(), operacao.tipo(), conflito.getMessage());
        return gravarRecusa(operacao, usuarioId,
                "o registro conflita com outro ja existente e nao foi alterado");
    }

    /** A recusa é gravada depois do rollback do efeito, numa transação só dela. */
    private ResultadoDaOperacao gravarRecusa(OperacaoRecebida operacao, UUID usuarioId,
            String motivo) {
        try {
            return transacao.execute(status -> registros.findByOperacaoId(operacao.operacaoId())
                    .map(gravada -> respostaAoReenvio(gravada, operacao, usuarioId))
                    .orElseGet(() -> gravar(operacao, usuarioId, Instant.now(),
                            Resultado.NAO_APLICADA, null, motivo)));
        } catch (DataIntegrityViolationException concorrente) {
            return lerGravada(operacao, usuarioId).orElseGet(() ->
                    ResultadoDaOperacao.transitorio(operacao.operacaoId(),
                            "a recusa nao pode ser gravada agora; a operacao pode ser reenviada"));
        } catch (DataAccessException | TransactionException falha) {
            log.warn("recusa da operacao {} nao gravada por falha transitoria: {}",
                    operacao.operacaoId(), falha.getMessage());
            return ResultadoDaOperacao.transitorio(operacao.operacaoId(),
                    "falha temporaria no servidor; a operacao pode ser reenviada");
        }
    }

    private Optional<ResultadoDaOperacao> lerGravada(OperacaoRecebida operacao, UUID usuarioId) {
        return transacao.execute(status -> registros.findByOperacaoId(operacao.operacaoId())
                .map(gravada -> respostaAoReenvio(gravada, operacao, usuarioId)));
    }

    private static void registrarRecusaNoLog(OperacaoRecebida operacao, RuntimeException recusa) {
        boolean prevista = recusa instanceof IllegalArgumentException
                || recusa instanceof IllegalStateException
                || recusa instanceof AcessoNegadoException
                || recusa instanceof OperacaoRecusadaException;
        if (prevista) {
            log.warn("operacao {} do tipo {} recusada: {}", operacao.operacaoId(),
                    operacao.tipo(), recusa.getMessage());
        } else {
            log.error("operacao {} do tipo {} recusada por excecao inesperada",
                    operacao.operacaoId(), operacao.tipo(), recusa);
        }
    }

    private static String mensagemDe(RuntimeException recusa) {
        String mensagem = recusa.getMessage();
        return mensagem == null || mensagem.isBlank()
                ? recusa.getClass().getSimpleName()
                : mensagem;
    }
}
