package br.com.caixasimples.caixa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.awaitility.Awaitility.await;

import br.com.caixasimples.TesteDeIntegracao;
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
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.modulith.events.CompletedEventPublications;
import org.springframework.modulith.events.EventPublication;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * O caixa reagindo à venda concluída, com o registro de publicação de verdade no meio.
 *
 * <p>Publica o evento diretamente, sem passar por {@code VendaService}, para provar só o que é do
 * caixa: quanto entra na gaveta, o que acontece numa reentrega e que o listener acha a sessão da
 * conta do evento numa thread que não tem tenant nenhum. O caminho inteiro, da conclusão da venda
 * ao movimento, está em {@code VendaServiceTest}.
 *
 * <p>Todo evento é publicado dentro de uma transação, porque um listener transacional só é
 * chamado depois de um commit; fora de transação, o evento seria descartado. O listener roda em
 * outra thread, então cada asserção espera com Awaitility em vez de ler na sequência.
 */
class VendaConcluidaListenerTest extends TesteDeIntegracao {

    private static final String SENHA_DE_TESTE = "uma senha longa de teste";
    private static final Duration ESPERA = Duration.ofSeconds(10);

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
    private CriadorDeContaDeTeste criador;

    @Autowired
    private CriadorDeVendaDeTeste vendas;

    @AfterEach
    void limparContexto() {
        TenantContext.limpar();
    }

