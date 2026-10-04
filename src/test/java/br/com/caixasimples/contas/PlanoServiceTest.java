package br.com.caixasimples.contas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.tuple;
import static org.awaitility.Awaitility.await;

import br.com.caixasimples.TesteDeIntegracao;
import br.com.caixasimples.contas.CriadorDeContaDeTeste.ContaCriada;
import br.com.caixasimples.contas.CriadorDeContaDeTeste.UsuarioCriado;
import br.com.caixasimples.contas.application.PlanoService;
import br.com.caixasimples.contas.application.PlanoService.EstadoDoPlano;
import br.com.caixasimples.contas.application.PlanoService.PedidoNaConta;
import br.com.caixasimples.contas.application.PlanoService.Proposta;
import br.com.caixasimples.contas.internal.AssinaturaDePedido;
import br.com.caixasimples.contas.internal.CicloDeVencimento;
import br.com.caixasimples.contas.internal.PedidoDePlano;
import br.com.caixasimples.contas.internal.PedidoDePlanoRepository;
import br.com.caixasimples.shared.AcessoNegadoException;
import br.com.caixasimples.shared.FusoDeReferencia;
import br.com.caixasimples.shared.Money;
import br.com.caixasimples.shared.TenantContext;
import br.com.caixasimples.shared.UsuarioContext;
import java.time.Duration;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A troca de plano pela própria Conta (RF31) contra o banco de verdade: o pedido, o código que o
 * mantenedor entrega depois de conferir o Pix e o ciclo mensal.
 *
 * <p>O código vem da mesma assinatura que a aplicação usa para conferir, com o segredo da suíte. O
 * teste do script de operação prova, pelo vetor fixo, que o script do mantenedor gera o mesmo.
 *
 * <p>O vencimento é posto no passado pela fixture, porque a aplicação nunca o recua: é assim que
 * os testes chegam à tolerância e à suspensão sem esperar um mês.
 */
class PlanoServiceTest extends TesteDeIntegracao {

    private static final String SENHA_DE_TESTE = "uma senha longa de teste";
    private static final Duration ESPERA = Duration.ofSeconds(10);
    private static final Money CAIXA_SIMPLES = Money.de(MENSALIDADE_CAIXA_SIMPLES);
    private static final Money COMPLETO = Money.de(MENSALIDADE_COMPLETO);

    @Autowired
    private PlanoService planos;

    @Autowired
    private PedidoDePlanoRepository pedidos;

    @Autowired
    private AssinaturaDePedido assinatura;

    @Autowired
    private CriadorDeContaDeTeste criador;

    @Autowired
    private TransactionTemplate transacao;

    @Autowired
    private JdbcClient jdbc;

    @AfterEach
    void limparContexto() {
        TenantContext.limpar();
        UsuarioContext.limpar();
    }

    @Test
    @DisplayName("no plano grátis, a tela mostra as mensalidades e as duas adesões, sem vencimento nem recurso pago")
    void planoGratisMostraAsAdesoes() {
        ContaCriada conta = criador.criar("Cafeteria Aurora", SENHA_DE_TESTE);

        EstadoDoPlano estado = conta.comoUsuario(planos::consultar);

        assertThat(estado.plano()).isEqualTo(Plano.GRATIS);
        assertThat(estado.situacao()).isEqualTo(SituacaoDoPlano.SEM_MENSALIDADE);
        assertThat(estado.vencimento()).isNull();
        assertThat(estado.inicioDaSuspensao()).isNull();
        assertThat(estado.recursos()).isEmpty();
        assertThat(estado.mensalidadeCaixaSimples()).isEqualTo(CAIXA_SIMPLES);
        assertThat(estado.mensalidadeCompleto()).isEqualTo(COMPLETO);
        assertThat(estado.pedidoAberto()).isNull();
        assertThat(estado.propostas())
                .extracting(Proposta::tipo, Proposta::plano, Proposta::valor, Proposta::periodoInicio)
                .containsExactly(
                        tuple(PedidoDePlano.Tipo.ADESAO, Plano.CAIXA_SIMPLES, CAIXA_SIMPLES, null),
                        tuple(PedidoDePlano.Tipo.ADESAO, Plano.COMPLETO, COMPLETO, null));
    }

