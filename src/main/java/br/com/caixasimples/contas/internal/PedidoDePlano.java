package br.com.caixasimples.contas.internal;

import br.com.caixasimples.contas.Plano;
import br.com.caixasimples.shared.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

/**
 * O pedido de plano que o administrador faz no aplicativo: adesão, upgrade ou renovação. Entidade
 * única, sem membros.
 *
 * <p>O pedido é o que o mantenedor confere contra o Pix recebido, pelo id, pelo valor e pelo
 * período, e o código de ativação fica preso ao id, ao plano e ao valor dele. Guarda quem pediu e
 * quem aplicou, e o valor do dia do pedido, que não muda se a mensalidade mudar depois.
 *
 * <p>{@code contaId} é preenchido pelo Hibernate a partir do tenant do contexto, e não há
 * construtor que o receba (RNF05). Um id de pedido de outra Conta não é encontrado.
 *
 * <p>A Conta tem no máximo um pedido aberto: pedir de novo substitui o aberto, e o código do
 * substituído deixa de valer. O índice único parcial do banco segura isso com dois pedidos
 * simultâneos.
 */
@Entity
@Table(name = "pedido_de_plano")
public class PedidoDePlano {

    /** Adesão sai do plano gratuito; upgrade, do intermediário ao completo; renovação paga um mês. */
    public enum Tipo {
        ADESAO,
        UPGRADE,
        RENOVACAO
    }

    public enum Situacao {
        ABERTO,
        APLICADO,
        SUBSTITUIDO
    }

    @Id
    private UUID id;

    @TenantId
    @Column(name = "conta_id", nullable = false, updatable = false)
    private UUID contaId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Tipo tipo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Plano plano;

    @Column(nullable = false, updatable = false)
    private BigDecimal valor;

    /** Nulo na adesão até a aplicação, porque o ciclo começa no dia em que o código é aplicado. */
    @Column(name = "periodo_inicio")
    private LocalDate periodoInicio;

    /** O vencimento seguinte, exclusive. Nulo junto com o início. */
    @Column(name = "periodo_fim")
    private LocalDate periodoFim;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Situacao situacao;

    @Column(name = "criado_em", nullable = false, updatable = false)
    private Instant criadoEm;

    @Column(name = "criado_por", nullable = false, updatable = false)
    private UUID criadoPor;

    @Column(name = "aplicado_em")
    private Instant aplicadoEm;

    @Column(name = "aplicado_por")
    private UUID aplicadoPor;

    protected PedidoDePlano() {
        // exigido pelo JPA
    }

    private PedidoDePlano(Tipo tipo, Plano plano, Money valor, CicloDeVencimento.Periodo periodo,
            UUID criadoPor, Instant agora) {
        Objects.requireNonNull(plano, "plano nao pode ser nulo");
        Objects.requireNonNull(valor, "valor nao pode ser nulo");
        if (plano == Plano.GRATIS) {
            throw new IllegalArgumentException("o plano gratuito nao tem pedido");
        }
        if (valor.isNegativo()) {
            throw new IllegalArgumentException("valor do pedido nao pode ser negativo: " + valor);
        }
        this.id = UUID.randomUUID();
        this.tipo = tipo;
        this.plano = plano;
        this.valor = valor.valor();
        this.periodoInicio = periodo == null ? null : periodo.inicio();
        this.periodoFim = periodo == null ? null : periodo.fim();
        this.situacao = Situacao.ABERTO;
        this.criadoPor = Objects.requireNonNull(criadoPor, "criadoPor nao pode ser nulo");
        this.criadoEm = Objects.requireNonNull(agora, "agora nao pode ser nulo");
    }

    /** Sem período: ele só existe quando o código for aplicado. */
    public static PedidoDePlano adesao(Plano plano, Money mensalidade, UUID criadoPor,
            Instant agora) {
        return new PedidoDePlano(Tipo.ADESAO, plano, mensalidade, null, criadoPor, agora);
    }

    /** De hoje, em que o crédito é presumido, até o vencimento do período já pago. */
    public static PedidoDePlano upgrade(Money diferencaProporcional,
            CicloDeVencimento.Periodo restante, UUID criadoPor, Instant agora) {
        Objects.requireNonNull(restante, "periodo nao pode ser nulo");
        return new PedidoDePlano(Tipo.UPGRADE, Plano.COMPLETO, diferencaProporcional, restante,
                criadoPor, agora);
    }

    public static PedidoDePlano renovacao(Plano plano, Money mensalidade,
            CicloDeVencimento.Periodo periodo, UUID criadoPor, Instant agora) {
        Objects.requireNonNull(periodo, "periodo nao pode ser nulo");
        return new PedidoDePlano(Tipo.RENOVACAO, plano, mensalidade, periodo, criadoPor, agora);
    }

    /** A Conta pediu de novo antes de aplicar este código. */
    public void substituir() {
        exigirAberto();
        this.situacao = Situacao.SUBSTITUIDO;
    }

    /** O código conferiu e o efeito na Conta foi feito na mesma transação. */
    public void aplicar(UUID aplicadoPor, Instant agora) {
        exigirAberto();
        if (tipo == Tipo.ADESAO) {
            throw new IllegalStateException("a adesao se aplica com o primeiro periodo");
        }
        registrarAplicacao(aplicadoPor, agora);
    }

    /** A adesão aplicada guarda o primeiro período, que só começa agora. */
    public void aplicarAdesao(CicloDeVencimento.Periodo primeiro, UUID aplicadoPor, Instant agora) {
        exigirAberto();
        if (tipo != Tipo.ADESAO) {
            throw new IllegalStateException("so a adesao recebe o primeiro periodo na aplicacao");
        }
        Objects.requireNonNull(primeiro, "periodo nao pode ser nulo");
        this.periodoInicio = primeiro.inicio();
        this.periodoFim = primeiro.fim();
        registrarAplicacao(aplicadoPor, agora);
    }

    private void registrarAplicacao(UUID aplicadoPor, Instant agora) {
        this.situacao = Situacao.APLICADO;
        this.aplicadoPor = Objects.requireNonNull(aplicadoPor, "aplicadoPor nao pode ser nulo");
        this.aplicadoEm = Objects.requireNonNull(agora, "agora nao pode ser nulo");
    }

    private void exigirAberto() {
        if (situacao != Situacao.ABERTO) {
            throw new IllegalStateException("o pedido de plano " + id + " nao esta aberto: "
                    + situacao);
        }
    }

    public UUID getId() {
        return id;
    }

    public Tipo getTipo() {
        return tipo;
    }

    public Plano getPlano() {
        return plano;
    }

    public Money getValor() {
        return Money.de(valor);
    }

    public LocalDate getPeriodoInicio() {
        return periodoInicio;
    }

    public LocalDate getPeriodoFim() {
        return periodoFim;
    }

    public Situacao getSituacao() {
        return situacao;
    }

    public Instant getCriadoEm() {
        return criadoEm;
    }

    public UUID getCriadoPor() {
        return criadoPor;
    }

    public Instant getAplicadoEm() {
        return aplicadoEm;
    }

    public UUID getAplicadoPor() {
        return aplicadoPor;
    }
}
