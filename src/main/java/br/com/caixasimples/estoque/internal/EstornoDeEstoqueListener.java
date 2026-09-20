package br.com.caixasimples.estoque.internal;

import br.com.caixasimples.cadastro.application.ProdutoNaoEncontradoException;
import br.com.caixasimples.cadastro.application.ProdutoService;
import br.com.caixasimples.contas.application.ContaService;
import br.com.caixasimples.shared.TenantContext;
import br.com.caixasimples.vendas.VendaCancelada;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Ouve a venda cancelada e devolve ao estoque os produtos que ela tinha levado (RF12). É o
 * oposto exato de {@link BaixaDeEstoqueListener}, e segue o mesmo molde: a venda publica, o
 * estoque reage, e vendas não conhece este módulo. O evento chega pelo registro de publicação,
 * depois do commit do cancelamento; se esta classe falhar, a publicação fica incompleta e pode
 * ser reprocessada, em vez de o estoque ficar errado em silêncio.
 *
 * <h2>Quem decide e quem executa</h2>
 *
 * <p><strong>Este módulo decide; o cadastro executa.</strong> A política é a mesma da baixa: a
 * conta ligou o controle de estoque (RF17)? Então cada item da venda cancelada vira um estorno,
 * pedido ao cadastro pela API pública dele. Com o controle desligado nada acontece, e nem se abre
 * transação. Uma conta que desligou o controle depois da venda fica com a saída sem a entrada, e
 * isso é aceito: quem desliga o controle deixou de contar o saldo, e ao religar recomeça por uma
 * contagem, como toda conta que liga.
 *
 * <p><strong>Só se devolve o que saiu.</strong> Antes de cada item, o cadastro responde se aquela
 * venda chegou a dar baixa naquele produto; uma conta que ligou o controle depois da venda não
 * tem baixa a devolver, e o item é pulado. Serviço no meio dos itens não é erro: o cadastro
 * reconhece e não devolve.
 *
 * <p><strong>A mesma venda devolve uma vez.</strong> A entrega é garantida ao menos uma vez, então
 * o evento pode chegar de novo; antes de cada item, o cadastro responde se aquela venda já foi
 * estornada naquele produto, e a reentrega vira um não fazer nada, registrado em log. O cadastro
 * recusa o estorno em dobro por conta própria também.
 *
 * <p><strong>A venda inteira numa transação só</strong>, como na baixa: ou todos os itens voltam,
 * ou nenhum, e a publicação fica incompleta para o reprocessamento.
 *
 * <h2>Por que não a anotação de listener do Modulith</h2>
 *
 * <p>Mesmo motivo dos outros ouvintes: a anotação pronta abre uma transação antes do corpo do
 * método, e o Hibernate resolve o tenant na abertura da sessão, então ela nasceria presa ao
 * sentinela sem enxergar a conta do evento. Aqui as duas anotações estão por extenso, a conta do
 * evento entra no contexto primeiro e a transação é aberta depois, à mão. Tenant primeiro,
 * transação depois.
 *
 * <p>Fica em {@code internal} porque é um adapter de entrada: nenhum outro módulo o nomeia.
 */
@Component
class EstornoDeEstoqueListener {

    private static final Logger log = LoggerFactory.getLogger(EstornoDeEstoqueListener.class);

    private final ContaService contas;
    private final ProdutoService produtos;
    private final TransactionTemplate transacao;

    EstornoDeEstoqueListener(ContaService contas, ProdutoService produtos,
            TransactionTemplate transacao) {
        this.contas = contas;
        this.produtos = produtos;
        this.transacao = transacao;
    }

    /**
     * Roda depois do commit da transação que cancelou a venda, em outra thread.
     *
     * @throws ProdutoNaoEncontradoException se um produto do evento não existe na conta do
     *         evento; a publicação fica incompleta no registro
     */
    @Async
    @TransactionalEventListener
    public void estornar(VendaCancelada evento) {
        // Tenant primeiro, transação depois. Ver o javadoc da classe.
        TenantContext.executarComo(evento.contaId(), () -> {
            if (!contas.estoqueHabilitado()) {
                // A conta não controla estoque: nada a devolver, e nem se abre transação.
                log.debug("conta {} sem controle de estoque; cancelamento da venda {} nao gera"
                        + " movimento", evento.contaId(), evento.vendaId());
                return;
            }

            transacao.executeWithoutResult(status -> {
                for (VendaCancelada.Item item : evento.itens()) {
                    if (!produtos.jaDeuBaixaPorVenda(item.produtoId(), evento.vendaId())) {
                        // A venda não tirou estoque deste produto, porque a conta ligou o
                        // controle depois dela ou porque o item é serviço: nada a devolver.
                        log.info("venda {} nao deu baixa no produto {}; nada a estornar",
                                evento.vendaId(), item.produtoId());
                        continue;
                    }
                    if (produtos.jaEstornouPorCancelamento(item.produtoId(), evento.vendaId())) {
                        // Reentrega do registro de publicação: o estoque já voltou.
                        log.info("venda {} ja estornada no produto {}; reentrega ignorada",
                                evento.vendaId(), item.produtoId());
                        continue;
                    }
                    produtos.estornarPorCancelamento(item.produtoId(), item.quantidade(),
                            evento.vendaId());
                }
            });
        });
    }
}
