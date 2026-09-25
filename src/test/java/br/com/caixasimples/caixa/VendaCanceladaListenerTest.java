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
import br.com.caixasimples.vendas.VendaCancelada;
import br.com.caixasimples.vendas.VendaConcluida;
import java.math.BigDecimal;
import java.time.Instant;
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
 * O caixa reagindo à venda cancelada, dentro da transação de quem cancela: o oposto de
 * {@code VendaConcluidaListenerTest}, no mesmo molde.
 *
 * <p>Publica os eventos diretamente, sem passar por {@code VendaService}, para provar só o que é
 * do caixa: o estorno espelha o que a venda tinha trazido, uma venda sem dinheiro não gera
 * movimento, o mesmo cancelamento não estorna duas vezes, a falha do estorno desfaz a transação de
 * quem publicou, e o evento de outra conta é recusado. O caminho inteiro, do cancelamento pelo caso
 * de uso ao estorno, está em {@code VendaServiceTest}; a disputa com outra transação pela mesma
 * sessão, em {@code CancelamentoERecebimentoSobConcorrenciaTest}.
 *
 * <p>Para haver o que estornar, cada cenário publica antes a venda concluída. Os eventos são
 * publicados com a conta no contexto, como numa requisição, e o estorno acontece antes de a
 * transação de quem publica terminar, então cada asserção lê logo em seguida.
 */
class VendaCanceladaListenerTest extends TesteDeIntegracao {

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

        publicar(conta, conclusaoDe(conta, vendaId, sessaoId, pagas));
        assertThat(esperadoDe(conta, sessaoId)).isEqualTo(Money.de("68.93"));

        publicar(conta, cancelamentoDe(conta, vendaId, sessaoId, pagas));

