package br.com.caixasimples.caixa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import br.com.caixasimples.TesteDeIntegracao;
import br.com.caixasimples.caixa.application.SessaoCaixaNaoEncontradaException;
import br.com.caixasimples.caixa.application.SessaoCaixaService;
import br.com.caixasimples.caixa.domain.MovimentoCaixa;
import br.com.caixasimples.caixa.domain.SessaoCaixa;
import br.com.caixasimples.caixa.internal.SessaoCaixaRepository;
import br.com.caixasimples.contas.CriadorDeContaDeTeste;
import br.com.caixasimples.contas.CriadorDeContaDeTeste.ContaCriada;
import br.com.caixasimples.pagamentos.FormaPagamento;
import br.com.caixasimples.pagamentos.StatusPagamento;
import br.com.caixasimples.shared.Money;
import br.com.caixasimples.shared.TenantContext;
import br.com.caixasimples.vendas.CriadorDeVendaDeTeste;
import br.com.caixasimples.vendas.VendaConcluida;
import br.com.caixasimples.vendas.VendaConcluida.Item;
import br.com.caixasimples.vendas.VendaConcluida.Parcela;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.modulith.events.CompletedEventPublications;
import org.springframework.modulith.events.EventPublication;
import org.springframework.modulith.events.core.EventPublicationRegistry;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * O caixa reagindo à venda concluída, dentro da transação de quem conclui.
 *
 * <p>Publica o evento diretamente, sem passar por {@code VendaService}, para provar só o que é do
 * caixa: quanto entra na gaveta, com que instante, e que o lançamento acontece na mesma thread e
 * na mesma transação de quem publicou, por isso cada asserção lê logo em seguida. O caminho
 * inteiro, da conclusão da venda ao movimento, está em {@code VendaServiceTest}.
 *
 * <p>O evento é publicado com a conta no contexto, como numa requisição: a transação abre a
 * sessão do Hibernate na conta de quem conclui, e o ouvinte participa dela.
 */
class VendaConcluidaListenerTest extends TesteDeIntegracao {

    private static final String SENHA_DE_TESTE = "uma senha longa de teste";

    @Autowired
    private ApplicationEventPublisher publicador;

    @Autowired
    private TransactionTemplate transacao;

    @Autowired
    private SessaoCaixaService sessoesDeCaixa;

    @Autowired
    private SessaoCaixaRepository sessoes;

    @Autowired
    private CompletedEventPublications publicacoesConcluidas;

    @Autowired
    private EventPublicationRegistry registroDePublicacoes;

    @Autowired
    private CriadorDeContaDeTeste criador;

    @Autowired
    private CriadorDeVendaDeTeste vendas;

    @AfterEach
    void limparContexto() {
        TenantContext.limpar();
    }

    @Test
    @DisplayName("entra na gaveta só o dinheiro confirmado, com o instante da conclusão")
    void entraSoODinheiroConfirmado() {
        ContaCriada conta = criador.criar("Cafeteria Aurora", SENHA_DE_TESTE);
        UUID sessaoId = abrirCaixa(conta, Money.de("50.00"));
        UUID vendaId = vendas.criarAbertaEm(conta.contaId(), sessaoId, conta.usuarioId());
        // O instante do balcão de uma venda registrada sem rede e sincronizada horas depois.
        Instant concluidaNoBalcao = Instant.now().minus(3, ChronoUnit.HOURS)
                .truncatedTo(ChronoUnit.MICROS);

        VendaConcluida evento = new VendaConcluida(conta.contaId(), vendaId, sessaoId,
                conta.usuarioId(),
                List.of(new Item(UUID.randomUUID(), new BigDecimal("2"))),
                List.of(
                        new Parcela(FormaPagamento.PIX, Money.de("20.00"),
                                StatusPagamento.CONFIRMADO),
                        new Parcela(FormaPagamento.CARTAO, Money.de("5.00"),
                                StatusPagamento.RECUSADO),
                        new Parcela(FormaPagamento.DINHEIRO, Money.de("5.00"),
                                StatusPagamento.RECUSADO),
                        new Parcela(FormaPagamento.DINHEIRO, Money.de("10.00"),
                                StatusPagamento.CONFIRMADO),
                        new Parcela(FormaPagamento.DINHEIRO, Money.de("8.93"),
                                StatusPagamento.CONFIRMADO)),
                concluidaNoBalcao);

        publicar(conta, evento);

        conta.comoUsuario(() -> {
            SessaoCaixa sessao = sessoes.findById(sessaoId).orElseThrow().paraDominio();
            // 50,00 de abertura mais 10,00 e 8,93 em dinheiro; o Pix de 20,00 e as recusadas
            // não estiveram na gaveta.
            assertThat(sessao.getValorFechamentoEsperado()).isEqualTo(Money.de("68.93"));
            assertThat(sessao.getMovimentos())
                    .extracting(MovimentoCaixa::tipo, MovimentoCaixa::valor,
                            MovimentoCaixa::vendaId, MovimentoCaixa::motivo,
                            MovimentoCaixa::criadoEm)
                    .containsExactly(tuple(TipoMovimentoCaixa.VENDA, Money.de("18.93"),
                            vendaId, null, concluidaNoBalcao));
        });
    }

