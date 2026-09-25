package br.com.caixasimples.caixa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import br.com.caixasimples.TesteDeIntegracao;
import br.com.caixasimples.caixa.application.SessaoCaixaService;
import br.com.caixasimples.caixa.domain.SessaoCaixa;
import br.com.caixasimples.caixa.internal.SessaoCaixaRepository;
import br.com.caixasimples.contas.CriadorDeContaDeTeste;
import br.com.caixasimples.contas.CriadorDeContaDeTeste.ContaCriada;
import br.com.caixasimples.pagamentos.FormaPagamento;
import br.com.caixasimples.shared.Money;
import br.com.caixasimples.shared.TenantContext;
import br.com.caixasimples.vendas.FiadoRecebido;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * O caixa reagindo ao recebimento de fiado, dentro da transação de quem recebe.
 *
 * <p>Publica o evento diretamente, sem passar por {@code VendaService}, para provar só o que é do
 * caixa e não depende de um recebimento gravado: Pix e cartão não mexem na gaveta, e o evento de
 * outra conta é recusado. O dinheiro entrando na sessão de quem recebeu precisa de um recebimento
 * de verdade, porque o movimento aponta para ele por chave estrangeira, e por isso está em
 * {@code FiadoServiceTest}; a disputa com outra transação pela mesma sessão, em
 * {@code CancelamentoERecebimentoSobConcorrenciaTest}.
 */
class FiadoRecebidoListenerTest extends TesteDeIntegracao {

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
    private CriadorDeContaDeTeste criador;

    @AfterEach
    void limparContexto() {
        TenantContext.limpar();
    }

    @Test
    @DisplayName("recebimento em Pix ou cartão não gera movimento: a gaveta não mexeu")
    void semDinheiroNaoHaMovimento() {
        ContaCriada conta = criador.criar("Padaria Central", SENHA_DE_TESTE);
        UUID sessaoId = conta.comoUsuario(() -> sessoesDeCaixa.abrir(Money.de("15.00")));

        publicar(conta, recebimento(conta, sessaoId, FormaPagamento.PIX));
        publicar(conta, recebimento(conta, sessaoId, FormaPagamento.CARTAO));

        conta.comoUsuario(() -> {
            SessaoCaixa sessao = sessoes.findById(sessaoId).orElseThrow().paraDominio();
            assertThat(sessao.getMovimentos()).isEmpty();
            assertThat(sessao.getValorFechamentoEsperado()).isEqualTo(Money.de("15.00"));
        });
    }

    @Test
    @DisplayName("o evento de outra conta é recusado, e a conta dele não recebe nada (RNF05)")
    void eventoDeOutraContaNaoLanca() {
        ContaCriada contaA = criador.criar("Loja A", SENHA_DE_TESTE);
        ContaCriada contaB = criador.criar("Loja B", SENHA_DE_TESTE);
        UUID sessaoDaContaA = contaA.comoUsuario(() -> sessoesDeCaixa.abrir(Money.ZERO));

        // A transação de quem publica está na conta B: o ouvinte não troca de conta no meio
        // dela, recusa antes de tocar em qualquer tabela.
        assertThatIllegalStateException()
                .isThrownBy(() -> publicar(contaB,
                        recebimento(contaA, sessaoDaContaA, FormaPagamento.DINHEIRO)))
                .withMessageContaining("outra conta");

        contaA.comoUsuario(() -> assertThat(sessoes.findById(sessaoDaContaA).orElseThrow()
                .paraDominio().getMovimentos()).isEmpty());
        contaB.comoUsuario(() -> assertThat(sessoes.findAll()).isEmpty());
    }

    /** Um recebimento de 20,00 na sessão indicada, da venda e do recebimento que forem. */
    private static FiadoRecebido recebimento(ContaCriada conta, UUID sessaoId,
            FormaPagamento forma) {
        return new FiadoRecebido(conta.contaId(), UUID.randomUUID(), UUID.randomUUID(), sessaoId,
                Money.de("20.00"), forma);
    }

    /** Como numa requisição: a conta no contexto antes de a transação abrir. */
    private void publicar(ContaCriada conta, FiadoRecebido evento) {
        conta.comoUsuario(() ->
                transacao.executeWithoutResult(status -> publicador.publishEvent(evento)));
    }
}
