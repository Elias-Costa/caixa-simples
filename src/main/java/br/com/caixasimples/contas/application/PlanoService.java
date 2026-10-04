package br.com.caixasimples.contas.application;

import br.com.caixasimples.contas.Plano;
import br.com.caixasimples.contas.RecursoDoPlano;
import br.com.caixasimples.contas.SituacaoDoPlano;
import br.com.caixasimples.contas.internal.AssinaturaDePedido;
import br.com.caixasimples.contas.internal.CicloDeVencimento;
import br.com.caixasimples.contas.internal.ConfiguracaoDosPlanos;
import br.com.caixasimples.contas.internal.Conta;
import br.com.caixasimples.contas.internal.ContaRepository;
import br.com.caixasimples.contas.internal.PedidoDePlano;
import br.com.caixasimples.contas.internal.PedidoDePlanoRepository;
import br.com.caixasimples.shared.ContaId;
import br.com.caixasimples.shared.FusoDeReferencia;
import br.com.caixasimples.shared.Money;
import br.com.caixasimples.shared.TenantContext;
import br.com.caixasimples.shared.UsuarioContext;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A troca de plano pela própria Conta (RF31): consultar o plano, pedir adesão, upgrade ou
 * renovação, e aplicar o código que o mantenedor entrega depois de conferir o Pix no extrato.
 *
 * <p><strong>Só o administrador chama</strong>, e cada caso de uso pergunta isso na primeira linha.
 * A Conta é a do contexto, e o pedido só é encontrado nela (RNF05).
 *
 * <p>Pedir e aplicar travam a linha da Conta, como a inativação de administrador: dois pedidos, ou
 * duas aplicações do mesmo código, entram em fila, e o segundo enxerga o que o primeiro gravou.
 * Assim o reenvio do código não ativa duas vezes, e o pedido novo sempre encontra o aberto que vai
 * substituir.
 *
 * <p>O dia de hoje é o do balcão, lido uma vez por caso de uso. As datas e o valor proporcional
 * vêm do ciclo de vencimento, pela Conta. O valor do pedido é o do dia em que foi feito: se a
 * mensalidade mudar depois, o pedido aberto continua com o valor que a Conta viu.
 */
@Service
public class PlanoService {

    private static final DateTimeFormatter DATA = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final ContaRepository contas;
    private final PedidoDePlanoRepository pedidos;
    private final ConfiguracaoDosPlanos configuracao;
    private final AssinaturaDePedido assinatura;

    PlanoService(ContaRepository contas, PedidoDePlanoRepository pedidos,
            ConfiguracaoDosPlanos configuracao, AssinaturaDePedido assinatura) {
        this.contas = contas;
        this.pedidos = pedidos;
        this.configuracao = configuracao;
        this.assinatura = assinatura;
    }

    /**
     * O plano da Conta, a situação no ciclo, as mensalidades, o que pode ser pedido hoje e o pedido
     * à espera do código, para a tela do plano.
     *
     * @throws br.com.caixasimples.shared.AcessoNegadoException se quem chama não é ADMIN
     */
    @Transactional(readOnly = true)
    public EstadoDoPlano consultar() {
        UsuarioContext.exigirAdmin();
        return estado(contaDoContexto(), hojeNoBalcao());
    }

    /**
     * Pede o plano. Quem pede escolhe só o plano, e o tipo sai do estado da Conta: adesão a partir
     * do gratuito, upgrade do intermediário ao completo, renovação do plano atual. Um pedido aberto
     * é substituído, e o código dele deixa de valer.
     *
     * @throws br.com.caixasimples.shared.AcessoNegadoException se quem chama não é ADMIN
     * @throws IllegalArgumentException se o plano não foi informado
     * @throws IllegalStateException se o plano pedido é o gratuito ou é menor que o atual, ou se é
     *         o completo com o período do intermediário já vencido
     */
    @Transactional
    public PedidoNaConta pedir(Plano desejado) {
        UsuarioContext.exigirAdmin();
        if (desejado == null) {
            throw new IllegalArgumentException("informe o plano");
        }
        LocalDate hoje = hojeNoBalcao();
        Conta conta = contaTravada();

        Optional<String> recusa = recusa(conta, desejado, hoje);
        if (recusa.isPresent()) {
            throw new IllegalStateException(recusa.get());
        }

        Optional<PedidoDePlano> aberto = pedidos.findBySituacao(PedidoDePlano.Situacao.ABERTO);
        if (aberto.isPresent()) {
            aberto.get().substituir();
            // O índice único parcial confere a regra a cada linha gravada, e o Hibernate grava as
            // inserções antes das atualizações: sem gravar a substituição agora, o pedido novo
            // encontraria o antigo ainda aberto.
            pedidos.flush();
        }

        UUID quemPede = UsuarioContext.exigirAtual().usuarioId();
        PedidoDePlano novo = pedidos.save(novoPedido(propor(conta, desejado, hoje), quemPede));
        return PedidoNaConta.de(novo);
    }

