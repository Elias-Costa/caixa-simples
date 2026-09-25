package br.com.caixasimples.caixa.internal;

import br.com.caixasimples.caixa.application.SessaoCaixaNaoEncontradaException;
import br.com.caixasimples.caixa.domain.SessaoCaixa;
import br.com.caixasimples.pagamentos.FormaPagamento;
import br.com.caixasimples.pagamentos.StatusPagamento;
import br.com.caixasimples.shared.Money;
import br.com.caixasimples.shared.TenantContext;
import br.com.caixasimples.vendas.VendaConcluida;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Ouve a venda concluída e lança na gaveta o dinheiro que ela trouxe.
 *
 * <p>É o efeito colateral entre módulos feito do jeito que a arquitetura pede: a venda publica um
 * fato, o caixa reage. Vendas não chama este módulo; este módulo não é chamado por ninguém.
 *
 * <h2>O que entra na gaveta</h2>
 *
 * <p><strong>Só a soma das parcelas em DINHEIRO confirmadas.</strong> O que foi pago em Pix ou
 * cartão nunca esteve na gaveta, e o esperado da sessão é o que deveria haver nela; contar o
 * resto faria a conferência do fechamento (RF15) acusar falta em toda venda que não fosse em
 * espécie. Uma venda sem parcela em dinheiro não gera movimento nenhum, porque a gaveta não
 * mexeu; a venda continua ligada à sessão pelo próprio id da sessão, gravado nela.
 *
 * <p>O movimento leva o instante da conclusão, e não o do servidor: numa venda registrada no
 * dispositivo sem rede, o dinheiro entrou na gaveta quando o balcão concluiu a venda, e é esse o
 * dia que o fluxo de caixa (RF23) precisa contar.
 *
 * <h2>Dentro da transação da conclusão</h2>
 *
 * <p>Roda na thread e na transação de quem concluiu a venda, e não depois do commit. A venda
 * concluída e o dinheiro na gaveta confirmam juntos ou não confirmam: se o lançamento falhar, a
 * conclusão falha junto, em vez de a venda aparecer concluída com o dinheiro fora do caixa. É
 * também o que deixa a sangria que vem depois, no mesmo lote enviado pelo dispositivo, encontrar
 * este dinheiro no esperado.
 *
 * <p>Sem reentrega: o fato não passa pelo registro de publicação, então chega uma vez. A raiz
 * continua recusando a mesma venda duas vezes, para qualquer chamador.
 *
 * <p>A conta é a de quem concluiu, a mesma que a transação já usa; o ouvinte confere que o evento
 * é dela, porque trocar de conta no meio de uma transação não surtiria efeito sobre a sessão do
 * Hibernate já aberta. A transação é a de quem publicou, e o {@link TransactionTemplate} só a abre
 * quando o evento é publicado fora de uma.
 */
@Component
class VendaConcluidaListener {

    private final SessaoCaixaRepository sessoes;
    private final TransactionTemplate transacao;

    VendaConcluidaListener(SessaoCaixaRepository sessoes, TransactionTemplate transacao) {
        this.sessoes = sessoes;
        this.transacao = transacao;
    }

    /**
     * Roda quando a venda é concluída, antes do commit.
     *
     * @throws SessaoCaixaNaoEncontradaException se a sessão do evento não existe nesta conta; a
     *         conclusão falha junto
     * @throws IllegalStateException se a sessão já está FECHADA, se a venda já entrou nela ou se o
     *         evento é de outra conta; a conclusão falha junto
     */
    @EventListener
    public void lancarNoCaixa(VendaConcluida evento) {
        Money emDinheiro = evento.parcelas().stream()
                .filter(parcela -> parcela.forma() == FormaPagamento.DINHEIRO)
                .filter(parcela -> parcela.status() == StatusPagamento.CONFIRMADO)
                .map(VendaConcluida.Parcela::valor)
                .reduce(Money.ZERO, Money::somar);

        if (emDinheiro.equals(Money.ZERO)) {
            // A gaveta não mexeu: nada a lançar.
            return;
        }
        if (!TenantContext.exigirAtual().equals(evento.contaId())) {
            throw new IllegalStateException(
                    "venda " + evento.vendaId() + " concluida em outra conta; o caixa nao lanca");
        }

        transacao.executeWithoutResult(status -> {
            SessaoCaixaEntity linha = sessoes.findById(evento.sessaoCaixaId())
                    .orElseThrow(() -> new SessaoCaixaNaoEncontradaException(
                            evento.sessaoCaixaId()));
            SessaoCaixa sessao = linha.paraDominio();

            sessao.registrarVenda(emDinheiro, evento.vendaId(), evento.concluidoEm());

            linha.atualizarCom(sessao);
            sessoes.save(linha);
        });
    }
}
