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
import br.com.caixasimples.vendas.VendaCancelada;
import br.com.caixasimples.vendas.VendaConcluida;
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
import org.springframework.modulith.events.core.TargetEventPublication;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * O caixa reagindo à venda cancelada, com o registro de publicação de verdade no meio: o oposto
 * de {@code VendaConcluidaListenerTest}, no mesmo molde.
 *
 * <p>Publica os eventos diretamente, sem passar por {@code VendaService}, para provar só o que é
 * do caixa: o estorno espelha o que a venda tinha trazido, uma venda sem dinheiro não gera
 * movimento, a reentrega não devolve em dobro, e o ouvinte acha a sessão da conta do evento numa
 * thread que não tem tenant nenhum. O caminho inteiro, do cancelamento pelo caso de uso ao
 * estorno, está em {@code VendaServiceTest}.
 *
 * <p>Para haver o que estornar, cada cenário publica antes a venda concluída, e espera o dinheiro
 * entrar. Todo evento é publicado dentro de uma transação, porque um listener transacional só é
 * chamado depois de um commit. O listener roda em outra thread, então cada asserção espera com
 * Awaitility.
 */
class VendaCanceladaListenerTest extends TesteDeIntegracao {

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
    @DisplayName("o estorno espelha o dinheiro que a venda trouxe; Pix, cartão e parcela recusada continuam fora")
    void estornoEspelhaODinheiroQueEntrou() {
        ContaCriada conta = criador.criar("Cafeteria Aurora", SENHA_DE_TESTE);
        UUID sessaoId = abrirCaixa(conta, Money.de("50.00"));
        UUID vendaId = vendas.criarAbertaEm(conta.contaId(), sessaoId, conta.usuarioId());
        List<VendaConcluida.Parcela> pagas = List.of(
                new VendaConcluida.Parcela(FormaPagamento.PIX, Money.de("20.00"),
                        StatusPagamento.CONFIRMADO),
                new VendaConcluida.Parcela(FormaPagamento.DINHEIRO, Money.de("5.00"),
                        StatusPagamento.RECUSADO),
                new VendaConcluida.Parcela(FormaPagamento.DINHEIRO, Money.de("10.00"),
                        StatusPagamento.CONFIRMADO),
                new VendaConcluida.Parcela(FormaPagamento.DINHEIRO, Money.de("8.93"),
                        StatusPagamento.CONFIRMADO));

        publicar(new VendaConcluida(conta.contaId(), vendaId, sessaoId, conta.usuarioId(),
                List.of(new VendaConcluida.Item(UUID.randomUUID(), new BigDecimal("2"))), pagas));
        await().atMost(ESPERA).untilAsserted(() ->
                assertThat(esperadoDe(conta, sessaoId)).isEqualTo(Money.de("68.93")));

        publicar(cancelamentoDe(conta, vendaId, sessaoId, pagas));

        await().atMost(ESPERA).untilAsserted(() ->
                conta.comoUsuario(() -> {
                    SessaoCaixa sessao = sessoes.findById(sessaoId).orElseThrow().paraDominio();
                    // Volta aos 50,00 da abertura: saíram os mesmos 18,93 que tinham entrado.
                    assertThat(sessao.getValorFechamentoEsperado()).isEqualTo(Money.de("50.00"));
                    assertThat(sessao.getMovimentos())
                            .extracting(MovimentoCaixa::tipo, MovimentoCaixa::valor,
                                    MovimentoCaixa::vendaId, MovimentoCaixa::motivo)
                            .containsExactly(
                                    tuple(TipoMovimentoCaixa.VENDA, Money.de("18.93"), vendaId,
                                            null),
                                    tuple(TipoMovimentoCaixa.ESTORNO, Money.de("18.93"),
                                            vendaId, null));
                }));
    }