    /**
     * Aplica o código que o mantenedor entregou para o pedido. O código certo de um pedido já
     * aplicado não faz nada, para o reenvio não ativar duas vezes.
     *
     * <p>A ordem das recusas: o pedido tem de existir nesta Conta, o código tem de ser o dele, e só
     * então importa se ele foi substituído.
     *
     * @return o estado do plano depois da aplicação
     * @throws br.com.caixasimples.shared.AcessoNegadoException se quem chama não é ADMIN
     * @throws PedidoDePlanoNaoEncontradoException se o pedido não existe nesta Conta
     * @throws IllegalArgumentException se o código está em branco ou não é o deste pedido
     * @throws IllegalStateException se o pedido foi substituído por outro
     */
    @Transactional
    public EstadoDoPlano aplicarCodigo(UUID pedidoId, String codigo) {
        UsuarioContext.exigirAdmin();
        if (codigo == null || codigo.isBlank()) {
            throw new IllegalArgumentException("informe o codigo");
        }
        LocalDate hoje = hojeNoBalcao();
        Conta conta = contaTravada();

        PedidoDePlano pedido = pedidos.findById(pedidoId)
                .orElseThrow(() -> new PedidoDePlanoNaoEncontradoException(pedidoId));
        if (!assinatura.confere(pedido.getId(), pedido.getPlano(), codigo)) {
            throw new IllegalArgumentException("o codigo nao e o deste pedido");
        }

        switch (pedido.getSituacao()) {
            case APLICADO -> {
                // Reenvio do mesmo código: o efeito já está gravado.
            }
            case SUBSTITUIDO -> throw new IllegalStateException(
                    "este pedido foi substituido por outro; o codigo dele nao vale mais");
            case ABERTO -> aplicar(conta, pedido, hoje);
        }
        return estado(conta, hoje);
    }

    private void aplicar(Conta conta, PedidoDePlano pedido, LocalDate hoje) {
        UUID quemAplica = UsuarioContext.exigirAtual().usuarioId();
        Instant agora = Instant.now();
        switch (pedido.getTipo()) {
            case ADESAO -> {
                CicloDeVencimento.Periodo primeiro = conta.ativarPlano(pedido.getPlano(), hoje);
                pedido.aplicarAdesao(primeiro, quemAplica, agora);
            }
            case UPGRADE -> {
                // Vale mesmo aplicado depois do fim do período pedido: o Pix foi conferido dentro
                // dele, e a Conta passa a renovar como completo.
                conta.subirParaCompleto();
                pedido.aplicar(quemAplica, agora);
            }
            case RENOVACAO -> {
                conta.renovar(pedido.getPlano(), pedido.getPeriodoFim());
                pedido.aplicar(quemAplica, agora);
            }
        }
    }

    /**
     * Por que o plano desejado não pode ser pedido hoje, ou vazio se pode. A mesma regra decide o
     * pedido e as opções que a tela oferece.
     */
    private Optional<String> recusa(Conta conta, Plano desejado, LocalDate hoje) {
        if (desejado == Plano.GRATIS) {
            return Optional.of("o plano gratuito nao tem pedido");
        }
        if (conta.getPlano() == Plano.COMPLETO && desejado == Plano.CAIXA_SIMPLES) {
            return Optional.of("a troca para um plano menor nao e oferecida");
        }
        if (conta.getPlano() == Plano.CAIXA_SIMPLES && desejado == Plano.COMPLETO
                && !hoje.isBefore(conta.getProximoVencimento())) {
            return Optional.of("o plano Caixa Simples venceu em "
                    + DATA.format(conta.getProximoVencimento())
                    + "; renove-o antes de pedir o plano Completo");
        }
        return Optional.empty();
    }

    /** O que o pedido do plano desejado seria hoje. Só chamado quando não há recusa. */
    private Proposta propor(Conta conta, Plano desejado, LocalDate hoje) {
        if (conta.getPlano() == Plano.GRATIS) {
            return new Proposta(PedidoDePlano.Tipo.ADESAO, desejado,
                    configuracao.mensalidade(desejado), null, null);
        }
        if (conta.getPlano() == desejado) {
            CicloDeVencimento.Periodo periodo = conta.periodoDaRenovacao(hoje);
            return new Proposta(PedidoDePlano.Tipo.RENOVACAO, desejado,
                    configuracao.mensalidade(desejado), periodo.inicio(), periodo.fim());
        }
        Money diferenca = configuracao.mensalidade(Plano.COMPLETO)
                .subtrair(configuracao.mensalidade(Plano.CAIXA_SIMPLES));
        return new Proposta(PedidoDePlano.Tipo.UPGRADE, Plano.COMPLETO,
                conta.valorDoUpgrade(diferenca, hoje), hoje, conta.getProximoVencimento());
    }