    @Test
    @DisplayName("entra na gaveta só o dinheiro confirmado; Pix, cartão e parcela recusada ficam fora")
    void entraSoODinheiroConfirmado() {
        ContaCriada conta = criador.criar("Cafeteria Aurora", SENHA_DE_TESTE);
        UUID sessaoId = abrirCaixa(conta, Money.de("50.00"));
        UUID vendaId = vendas.criarAbertaEm(conta.contaId(), sessaoId, conta.usuarioId());

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
                                StatusPagamento.CONFIRMADO)));

        publicar(evento);

        await().atMost(ESPERA).untilAsserted(() ->
                TenantContext.executarComo(conta.contaId(), () -> {
                    SessaoCaixa sessao = sessoes.findById(sessaoId).orElseThrow().paraDominio();
                    // 50,00 de abertura mais 10,00 e 8,93 em dinheiro; o Pix de 20,00 e as
                    // recusadas não estiveram na gaveta.
                    assertThat(sessao.getValorFechamentoEsperado()).isEqualTo(Money.de("68.93"));
                    assertThat(sessao.getMovimentos())
                            .extracting(MovimentoCaixa::tipo, MovimentoCaixa::valor,
                                    MovimentoCaixa::vendaId, MovimentoCaixa::motivo)
                            .containsExactly(tuple(TipoMovimentoCaixa.VENDA, Money.de("18.93"),
                                    vendaId, null));
                }));
    }

    @Test
    @DisplayName("venda paga só em Pix e cartão não gera movimento: a gaveta não mexeu")
    void semDinheiroNaoHaMovimento() {
        ContaCriada conta = criador.criar("Padaria Central", SENHA_DE_TESTE);
        UUID sessaoId = abrirCaixa(conta, Money.ZERO);
        UUID vendaId = vendas.criarAbertaEm(conta.contaId(), sessaoId, conta.usuarioId());

        VendaConcluida evento = new VendaConcluida(conta.contaId(), vendaId, sessaoId,
                conta.usuarioId(),
                List.of(new Item(UUID.randomUUID(), BigDecimal.ONE)),
                List.of(
                        new Parcela(FormaPagamento.PIX, Money.de("4.50"),
                                StatusPagamento.CONFIRMADO),
                        new Parcela(FormaPagamento.CARTAO, Money.de("4.50"),
                                StatusPagamento.CONFIRMADO)));

        publicar(evento);

        // Só dá para afirmar que nada aconteceu depois de o listener ter terminado, e o sinal de
        // que terminou é a publicação concluída no registro.
        await().atMost(ESPERA).untilAsserted(() ->
                assertThat(publicacoesConcluidas.findAll())
                        .extracting(EventPublication::getEvent)
                        .contains(evento));

        TenantContext.executarComo(conta.contaId(), () -> {
            SessaoCaixa sessao = sessoes.findById(sessaoId).orElseThrow().paraDominio();
            assertThat(sessao.getMovimentos()).isEmpty();
            assertThat(sessao.getValorFechamentoEsperado()).isEqualTo(Money.ZERO);
        });
    }

    @Test
    @DisplayName("o mesmo evento entregue duas vezes lança uma vez só")
    void reentregaNaoDuplicaOMovimento() {
        ContaCriada conta = criador.criar("Mercearia da Rua", SENHA_DE_TESTE);
        UUID sessaoId = abrirCaixa(conta, Money.ZERO);
        UUID vendaId = vendas.criarAbertaEm(conta.contaId(), sessaoId, conta.usuarioId());

        VendaConcluida evento = new VendaConcluida(conta.contaId(), vendaId, sessaoId,
                conta.usuarioId(),
                List.of(new Item(UUID.randomUUID(), BigDecimal.ONE)),
                List.of(new Parcela(FormaPagamento.DINHEIRO, Money.de("30.00"),
                        StatusPagamento.CONFIRMADO)));

        publicar(evento);
        await().atMost(ESPERA).untilAsserted(() ->
                TenantContext.executarComo(conta.contaId(), () ->
                        assertThat(sessoes.findById(sessaoId).orElseThrow().paraDominio()
                                .getMovimentos()).hasSize(1)));

        // A segunda publicação do mesmo fato, depois de a primeira ter sido lançada, é o que uma
        // reentrega do registro de publicação faz. Ela também termina sem erro: fica concluída no
        // registro em vez de presa como falha.
        publicar(evento);
        await().atMost(ESPERA).untilAsserted(() ->
                assertThat(publicacoesConcluidas.findAll())
                        .filteredOn(publicacao -> publicacao.getEvent().equals(evento))
                        .as("as duas entregas terminaram, nenhuma delas com erro")
                        .hasSize(2));

        TenantContext.executarComo(conta.contaId(), () -> {
            SessaoCaixa sessao = sessoes.findById(sessaoId).orElseThrow().paraDominio();
            assertThat(sessao.getMovimentos()).hasSize(1);
            assertThat(sessao.getValorFechamentoEsperado()).isEqualTo(Money.de("30.00"));
        });
    }

    @Test
    @DisplayName("o listener lança na conta do evento, e a conta B não vê o movimento (RNF05)")
    void lancaNaContaDoEventoENaoVazaParaOutra() {
        ContaCriada contaA = criador.criar("Loja A", SENHA_DE_TESTE);
        ContaCriada contaB = criador.criar("Loja B", SENHA_DE_TESTE);
        UUID sessaoDaContaA = abrirCaixa(contaA, Money.ZERO);
        UUID vendaDaContaA = vendas.criarAbertaEm(contaA.contaId(), sessaoDaContaA,
                contaA.usuarioId());

        VendaConcluida evento = new VendaConcluida(contaA.contaId(), vendaDaContaA,
                sessaoDaContaA, contaA.usuarioId(),
                List.of(new Item(UUID.randomUUID(), BigDecimal.ONE)),
                List.of(new Parcela(FormaPagamento.DINHEIRO, Money.de("12.00"),
                        StatusPagamento.CONFIRMADO)));

        // Publicado como conta B de propósito: o listener roda em outra thread, sem tenant, e
        // tem de usar a conta que está dentro do evento, não a de quem publicou.
        TenantContext.executarComo(contaB.contaId(), () -> publicar(evento));

        await().atMost(ESPERA).untilAsserted(() ->
                TenantContext.executarComo(contaA.contaId(), () ->
                        assertThat(sessoes.findById(sessaoDaContaA).orElseThrow().paraDominio()
                                .getValorFechamentoEsperado())
                                .isEqualTo(Money.de("12.00"))));

        TenantContext.executarComo(contaB.contaId(), () -> {
            assertThat(sessoes.findById(sessaoDaContaA)).isEmpty();
            assertThat(sessoes.findAll()).isEmpty();
        });
    }

    @Test
    @DisplayName("a publicação atravessa o outbox: gravada, concluída e remontada do JSON igual ao original")
    void publicacaoVaiEVoltaDoRegistro() {
        ContaCriada conta = criador.criar("Empório do Bairro", SENHA_DE_TESTE);
        UUID sessaoId = abrirCaixa(conta, Money.ZERO);
        UUID vendaId = vendas.criarAbertaEm(conta.contaId(), sessaoId, conta.usuarioId());

        // Valores com zero à direita e quantidade fracionada de propósito: o JSON precisa
        // devolver a mesma escala, senão Money e o record não remontam iguais numa reentrega.
        VendaConcluida evento = new VendaConcluida(conta.contaId(), vendaId, sessaoId,
                conta.usuarioId(),
                List.of(
                        new Item(UUID.randomUUID(), new BigDecimal("0.750")),
                        new Item(UUID.randomUUID(), new BigDecimal("2"))),
                List.of(
                        new Parcela(FormaPagamento.DINHEIRO, Money.de("10.00"),
                                StatusPagamento.CONFIRMADO),
                        new Parcela(FormaPagamento.PIX, Money.de("0.50"),
                                StatusPagamento.PENDENTE)));

        publicar(evento);

        // getEvent() desserializa o que está gravado em serialized_event, então a igualdade prova
        // a ida e a volta pelo JSON, não só a gravação.
        await().atMost(ESPERA).untilAsserted(() ->
                assertThat(publicacoesConcluidas.findAll())
                        .filteredOn(EventPublication::isCompleted)
                        .extracting(EventPublication::getEvent)
                        .contains(evento));
    }

    private UUID abrirCaixa(ContaCriada conta, Money valorAbertura) {
        return TenantContext.executarComo(conta.contaId(), () ->
                sessoesDeCaixa.abrir(conta.usuarioId(), valorAbertura));
    }

    private void publicar(VendaConcluida evento) {
        transacao.executeWithoutResult(status -> publicador.publishEvent(evento));
    }
}
