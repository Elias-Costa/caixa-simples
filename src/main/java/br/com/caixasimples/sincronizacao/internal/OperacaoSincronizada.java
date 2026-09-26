package br.com.caixasimples.sincronizacao.internal;

import br.com.caixasimples.shared.ContaId;
import br.com.caixasimples.sincronizacao.OperacaoRecebida;
import br.com.caixasimples.sincronizacao.application.ResultadoDaOperacao;
import br.com.caixasimples.sincronizacao.application.ResultadoDaOperacao.Resultado;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.TenantId;
import org.hibernate.type.SqlTypes;

/**
 * O resultado gravado de uma operação que o dispositivo enviou, com o gesto como ele chegou.
 *
 * <p>É gravado na mesma transação do efeito, e o gesto e o resultado nunca mudam depois: essas
 * colunas são {@code updatable = false}. O índice único da migration V19, pela conta e pelo id da
 * operação, é o que impede que dois envios simultâneos da mesma operação apliquem o efeito duas
 * vezes.
 *
 * <p>A única escrita posterior é a conferência de uma revisão ou recusa pelo administrador, com
 * quem e quando, na migration V20. Conferir é registrar que alguém olhou: não desfaz nem refaz o
 * efeito, e o reenvio da operação continua devolvendo o resultado gravado.
 *
 * <p>O conteúdo e as dependências ficam em {@code jsonb} como texto já serializado, e quem os
 * compara num reenvio é o serviço, lendo com o mesmo leitor de JSON da API; comparar texto
 * dependeria da ordem das chaves, que o banco não guarda.
 *
 * <p>{@code contaId} é preenchido pelo Hibernate a partir do contexto e filtra toda consulta
 * automaticamente ({@link TenantId}); não há construtor nem setter que o receba (RNF05).
 */
@Entity
@Table(name = "operacao_sincronizada")
public class OperacaoSincronizada {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "conta_id", nullable = false, updatable = false)
    private UUID contaId;

    @Column(name = "operacao_id", nullable = false, updatable = false)
    private UUID operacaoId;

    @Column(name = "usuario_id", nullable = false, updatable = false)
    private UUID usuarioId;

    @Column(nullable = false, updatable = false)
    private String tipo;

    @Column(name = "registro_id", nullable = false, updatable = false)
    private UUID registroId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, updatable = false)
    private String payload;

    @Column(name = "versao_base", updatable = false)
    private Long versaoBase;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "depende_de", nullable = false, updatable = false)
    private String dependeDe;

    @Column(name = "criada_em", nullable = false, updatable = false)
    private Instant criadaEm;

    @Column(name = "recebida_em", nullable = false, updatable = false)
    private Instant recebidaEm;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Resultado resultado;

    @Column(updatable = false)
    private Long versao;

    @Column(updatable = false)
    private String detalhe;

    @Column(name = "conferida_em")
    private Instant conferidaEm;

    @Column(name = "conferida_por")
    private UUID conferidaPor;

    protected OperacaoSincronizada() {
        // exigido pelo JPA
    }

    /**
     * @param payloadEmJson   o conteúdo do gesto, já serializado
     * @param dependeDeEmJson a lista de dependências, já serializada
     * @param resultado       nunca o erro transitório, que não é gravado
     */
    public OperacaoSincronizada(OperacaoRecebida operacao, UUID usuarioId, String payloadEmJson,
            String dependeDeEmJson, Instant recebidaEm, Resultado resultado, Long versao,
            String detalhe) {
        if (resultado == Resultado.ERRO_TRANSITORIO) {
            throw new IllegalArgumentException(
                    "erro transitorio nao e gravado: o reenvio precisa tentar de novo");
        }
        this.id = UUID.randomUUID();
        this.operacaoId = operacao.operacaoId();
        this.usuarioId = Objects.requireNonNull(usuarioId, "usuarioId nao pode ser nulo");
        this.tipo = operacao.tipo();
        this.registroId = operacao.registroId();
        this.payload = Objects.requireNonNull(payloadEmJson, "payload nao pode ser nulo");
        this.versaoBase = operacao.versaoBase();
        this.dependeDe = Objects.requireNonNull(dependeDeEmJson, "dependeDe nao pode ser nulo");
        // A coluna guarda microssegundos, e o banco arredondaria o resto, às vezes para cima; o
        // reenvio compara o instante truncado, então é truncado que ele precisa ser gravado.
        this.criadaEm = operacao.criadoEm().truncatedTo(ChronoUnit.MICROS);
        this.recebidaEm = Objects.requireNonNull(recebidaEm, "recebidaEm nao pode ser nulo");
        this.resultado = Objects.requireNonNull(resultado, "resultado nao pode ser nulo");
        this.versao = versao;
        this.detalhe = detalhe;
    }

    /** O resultado como foi devolvido na primeira vez, para o reenvio receber o mesmo. */
    public ResultadoDaOperacao paraResultado() {
        return new ResultadoDaOperacao(operacaoId, resultado, versao, detalhe);
    }

    /** Se o gesto foi recusado, e portanto não produziu efeito. */
    public boolean naoFoiAplicada() {
        return resultado == Resultado.NAO_APLICADA;
    }

    /**
     * Registra que o administrador conferiu a revisão ou a recusa.
     *
     * <p>Conferir de novo não troca quem conferiu primeiro: a pergunta respondida é se alguém já
     * olhou, e a primeira resposta é a que vale.
     *
     * @throws IllegalStateException se a operação foi aplicada sem pendência, e não há o que
     *                               conferir
     */
    public void conferir(UUID administradorId, Instant instante) {
        Objects.requireNonNull(administradorId, "administradorId nao pode ser nulo");
        Objects.requireNonNull(instante, "instante nao pode ser nulo");
        if (resultado == Resultado.APLICADA) {
            throw new IllegalStateException(
                    "a operacao foi aplicada sem pendencia e nao tem o que conferir");
        }
        if (conferidaEm != null) {
            return;
        }
        this.conferidaEm = instante;
        this.conferidaPor = administradorId;
    }

    public UUID getOperacaoId() {
        return operacaoId;
    }

    public UUID getUsuarioId() {
        return usuarioId;
    }

    public String getTipo() {
        return tipo;
    }

    public UUID getRegistroId() {
        return registroId;
    }

    public String getPayload() {
        return payload;
    }

    public Long getVersaoBase() {
        return versaoBase;
    }

    public String getDependeDe() {
        return dependeDe;
    }

    public Instant getCriadaEm() {
        return criadaEm;
    }

    public Instant getRecebidaEm() {
        return recebidaEm;
    }

    public Resultado getResultado() {
        return resultado;
    }

    public String getDetalhe() {
        return detalhe;
    }

    public Instant getConferidaEm() {
        return conferidaEm;
    }

    public UUID getConferidaPor() {
        return conferidaPor;
    }

    /** Existe para o teste de isolamento poder afirmar de que conta a linha é. */
    public ContaId getContaId() {
        return ContaId.de(contaId);
    }
}