        conta.comoUsuario(() -> {
            SessaoCaixa sessao = sessoes.findById(sessaoId).orElseThrow().paraDominio();
            // Volta aos 50,00 da abertura: saíram os mesmos 18,93 que tinham entrado.
            assertThat(sessao.getValorFechamentoEsperado()).isEqualTo(Money.de("50.00"));
            assertThat(sessao.getMovimentos())
                    .extracting(MovimentoCaixa::tipo, MovimentoCaixa::valor,
                            MovimentoCaixa::vendaId, MovimentoCaixa::motivo)
                    .containsExactly(
                            tuple(TipoMovimentoCaixa.VENDA, Money.de("18.93"), vendaId, null),
                            tuple(TipoMovimentoCaixa.ESTORNO, Money.de("18.93"), vendaId, null));
        });
    }

    @Test
    @DisplayName("venda paga só em Pix e cartão não gera estorno: a gaveta nunca mexeu")
    void semDinheiroNaoHaEstorno() {
        ContaCriada conta = criador.criar("Padaria Central", SENHA_DE_TESTE);
        UUID sessaoId = abrirCaixa(conta, Money.de("30.00"));
        UUID vendaId = vendas.criarAbertaEm(conta.contaId(), sessaoId, conta.usuarioId());

        publicar(conta, cancelamentoDe(conta, vendaId, sessaoId, List.of(
                new VendaConcluida.Parcela(FormaPagamento.PIX, Money.de("4.50"),
                        StatusPagamento.CONFIRMADO),
                new VendaConcluida.Parcela(FormaPagamento.CARTAO, Money.de("4.50"),
                        StatusPagamento.CONFIRMADO))));

        conta.comoUsuario(() -> {
            SessaoCaixa sessao = sessoes.findById(sessaoId).orElseThrow().paraDominio();
            assertThat(sessao.getMovimentos()).isEmpty();
            assertThat(sessao.getValorFechamentoEsperado()).isEqualTo(Money.de("30.00"));
        });
    }

    @Test
    @DisplayName("o mesmo cancelamento publicado de novo é recusado pela raiz, e o dinheiro sai uma vez")
    void mesmoCancelamentoNaoEstornaDuasVezes() {
        ContaCriada conta = criador.criar("Mercearia da Rua", SENHA_DE_TESTE);
        UUID sessaoId = abrirCaixa(conta, Money.ZERO);
        UUID vendaId = vendas.criarAbertaEm(conta.contaId(), sessaoId, conta.usuarioId());
        List<VendaConcluida.Parcela> pagas = List.of(new VendaConcluida.Parcela(
                FormaPagamento.DINHEIRO, Money.de("30.00"), StatusPagamento.CONFIRMADO));

        publicar(conta, conclusaoDe(conta, vendaId, sessaoId, pagas));
        VendaCancelada cancelamento = cancelamentoDe(conta, vendaId, sessaoId, pagas);
        publicar(conta, cancelamento);
        assertThat(esperadoDe(conta, sessaoId)).isEqualTo(Money.ZERO);

        // Sem registro de publicação não há reentrega; publicar de novo é defeito de quem
        // publica, e a raiz recusa alto, desfazendo a transação que tentou.
        assertThatIllegalStateException()
                .isThrownBy(() -> publicar(conta, cancelamento))
                .withMessageContaining("nao sai de novo");

        conta.comoUsuario(() -> {
            SessaoCaixa sessao = sessoes.findById(sessaoId).orElseThrow().paraDominio();
            assertThat(sessao.getMovimentos())
                    .extracting(MovimentoCaixa::tipo)
                    .containsExactly(TipoMovimentoCaixa.VENDA, TipoMovimentoCaixa.ESTORNO);
            assertThat(sessao.getValorFechamentoEsperado()).isEqualTo(Money.ZERO);
        });
    }

    @Test
    @DisplayName("a falha do estorno sobe para quem publicou, e nada da transação fica")
    void falhaDoEstornoDesfazATransacaoDeQuemPublicou() {
        ContaCriada conta = criador.criar("Armazém Aurora", SENHA_DE_TESTE);
        UUID sessaoId = abrirCaixa(conta, Money.ZERO);
        UUID vendaId = vendas.criarAbertaEm(conta.contaId(), sessaoId, conta.usuarioId());
        UUID sessaoQueNaoExiste = UUID.randomUUID();

        // Na mesma transação: um suprimento que o caixa aceitaria e o cancelamento de uma venda
        // cuja sessão não existe. O estorno falha, e o suprimento sai junto.
        assertThatThrownBy(() -> conta.comoUsuario(() ->
                transacao.executeWithoutResult(status -> {
                    sessoesDeCaixa.registrarSuprimento(sessaoId, Money.de("7.00"), "Troco");
                    publicador.publishEvent(cancelamentoDe(conta, vendaId, sessaoQueNaoExiste,
                            List.of(new VendaConcluida.Parcela(FormaPagamento.DINHEIRO,
                                    Money.de("3.00"), StatusPagamento.CONFIRMADO))));
                })))
                .isInstanceOf(SessaoCaixaNaoEncontradaException.class);

        conta.comoUsuario(() -> assertThat(sessoes.findById(sessaoId).orElseThrow()
                .paraDominio().getMovimentos()).isEmpty());
    }

    @Test
    @DisplayName("o evento de outra conta é recusado, e a conta dele não perde nada (RNF05)")
    void eventoDeOutraContaNaoEstorna() {
        ContaCriada contaA = criador.criar("Loja A", SENHA_DE_TESTE);
        ContaCriada contaB = criador.criar("Loja B", SENHA_DE_TESTE);
        UUID sessaoDaContaA = abrirCaixa(contaA, Money.ZERO);
        UUID vendaDaContaA = vendas.criarAbertaEm(contaA.contaId(), sessaoDaContaA,
                contaA.usuarioId());
        List<VendaConcluida.Parcela> pagas = List.of(new VendaConcluida.Parcela(
                FormaPagamento.DINHEIRO, Money.de("12.00"), StatusPagamento.CONFIRMADO));
        publicar(contaA, conclusaoDe(contaA, vendaDaContaA, sessaoDaContaA, pagas));

        // A transação de quem publica está na conta B: o ouvinte não troca de conta no meio
        // dela, recusa.
        assertThatIllegalStateException()
                .isThrownBy(() -> publicar(contaB,
                        cancelamentoDe(contaA, vendaDaContaA, sessaoDaContaA, pagas)))
                .withMessageContaining("outra conta");

        assertThat(esperadoDe(contaA, sessaoDaContaA)).isEqualTo(Money.de("12.00"));
        contaB.comoUsuario(() -> assertThat(sessoes.findAll()).isEmpty());
    }

    @Test
    @DisplayName("o cancelamento não passa pelo registro de publicação: não há entrega para acompanhar")
    void naoPassaPeloRegistroDePublicacao() {
        ContaCriada conta = criador.criar("Empório do Bairro", SENHA_DE_TESTE);
        UUID sessaoId = abrirCaixa(conta, Money.ZERO);
        UUID vendaId = vendas.criarAbertaEm(conta.contaId(), sessaoId, conta.usuarioId());
        List<VendaConcluida.Parcela> pagas = List.of(new VendaConcluida.Parcela(
                FormaPagamento.DINHEIRO, Money.de("10.00"), StatusPagamento.CONFIRMADO));
        publicar(conta, conclusaoDe(conta, vendaId, sessaoId, pagas));
        VendaCancelada evento = cancelamentoDe(conta, vendaId, sessaoId, pagas);

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

    private Money esperadoDe(ContaCriada conta, UUID sessaoId) {
        return conta.comoUsuario(() ->
                sessoes.findById(sessaoId).orElseThrow().paraDominio()
                        .getValorFechamentoEsperado());
    }

    private static VendaConcluida conclusaoDe(ContaCriada conta, UUID vendaId, UUID sessaoId,
            List<VendaConcluida.Parcela> pagas) {
        return new VendaConcluida(conta.contaId(), vendaId, sessaoId, conta.usuarioId(),
                List.of(new VendaConcluida.Item(UUID.randomUUID(), BigDecimal.ONE)), pagas,
                Instant.now());
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

    /** Como numa requisição: a conta no contexto antes de a transação abrir. */
    private void publicar(ContaCriada conta, Object evento) {
        conta.comoUsuario(() ->
                transacao.executeWithoutResult(status -> publicador.publishEvent(evento)));
    }
}