    @Test
    @DisplayName("venda paga só em Pix e cartão não gera estorno: a gaveta nunca mexeu")
    void semDinheiroNaoHaEstorno() {
        ContaCriada conta = criador.criar("Padaria Central", SENHA_DE_TESTE);
        UUID sessaoId = abrirCaixa(conta, Money.de("30.00"));
        UUID vendaId = vendas.criarAbertaEm(conta.contaId(), sessaoId, conta.usuarioId());

        VendaCancelada evento = cancelamentoDe(conta, vendaId, sessaoId, List.of(
                new VendaConcluida.Parcela(FormaPagamento.PIX, Money.de("4.50"),
                        StatusPagamento.CONFIRMADO),
                new VendaConcluida.Parcela(FormaPagamento.CARTAO, Money.de("4.50"),
                        StatusPagamento.CONFIRMADO)));

        publicar(evento);

        // Só dá para afirmar que nada aconteceu depois de o listener ter terminado, e o sinal de
        // que terminou é a publicação concluída no registro.
        await().atMost(ESPERA).untilAsserted(() ->
                assertThat(publicacoesDoCaixa(evento)).hasSize(1));

        conta.comoUsuario(() -> {
            SessaoCaixa sessao = sessoes.findById(sessaoId).orElseThrow().paraDominio();
            assertThat(sessao.getMovimentos()).isEmpty();
            assertThat(sessao.getValorFechamentoEsperado()).isEqualTo(Money.de("30.00"));
        });
    }

    @Test
    @DisplayName("o mesmo cancelamento entregue duas vezes estorna uma vez só, e as duas entregas terminam")
    void reentregaNaoDuplicaOEstorno() {
        ContaCriada conta = criador.criar("Mercearia da Rua", SENHA_DE_TESTE);
        UUID sessaoId = abrirCaixa(conta, Money.ZERO);
        UUID vendaId = vendas.criarAbertaEm(conta.contaId(), sessaoId, conta.usuarioId());
        List<VendaConcluida.Parcela> pagas = List.of(new VendaConcluida.Parcela(
                FormaPagamento.DINHEIRO, Money.de("30.00"), StatusPagamento.CONFIRMADO));

        publicar(new VendaConcluida(conta.contaId(), vendaId, sessaoId, conta.usuarioId(),
                List.of(new VendaConcluida.Item(UUID.randomUUID(), BigDecimal.ONE)), pagas));
        await().atMost(ESPERA).untilAsserted(() ->
                assertThat(esperadoDe(conta, sessaoId)).isEqualTo(Money.de("30.00")));

        VendaCancelada cancelamento = cancelamentoDe(conta, vendaId, sessaoId, pagas);
        publicar(cancelamento);
        await().atMost(ESPERA).untilAsserted(() ->
                assertThat(esperadoDe(conta, sessaoId)).isEqualTo(Money.ZERO));

        // A segunda publicação do mesmo fato é o que uma reentrega do registro faz. Ela também
        // termina sem erro: fica concluída no registro em vez de presa como falha.
        publicar(cancelamento);
        await().atMost(ESPERA).untilAsserted(() ->
                assertThat(publicacoesDoCaixa(cancelamento))
                        .as("as duas entregas terminaram, nenhuma delas com erro")
                        .hasSize(2));

        conta.comoUsuario(() -> {
            SessaoCaixa sessao = sessoes.findById(sessaoId).orElseThrow().paraDominio();
            assertThat(sessao.getMovimentos())
                    .extracting(MovimentoCaixa::tipo)
                    .containsExactly(TipoMovimentoCaixa.VENDA, TipoMovimentoCaixa.ESTORNO);
            assertThat(sessao.getValorFechamentoEsperado()).isEqualTo(Money.ZERO);
        });
    }