    private static PedidoDePlano novoPedido(Proposta proposta, UUID quemPede) {
        Instant agora = Instant.now();
        return switch (proposta.tipo()) {
            case ADESAO -> PedidoDePlano.adesao(proposta.plano(), proposta.valor(), quemPede, agora);
            case UPGRADE -> PedidoDePlano.upgrade(proposta.valor(),
                    new CicloDeVencimento.Periodo(proposta.periodoInicio(), proposta.periodoFim()),
                    quemPede, agora);
            case RENOVACAO -> PedidoDePlano.renovacao(proposta.plano(), proposta.valor(),
                    new CicloDeVencimento.Periodo(proposta.periodoInicio(), proposta.periodoFim()),
                    quemPede, agora);
        };
    }

    private EstadoDoPlano estado(Conta conta, LocalDate hoje) {
        List<Proposta> propostas = new ArrayList<>();
        for (Plano plano : List.of(Plano.CAIXA_SIMPLES, Plano.COMPLETO)) {
            if (recusa(conta, plano, hoje).isEmpty()) {
                propostas.add(propor(conta, plano, hoje));
            }
        }
        PedidoNaConta aberto = pedidos.findBySituacao(PedidoDePlano.Situacao.ABERTO)
                .map(PedidoNaConta::de)
                .orElse(null);
        return new EstadoDoPlano(conta.getPlano(), conta.situacao(hoje),
                conta.getProximoVencimento(), conta.inicioDaSuspensao(), conta.recursos(hoje),
                configuracao.mensalidade(Plano.CAIXA_SIMPLES),
                configuracao.mensalidade(Plano.COMPLETO), propostas, aberto);
    }

    private Conta contaDoContexto() {
        ContaId contaId = TenantContext.exigirAtual();
        return contas.findById(contaId.valor())
                .orElseThrow(() -> new IllegalStateException(
                        "conta do contexto nao existe: " + contaId));
    }

    private Conta contaTravada() {
        ContaId contaId = TenantContext.exigirAtual();
        return contas.buscarParaAtualizar(contaId.valor())
                .orElseThrow(() -> new IllegalStateException(
                        "conta do contexto nao existe: " + contaId));
    }

    private static LocalDate hojeNoBalcao() {
        return LocalDate.now(FusoDeReferencia.DO_BALCAO);
    }

    /**
     * O plano da Conta hoje, para a tela do plano.
     *
     * @param vencimento        o fim do último período pago; nulo no gratuito
     * @param inicioDaSuspensao o primeiro dia sem os recursos pagos se não houver renovação; nulo
     *                          no gratuito
     * @param recursos          os recursos que valem hoje
     * @param propostas         o que pode ser pedido hoje, com o valor de hoje
     * @param pedidoAberto      o pedido à espera do código; nulo se não houver
     */
    public record EstadoDoPlano(Plano plano, SituacaoDoPlano situacao, LocalDate vencimento,
            LocalDate inicioDaSuspensao, Set<RecursoDoPlano> recursos, Money mensalidadeCaixaSimples,
            Money mensalidadeCompleto, List<Proposta> propostas, PedidoNaConta pedidoAberto) {
    }

    /**
     * Um pedido que a Conta pode fazer hoje.
     *
     * @param periodoInicio nulo na adesão, cujo período começa no dia em que o código é aplicado
     * @param periodoFim    o vencimento que fecha o período, exclusive; nulo na adesão
     */
    public record Proposta(PedidoDePlano.Tipo tipo, Plano plano, Money valor,
            LocalDate periodoInicio, LocalDate periodoFim) {
    }

    /**
     * Um pedido gravado, com o que a Conta manda pelo canal comercial: id, tipo, plano, valor e
     * período. Sem nome de negócio nem de pessoa.
     */
    public record PedidoNaConta(UUID id, PedidoDePlano.Tipo tipo, Plano plano, Money valor,
            LocalDate periodoInicio, LocalDate periodoFim, PedidoDePlano.Situacao situacao,
            Instant criadoEm) {

        static PedidoNaConta de(PedidoDePlano pedido) {
            return new PedidoNaConta(pedido.getId(), pedido.getTipo(), pedido.getPlano(),
                    pedido.getValor(), pedido.getPeriodoInicio(), pedido.getPeriodoFim(),
                    pedido.getSituacao(), pedido.getCriadoEm());
        }
    }
}