    @Test
    @DisplayName("venda paga só em Pix e cartão não gera movimento: a gaveta não mexeu")
    void semDinheiroNaoHaMovimento() {
        ContaCriada conta = criador.criar("Padaria Central", SENHA_DE_TESTE);
        UUID sessaoId = abrirCaixa(conta, Money.ZERO);
        UUID vendaId = vendas.criarAbertaEm(conta.contaId(), sessaoId, conta.usuarioId());

        publicar(conta, new VendaConcluida(conta.contaId(), vendaId, sessaoId,
                conta.usuarioId(),
                List.of(new Item(UUID.randomUUID(), BigDecimal.ONE)),
                List.of(
                        new Parcela(FormaPagamento.PIX, Money.de("4.50"),
                                StatusPagamento.CONFIRMADO),
                        new Parcela(FormaPagamento.CARTAO, Money.de("4.50"),
                                StatusPagamento.CONFIRMADO)),
                Instant.now()));

        conta.comoUsuario(() -> {
            SessaoCaixa sessao = sessoes.findById(sessaoId).orElseThrow().paraDominio();
            assertThat(sessao.getMovimentos()).isEmpty();
            assertThat(sessao.getValorFechamentoEsperado()).isEqualTo(Money.ZERO);
        });
    }

    @Test
    @DisplayName("o mesmo fato publicado de novo é recusado pela raiz, e o dinheiro conta uma vez")
    void mesmaVendaNaoEntraDuasVezes() {
        ContaCriada conta = criador.criar("Mercearia da Rua", SENHA_DE_TESTE);
        UUID sessaoId = abrirCaixa(conta, Money.ZERO);
        UUID vendaId = vendas.criarAbertaEm(conta.contaId(), sessaoId, conta.usuarioId());
        VendaConcluida evento = new VendaConcluida(conta.contaId(), vendaId, sessaoId,
                conta.usuarioId(),
                List.of(new Item(UUID.randomUUID(), BigDecimal.ONE)),
                List.of(new Parcela(FormaPagamento.DINHEIRO, Money.de("30.00"),
                        StatusPagamento.CONFIRMADO)),
                Instant.now());

        publicar(conta, evento);
        // Sem registro de publicação não há reentrega; publicar de novo é defeito de quem
        // publica, e a raiz recusa alto, desfazendo a transação que tentou.
        assertThatIllegalStateException()
                .isThrownBy(() -> publicar(conta, evento))
                .withMessageContaining("nao entra de novo");

        conta.comoUsuario(() -> {
            SessaoCaixa sessao = sessoes.findById(sessaoId).orElseThrow().paraDominio();
            assertThat(sessao.getMovimentos()).hasSize(1);
            assertThat(sessao.getValorFechamentoEsperado()).isEqualTo(Money.de("30.00"));
        });
    }