    @Test
    @DisplayName("a adesão espera o código: o plano só muda com o código do pedido, e o reenvio não ativa de novo")
    void adesaoEsperaOCodigo() {
        ContaCriada conta = criador.criar("Padaria Central", SENHA_DE_TESTE);

        PedidoNaConta pedido = conta.comoUsuario(() -> planos.pedir(Plano.COMPLETO));

        assertThat(pedido.tipo()).isEqualTo(PedidoDePlano.Tipo.ADESAO);
        assertThat(pedido.valor()).isEqualTo(COMPLETO);
        assertThat(pedido.periodoInicio()).as("o ciclo começa na aplicação").isNull();
        assertThat(pedido.situacao()).isEqualTo(PedidoDePlano.Situacao.ABERTO);
        EstadoDoPlano antes = conta.comoUsuario(planos::consultar);
        assertThat(antes.plano()).as("o pedido não ativa nada").isEqualTo(Plano.GRATIS);
        assertThat(antes.pedidoAberto().id()).isEqualTo(pedido.id());

        LocalDate hoje = hoje();
        LocalDate vencimento = CicloDeVencimento.seguinte(hoje, hoje.getDayOfMonth());
        EstadoDoPlano depois = conta.comoUsuario(() ->
                planos.aplicarCodigo(pedido.id(), assinatura.codigo(pedido.id(), Plano.COMPLETO)));

        assertThat(depois.plano()).isEqualTo(Plano.COMPLETO);
        assertThat(depois.situacao()).isEqualTo(SituacaoDoPlano.EM_DIA);
        assertThat(depois.vencimento()).isEqualTo(vencimento);
        assertThat(depois.recursos()).isEqualTo(EnumSet.allOf(RecursoDoPlano.class));
        assertThat(depois.pedidoAberto()).isNull();
        assertThat(depois.propostas())
                .extracting(Proposta::tipo, Proposta::plano, Proposta::valor, Proposta::periodoInicio)
                .containsExactly(tuple(PedidoDePlano.Tipo.RENOVACAO, Plano.COMPLETO, COMPLETO,
                        vencimento));

        EstadoDoPlano reenvio = conta.comoUsuario(() ->
                planos.aplicarCodigo(pedido.id(), assinatura.codigo(pedido.id(), Plano.COMPLETO)));

        assertThat(reenvio.vencimento()).as("o reenvio não renova").isEqualTo(vencimento);
        conta.comoUsuario(() -> assertThat(pedidos.findById(pedido.id())).get()
                .satisfies(aplicado -> {
                    assertThat(aplicado.getSituacao()).isEqualTo(PedidoDePlano.Situacao.APLICADO);
                    assertThat(aplicado.getPeriodoInicio()).isEqualTo(hoje);
                    assertThat(aplicado.getPeriodoFim()).isEqualTo(vencimento);
                    assertThat(aplicado.getCriadoPor()).isEqualTo(conta.usuarioId());
                    assertThat(aplicado.getAplicadoPor()).isEqualTo(conta.usuarioId());
                }));
    }

