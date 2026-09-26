package br.com.caixasimples.sincronizacao.application;

import br.com.caixasimples.shared.FusoDeReferencia;
import br.com.caixasimples.shared.UsuarioContext;
import br.com.caixasimples.sincronizacao.application.ResultadoDaOperacao.Resultado;
import br.com.caixasimples.sincronizacao.internal.OperacaoSincronizada;
import br.com.caixasimples.sincronizacao.internal.OperacaoSincronizadaRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * O que o administrador precisa conferir depois que os dispositivos sincronizaram: as operações
 * aplicadas com revisão e as recusadas, de todos os usuários da Conta, com o gesto como chegou.
 *
 * <p>Quem operou vê a revisão no próprio aparelho; o administrador vê aqui a de todos, inclusive
 * a de um aparelho que ele não tem nas mãos. Conferir registra quem olhou e quando, e tira a
 * operação da lista de pendências; não muda o gesto, o resultado nem o efeito.
 *
 * <p>Tudo aqui é só do administrador, verificado na primeira linha de cada caso de uso, e passa
 * pelo filtro de tenant do registro de operações (RNF05).
 */
@Service
public class RevisaoService {

    private final OperacaoSincronizadaRepository registros;
    private final ObjectMapper json;

    RevisaoService(OperacaoSincronizadaRepository registros, ObjectMapper json) {
        this.registros = registros;
        this.json = json;
    }

    /** As revisões e recusas que ninguém conferiu, de qualquer dia, da mais antiga à mais nova. */
    @Transactional(readOnly = true)
    public List<RevisaoDeOperacao> pendentes() {
        UsuarioContext.exigirAdmin();
        return registros.findByResultadoNotAndConferidaEmIsNullOrderByRecebidaEm(
                        Resultado.APLICADA).stream()
                .map(this::paraRevisao)
                .toList();
    }

    /** As conferidas no dia, pelo relógio do balcão, na ordem da conferência. */
    @Transactional(readOnly = true)
    public List<RevisaoDeOperacao> conferidasNoDia(LocalDate dia) {
        UsuarioContext.exigirAdmin();
        Objects.requireNonNull(dia, "dia nao pode ser nulo");
        return registros
                .findByConferidaEmGreaterThanEqualAndConferidaEmLessThanOrderByConferidaEm(
                        FusoDeReferencia.inicioDoDia(dia),
                        FusoDeReferencia.inicioDoDiaSeguinte(dia))
                .stream()
                .map(this::paraRevisao)
                .toList();
    }

    /**
     * Registra que o administrador atual conferiu a operação. Conferir de novo não troca a
     * primeira conferência.
     *
     * @throws RevisaoNaoEncontradaException se a operação não existe nesta Conta
     * @throws IllegalStateException         se a operação foi aplicada sem pendência
     */
    @Transactional
    public void conferir(UUID operacaoId) {
        UsuarioContext.exigirAdmin();
        OperacaoSincronizada operacao = registros.findByOperacaoId(operacaoId)
                .orElseThrow(() -> new RevisaoNaoEncontradaException(operacaoId));
        operacao.conferir(UsuarioContext.exigirAtual().usuarioId(), Instant.now());
    }

    private RevisaoDeOperacao paraRevisao(OperacaoSincronizada operacao) {
        return new RevisaoDeOperacao(operacao.getOperacaoId(), operacao.getUsuarioId(),
                operacao.getTipo(), operacao.getRegistroId(), json.readTree(operacao.getPayload()),
                operacao.getCriadaEm(), operacao.getRecebidaEm(), operacao.getResultado(),
                operacao.getDetalhe(), operacao.getConferidaEm(), operacao.getConferidaPor());
    }

    /**
     * Uma operação que pede o olhar do administrador.
     *
     * @param usuarioId    quem enviou; o nome fica com a tela, que já lê os usuários da Conta
     * @param payload      o conteúdo do gesto como o dispositivo gravou
     * @param criadaEm     o instante do gesto no relógio do dispositivo
     * @param recebidaEm   quando o servidor aplicou ou recusou
     * @param conferidaEm  nulo enquanto ninguém conferiu
     * @param conferidaPor o administrador que conferiu, ou nulo
     */
    public record RevisaoDeOperacao(UUID operacaoId, UUID usuarioId, String tipo, UUID registroId,
            JsonNode payload, Instant criadaEm, Instant recebidaEm, Resultado resultado,
            String detalhe, Instant conferidaEm, UUID conferidaPor) {
    }
}