    @Test
    @DisplayName("a falha do lançamento sobe para quem publicou, e nada da transação fica")
    void falhaDoLancamentoDesfazATransacaoDeQuemPublicou() {
        ContaCriada conta = criador.criar("Armazém Aurora", SENHA_DE_TESTE);
        UUID sessaoId = abrirCaixa(conta, Money.ZERO);
        UUID vendaId = vendas.criarAbertaEm(conta.contaId(), sessaoId, conta.usuarioId());
        UUID sessaoQueNaoExiste = UUID.randomUUID();

        // Na mesma transação: um suprimento que o caixa aceitaria e o fato de uma venda cuja
        // sessão não existe. O lançamento falha, e o suprimento sai junto.
        assertThatThrownBy(() -> conta.comoUsuario(() ->
                transacao.executeWithoutResult(status -> {
                    sessoesDeCaixa.registrarSuprimento(sessaoId, Money.de("7.00"), "Troco");
                    publicador.publishEvent(new VendaConcluida(conta.contaId(), vendaId,
                            sessaoQueNaoExiste, conta.usuarioId(),
                            List.of(new Item(UUID.randomUUID(), BigDecimal.ONE)),
                            List.of(new Parcela(FormaPagamento.DINHEIRO, Money.de("3.00"),
                                    StatusPagamento.CONFIRMADO)),
                            Instant.now()));
                })))
                .isInstanceOf(SessaoCaixaNaoEncontradaException.class);

        conta.comoUsuario(() -> assertThat(sessoes.findById(sessaoId).orElseThrow()
                .paraDominio().getMovimentos()).isEmpty());
    }

    @Test
    @DisplayName("o evento de outra conta é recusado, e a conta dele não recebe nada (RNF05)")
    void eventoDeOutraContaNaoLanca() {
        ContaCriada contaA = criador.criar("Loja A", SENHA_DE_TESTE);
        ContaCriada contaB = criador.criar("Loja B", SENHA_DE_TESTE);
        UUID sessaoDaContaA = abrirCaixa(contaA, Money.ZERO);
        UUID vendaDaContaA = vendas.criarAbertaEm(contaA.contaId(), sessaoDaContaA,
                contaA.usuarioId());
        VendaConcluida evento = new VendaConcluida(contaA.contaId(), vendaDaContaA,
                sessaoDaContaA, contaA.usuarioId(),
                List.of(new Item(UUID.randomUUID(), BigDecimal.ONE)),
                List.of(new Parcela(FormaPagamento.DINHEIRO, Money.de("12.00"),
                        StatusPagamento.CONFIRMADO)),
                Instant.now());

        // A transação de quem publica está na conta B: o ouvinte não troca de conta no meio
        // dela, recusa.
        assertThatIllegalStateException()
                .isThrownBy(() -> publicar(contaB, evento))
                .withMessageContaining("outra conta");

        contaA.comoUsuario(() -> assertThat(sessoes.findById(sessaoDaContaA).orElseThrow()
                .paraDominio().getValorFechamentoEsperado()).isEqualTo(Money.ZERO));
        contaB.comoUsuario(() -> assertThat(sessoes.findAll()).isEmpty());
    }

    @Test
    @DisplayName("o fato não passa pelo registro de publicação: não há entrega para acompanhar")
    void naoPassaPeloRegistroDePublicacao() {
        ContaCriada conta = criador.criar("Empório do Bairro", SENHA_DE_TESTE);
        UUID sessaoId = abrirCaixa(conta, Money.ZERO);
        UUID vendaId = vendas.criarAbertaEm(conta.contaId(), sessaoId, conta.usuarioId());
        VendaConcluida evento = new VendaConcluida(conta.contaId(), vendaId, sessaoId,
                conta.usuarioId(),
                List.of(new Item(UUID.randomUUID(), new BigDecimal("0.750"))),
                List.of(new Parcela(FormaPagamento.DINHEIRO, Money.de("10.00"),
                        StatusPagamento.CONFIRMADO)),
                Instant.now());

        publicar(conta, evento);

        assertThat(publicacoesConcluidas.findAll()).extracting(EventPublication::getEvent)
                .doesNotContain(evento);
        assertThat(registroDePublicacoes.findIncompletePublications())
                .extracting(EventPublication::getEvent)
                .doesNotContain(evento);
    }

    private UUID abrirCaixa(ContaCriada conta, Money valorAbertura) {
        return conta.comoUsuario(() -> sessoesDeCaixa.abrir(valorAbertura));
    }

    /** Como numa requisição: a conta no contexto antes de a transação abrir. */
    private void publicar(ContaCriada conta, VendaConcluida evento) {
        conta.comoUsuario(() ->
                transacao.executeWithoutResult(status -> publicador.publishEvent(evento)));
    }
}