    @Test
    @DisplayName("o listener estorna na conta do evento, e a conta B não vê o movimento (RNF05)")
    void estornaNaContaDoEventoENaoVazaParaOutra() {
        ContaCriada contaA = criador.criar("Loja A", SENHA_DE_TESTE);
        ContaCriada contaB = criador.criar("Loja B", SENHA_DE_TESTE);
        UUID sessaoDaContaA = abrirCaixa(contaA, Money.ZERO);
        UUID vendaDaContaA = vendas.criarAbertaEm(contaA.contaId(), sessaoDaContaA,
                contaA.usuarioId());
        List<VendaConcluida.Parcela> pagas = List.of(new VendaConcluida.Parcela(
                FormaPagamento.DINHEIRO, Money.de("12.00"), StatusPagamento.CONFIRMADO));

        publicar(new VendaConcluida(contaA.contaId(), vendaDaContaA, sessaoDaContaA,
                contaA.usuarioId(),
                List.of(new VendaConcluida.Item(UUID.randomUUID(), BigDecimal.ONE)), pagas));
        await().atMost(ESPERA).untilAsserted(() ->
                assertThat(esperadoDe(contaA, sessaoDaContaA)).isEqualTo(Money.de("12.00")));

        // Publicado como conta B de propósito: o listener roda em outra thread, sem tenant, e
        // tem de usar a conta que está dentro do evento, não a de quem publicou.
        contaB.comoUsuario(() ->
                publicar(cancelamentoDe(contaA, vendaDaContaA, sessaoDaContaA, pagas)));

        await().atMost(ESPERA).untilAsserted(() ->
                assertThat(esperadoDe(contaA, sessaoDaContaA)).isEqualTo(Money.ZERO));

        contaB.comoUsuario(() -> {
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

        // Sem dinheiro, para o ouvinte terminar sem tocar na sessão; e com zero à direita e
        // quantidade fracionada, porque o JSON precisa devolver a mesma escala.
        VendaCancelada evento = new VendaCancelada(conta.contaId(), vendaId, sessaoId,
                conta.usuarioId(),
                List.of(
                        new VendaCancelada.Item(UUID.randomUUID(), new BigDecimal("0.750")),
                        new VendaCancelada.Item(UUID.randomUUID(), new BigDecimal("2"))),
                List.of(
                        new VendaCancelada.Parcela(FormaPagamento.PIX, Money.de("10.00"),
                                StatusPagamento.CONFIRMADO),
                        new VendaCancelada.Parcela(FormaPagamento.CARTAO, Money.de("0.50"),
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
        return conta.comoUsuario(() ->
                sessoesDeCaixa.abrir(valorAbertura));
    }

    private Money esperadoDe(ContaCriada conta, UUID sessaoId) {
        return conta.comoUsuario(() ->
                sessoes.findById(sessaoId).orElseThrow().paraDominio()
                        .getValorFechamentoEsperado());
    }

    /** O cancelamento da venda com as mesmas parcelas com que ela foi concluída. */
    private static VendaCancelada cancelamentoDe(ContaCriada conta, UUID vendaId, UUID sessaoId,
            List<VendaConcluida.Parcela> pagas) {
        List<VendaCancelada.Parcela> parcelas = pagas.stream()
                .map(parcela -> new VendaCancelada.Parcela(parcela.forma(), parcela.valor(),
                        parcela.status()))
                .toList();
        return new VendaCancelada(conta.contaId(), vendaId, sessaoId, conta.usuarioId(),
                List.of(new VendaCancelada.Item(UUID.randomUUID(), BigDecimal.ONE)), parcelas);
    }

    private void publicar(Object evento) {
        transacao.executeWithoutResult(status -> publicador.publishEvent(evento));
    }

    /**
     * As publicações deste evento entregues ao ouvinte do cancelamento no caixa. O identificador
     * do alvo é a assinatura do método do listener, então o nome da classe basta para separar do
     * ouvinte do estoque.
     */
    private List<? extends EventPublication> publicacoesDoCaixa(VendaCancelada evento) {
        return publicacoesConcluidas.findAll().stream()
                .filter(publicacao -> publicacao.getEvent().equals(evento))
                .filter(publicacao -> publicacao instanceof TargetEventPublication alvo
                        && alvo.getTargetIdentifier().getValue()
                                .contains("VendaCanceladaListener"))
                .toList();
    }
}