    @Test
    @DisplayName("código errado, de outro plano ou de outro pedido é recusado sem mudar nada")
    void codigoErradoRecusado() {
        ContaCriada conta = criador.criar("Mercearia da Rua", SENHA_DE_TESTE);
        UUID pedido = conta.comoUsuario(() -> planos.pedir(Plano.COMPLETO)).id();

        for (String errado : List.of("0000-0000-0000-0000",
                assinatura.codigo(pedido, Plano.CAIXA_SIMPLES),
                assinatura.codigo(UUID.randomUUID(), Plano.COMPLETO))) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> conta.comoUsuario(() -> planos.aplicarCodigo(pedido, errado)))
                    .withMessageContaining("codigo");
        }
        assertThatIllegalArgumentException()
                .isThrownBy(() -> conta.comoUsuario(() -> planos.aplicarCodigo(pedido, "  ")));

        EstadoDoPlano estado = conta.comoUsuario(planos::consultar);
        assertThat(estado.plano()).isEqualTo(Plano.GRATIS);
        assertThat(estado.pedidoAberto().id()).isEqualTo(pedido);
    }

    @Test
    @DisplayName("pedir de novo substitui o pedido aberto, e o código do substituído deixa de valer")
    void novoPedidoSubstituiOAberto() {
        ContaCriada conta = criador.criar("Loja da Esquina", SENHA_DE_TESTE);
        PedidoNaConta primeiro = conta.comoUsuario(() -> planos.pedir(Plano.CAIXA_SIMPLES));
        PedidoNaConta segundo = conta.comoUsuario(() -> planos.pedir(Plano.COMPLETO));

        assertThat(conta.comoUsuario(planos::consultar).pedidoAberto().id()).isEqualTo(segundo.id());
        assertThatIllegalStateException()
                .isThrownBy(() -> conta.comoUsuario(() -> planos.aplicarCodigo(primeiro.id(),
                        assinatura.codigo(primeiro.id(), Plano.CAIXA_SIMPLES))))
                .withMessageContaining("substituido");

        EstadoDoPlano estado = conta.comoUsuario(() -> planos.aplicarCodigo(segundo.id(),
                assinatura.codigo(segundo.id(), Plano.COMPLETO)));

        assertThat(estado.plano()).isEqualTo(Plano.COMPLETO);
        conta.comoUsuario(() -> assertThat(pedidos.findById(primeiro.id())).get()
                .extracting(PedidoDePlano::getSituacao)
                .isEqualTo(PedidoDePlano.Situacao.SUBSTITUIDO));
    }

    @Test
    @DisplayName("o operador não consulta, não pede e não aplica código (RF30)")
    void operadorNaoTrocaPlano() {
        ContaCriada dono = criador.criar("Barbearia do Centro", SENHA_DE_TESTE);
        UsuarioCriado operador = criador.criarOperadorEm(dono.contaId(), "Atendente");
        PedidoNaConta pedido = dono.comoUsuario(() -> planos.pedir(Plano.CAIXA_SIMPLES));
        String codigo = assinatura.codigo(pedido.id(), Plano.CAIXA_SIMPLES);

        assertThatExceptionOfType(AcessoNegadoException.class)
                .isThrownBy(() -> operador.comoUsuario(planos::consultar));
        assertThatExceptionOfType(AcessoNegadoException.class)
                .isThrownBy(() -> operador.comoUsuario(() -> planos.pedir(Plano.COMPLETO)));
        assertThatExceptionOfType(AcessoNegadoException.class)
                .isThrownBy(() -> operador.comoUsuario(() -> planos.aplicarCodigo(pedido.id(), codigo)));

        assertThat(dono.comoUsuario(planos::consultar).plano()).isEqualTo(Plano.GRATIS);
    }

    @Test
    @DisplayName("o upgrade no meio do período cobra a diferença proporcional e mantém o vencimento")
    void upgradeProporcionalMantemOVencimento() {
        ContaCriada conta = criador.criar("Cafeteria do Largo", SENHA_DE_TESTE);
        criador.contratar(conta.contaId(), Plano.CAIXA_SIMPLES);
        criador.vencerPlano(conta.contaId(), -10);
        LocalDate hoje = hoje();
        LocalDate vencimento = hoje.plusDays(10);
        long diasDoPeriodo = ChronoUnit.DAYS.between(
                CicloDeVencimento.anterior(vencimento, vencimento.getDayOfMonth()), vencimento);
        Money esperado = COMPLETO.subtrair(CAIXA_SIMPLES).proporcionalArredondando(10, diasDoPeriodo);

        PedidoNaConta pedido = conta.comoUsuario(() -> planos.pedir(Plano.COMPLETO));

        assertThat(pedido.tipo()).isEqualTo(PedidoDePlano.Tipo.UPGRADE);
        assertThat(pedido.valor()).isEqualTo(esperado);
        assertThat(pedido.periodoInicio()).isEqualTo(hoje);
        assertThat(pedido.periodoFim()).isEqualTo(vencimento);

        EstadoDoPlano estado = conta.comoUsuario(() -> planos.aplicarCodigo(pedido.id(),
                assinatura.codigo(pedido.id(), Plano.COMPLETO)));

        assertThat(estado.plano()).isEqualTo(Plano.COMPLETO);
        assertThat(estado.vencimento()).isEqualTo(vencimento);
        assertThat(estado.recursos()).contains(RecursoDoPlano.ESTOQUE, RecursoDoPlano.MULTIUSUARIO);
        assertThat(estado.propostas())
                .as("no vencimento, o período seguinte já custa a mensalidade do completo")
                .extracting(Proposta::tipo, Proposta::plano, Proposta::valor)
                .containsExactly(tuple(PedidoDePlano.Tipo.RENOVACAO, Plano.COMPLETO, COMPLETO));
    }

    @Test
    @DisplayName("vencido o intermediário, o upgrade espera a renovação; downgrade e plano grátis nunca têm pedido")
    void pedidosRecusados() {
        ContaCriada intermediaria = criador.criar("Loja Intermediaria", SENHA_DE_TESTE);
        criador.contratar(intermediaria.contaId(), Plano.CAIXA_SIMPLES);
        criador.vencerPlano(intermediaria.contaId(), 0);

        assertThat(intermediaria.comoUsuario(planos::consultar).propostas())
                .extracting(Proposta::tipo, Proposta::plano)
                .containsExactly(tuple(PedidoDePlano.Tipo.RENOVACAO, Plano.CAIXA_SIMPLES));
        assertThatIllegalStateException()
                .isThrownBy(() -> intermediaria.comoUsuario(() -> planos.pedir(Plano.COMPLETO)))
                .withMessageContaining("renove");

        ContaCriada completa = criador.criar("Loja Completa", SENHA_DE_TESTE);
        criador.contratar(completa.contaId(), Plano.COMPLETO);
        assertThatIllegalStateException()
                .isThrownBy(() -> completa.comoUsuario(() -> planos.pedir(Plano.CAIXA_SIMPLES)))
                .withMessageContaining("plano menor");
        assertThatIllegalStateException()
                .isThrownBy(() -> completa.comoUsuario(() -> planos.pedir(Plano.GRATIS)));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> completa.comoUsuario(() -> planos.pedir(null)));

        completa.comoUsuario(() -> assertThat(pedidos.findAll()).isEmpty());
    }

    @Test
    @DisplayName("o upgrade aplicado depois do fim do período pedido ainda vale, e a Conta renova como completo")
    void upgradeAplicadoDepoisDoPeriodo() {
        ContaCriada conta = criador.criar("Papelaria Aurora", SENHA_DE_TESTE);
        criador.contratar(conta.contaId(), Plano.CAIXA_SIMPLES);
        criador.vencerPlano(conta.contaId(), -5);
        PedidoNaConta pedido = conta.comoUsuario(() -> planos.pedir(Plano.COMPLETO));
        // O mantenedor conferiu o Pix no período, mas o código só foi aplicado semanas depois.
        criador.vencerPlano(conta.contaId(), 10);

        EstadoDoPlano estado = conta.comoUsuario(() -> planos.aplicarCodigo(pedido.id(),
                assinatura.codigo(pedido.id(), Plano.COMPLETO)));

        assertThat(estado.plano()).isEqualTo(Plano.COMPLETO);
        assertThat(estado.situacao()).isEqualTo(SituacaoDoPlano.SUSPENSO);
        assertThat(estado.propostas())
                .extracting(Proposta::tipo, Proposta::plano, Proposta::valor)
                .containsExactly(tuple(PedidoDePlano.Tipo.RENOVACAO, Plano.COMPLETO, COMPLETO));
    }

    @Test
    @DisplayName("a renovação paga o período vencido sem deslocar o ciclo e devolve os recursos suspensos")
    void renovacaoDevolveOsRecursos() {
        ContaCriada conta = criador.criar("Livraria Aurora", SENHA_DE_TESTE);
        criador.contratar(conta.contaId(), Plano.CAIXA_SIMPLES);
        criador.vencerPlano(conta.contaId(), 10);
        LocalDate vencido = hoje().minusDays(10);
        LocalDate seguinte = CicloDeVencimento.seguinte(vencido, vencido.getDayOfMonth());

        EstadoDoPlano suspenso = conta.comoUsuario(planos::consultar);
        assertThat(suspenso.situacao()).isEqualTo(SituacaoDoPlano.SUSPENSO);
        assertThat(suspenso.recursos()).isEmpty();
        assertThat(suspenso.inicioDaSuspensao()).isEqualTo(vencido.plusDays(8));

        PedidoNaConta pedido = conta.comoUsuario(() -> planos.pedir(Plano.CAIXA_SIMPLES));
        assertThat(pedido.tipo()).isEqualTo(PedidoDePlano.Tipo.RENOVACAO);
        assertThat(pedido.valor()).isEqualTo(CAIXA_SIMPLES);
        assertThat(pedido.periodoInicio()).as("o atraso não desloca o ciclo").isEqualTo(vencido);
        assertThat(pedido.periodoFim()).isEqualTo(seguinte);

        EstadoDoPlano renovado = conta.comoUsuario(() -> planos.aplicarCodigo(pedido.id(),
                assinatura.codigo(pedido.id(), Plano.CAIXA_SIMPLES)));

        assertThat(renovado.vencimento()).isEqualTo(seguinte);
        assertThat(renovado.situacao()).isEqualTo(SituacaoDoPlano.EM_DIA);
        assertThat(renovado.recursos()).containsExactly(RecursoDoPlano.RELATORIOS);
    }

    @Test
    @DisplayName("depois de vários meses sem pagar, a renovação paga só o período corrente")
    void renovacaoDepoisDeVariosMeses() {
        ContaCriada conta = criador.criar("Sorveteria Aurora", SENHA_DE_TESTE);
        criador.contratar(conta.contaId(), Plano.COMPLETO);
        criador.vencerPlano(conta.contaId(), 70);
        LocalDate hoje = hoje();

        PedidoNaConta pedido = conta.comoUsuario(() -> planos.pedir(Plano.COMPLETO));

        assertThat(pedido.periodoInicio()).isAfter(hoje.minusDays(70)).isBeforeOrEqualTo(hoje);
        assertThat(pedido.periodoFim()).isAfter(hoje);
        assertThat(pedido.valor()).isEqualTo(COMPLETO);

        EstadoDoPlano renovado = conta.comoUsuario(() -> planos.aplicarCodigo(pedido.id(),
                assinatura.codigo(pedido.id(), Plano.COMPLETO)));

        assertThat(renovado.vencimento()).isEqualTo(pedido.periodoFim());
        assertThat(renovado.situacao()).isNotEqualTo(SituacaoDoPlano.SUSPENSO);
        assertThat(renovado.recursos()).isEqualTo(EnumSet.allOf(RecursoDoPlano.class));
    }

    /**
     * Duas pessoas aplicam o mesmo código ao mesmo tempo. Uma transação prende, sem alterá-la, a
     * linha do pedido que a primeira aplicação grava; a primeira para ali, já com a linha da Conta
     * presa, e a segunda começa e para na trava da Conta, atrás da primeira. Solta a linha, a
     * primeira confirma, e a segunda encontra o pedido aplicado. Sem a trava da Conta, a segunda
     * leria o pedido ainda aberto e gravaria por cima a aplicação, com o nome dela.
     */
    @Test
    @DisplayName("duas aplicações simultâneas do mesmo código: uma aplica, e a outra espera e não faz nada")
    void aplicacoesSimultaneasAplicamUmaVez() {
        ContaCriada dono = criador.criar("Armazém da Praça", SENHA_DE_TESTE);
        UsuarioCriado socia = criador.criarAdminEm(dono.contaId(), "Sócia");
        PedidoNaConta pedido = dono.comoUsuario(() -> planos.pedir(Plano.COMPLETO));
        String codigo = assinatura.codigo(pedido.id(), Plano.COMPLETO);

        ExecutorService threads = Executors.newFixedThreadPool(2);
        List<CompletableFuture<EstadoDoPlano>> disputa;
        try {
            disputa = dono.comoUsuario(() -> transacao.execute(status -> {
                jdbc.sql("select id from pedido_de_plano where id = :id and conta_id = :conta"
                                + " for no key update")
                        .param("id", pedido.id())
                        .param("conta", dono.contaId().valor())
                        .query(UUID.class)
                        .single();
                List<CompletableFuture<EstadoDoPlano>> iniciadas = new ArrayList<>();
                for (Supplier<EstadoDoPlano> aplicacao : List.<Supplier<EstadoDoPlano>>of(
                        () -> dono.comoUsuario(() -> planos.aplicarCodigo(pedido.id(), codigo)),
                        () -> socia.comoUsuario(() -> planos.aplicarCodigo(pedido.id(), codigo)))) {
                    iniciadas.add(CompletableFuture.supplyAsync(aplicacao, threads));
                    await().atMost(ESPERA).until(() ->
                            quantasEsperam() + terminadas(iniciadas) == iniciadas.size());
                }
                assertThat(terminadas(iniciadas))
                        .as("as duas aplicações estão paradas numa trava, nenhuma terminou")
                        .isZero();
                return iniciadas;
            }));
        } finally {
            threads.shutdown();
        }

        assertThat(disputa).allSatisfy(aplicacao -> assertThat(aplicacao).succeedsWithin(ESPERA));
        assertThat(dono.comoUsuario(planos::consultar).plano()).isEqualTo(Plano.COMPLETO);
        dono.comoUsuario(() -> assertThat(pedidos.findById(pedido.id())).get()
                .extracting(PedidoDePlano::getAplicadoPor)
                .as("a aplicação que ficou é a primeira")
                .isEqualTo(dono.usuarioId()));
    }

    /** Conexões paradas numa trava, de quem for, lidas de {@code pg_locks} na hora da consulta. */
    private long quantasEsperam() {
        return jdbc.sql("select count(*) from pg_locks where not granted")
                .query(Long.class)
                .single();
    }

    private static long terminadas(List<? extends CompletableFuture<?>> operacoes) {
        return operacoes.stream().filter(CompletableFuture::isDone).count();
    }

    private static LocalDate hoje() {
        return LocalDate.now(FusoDeReferencia.DO_BALCAO);
    }
}
