package br.com.caixasimples.estoque.internal;

import br.com.caixasimples.cadastro.application.ProdutoNaoEncontradoException;
import br.com.caixasimples.cadastro.application.ProdutoService;
import br.com.caixasimples.contas.application.ContaService;
import br.com.caixasimples.shared.TenantContext;
import br.com.caixasimples.vendas.VendaCancelada;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Ouve a venda cancelada e devolve ao estoque os produtos que ela tinha levado (RF12). É o
 * oposto exato de {@link BaixaDeEstoqueListener}, e segue o mesmo molde: a venda publica, o
 * estoque reage, e vendas não conhece este módulo.
 *
 * <h2>Quem decide e quem executa</h2>
 *
 * <p><strong>Este módulo decide; o cadastro executa.</strong> A política é a mesma da baixa: a
 * conta ligou o controle de estoque (RF17)? Então cada item da venda cancelada vira um estorno,
 * pedido ao cadastro pela API pública dele. Com o controle desligado nada acontece. Uma conta que
 * desligou o controle depois da venda fica com a saída sem a entrada, e isso é aceito: quem
 * desliga o controle deixou de contar o saldo, e ao religar recomeça por uma contagem, como toda
 * conta que liga.
 *
 * <p><strong>Só se devolve o que saiu.</strong> Antes de cada item, o cadastro responde se aquela
 * venda chegou a dar baixa naquele produto; uma conta que ligou o controle depois da venda não
 * tem baixa a devolver, e o item é pulado. Serviço no meio dos itens não é erro: o cadastro
 * reconhece e não devolve.
 *
 * <h2>Dentro da transação do cancelamento</h2>
 *
 * <p>Roda na thread e na transação de quem cancelou a venda, e não depois do commit: a venda
 * cancelada e a devolução de todos os itens confirmam juntas ou não confirmam. Se outra operação
 * alterou um dos produtos entre a leitura e a gravação, outra venda, um ajuste ou uma edição, a
 * versão da raiz recusa a gravação e o cancelamento inteiro falha, para quem cancelou repetir.
 * Depois do commit, a mesma recusa deixaria a venda cancelada com o estoque fora do saldo.
 *
 * <p>Sem reentrega: o fato não passa pelo registro de publicação, então chega uma vez. O cadastro
 * continua recusando a devolução em dobro, para qualquer chamador.
 *
 * <p>A conta é a de quem cancelou, a mesma que a transação já usa, e o ouvinte confere que o
 * evento é dela. A transação é a de quem publicou; o {@link TransactionTemplate} só a abre quando o
 * evento é publicado fora de uma.
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
     * Roda quando a venda é cancelada, antes do commit.
     *
     * @throws ProdutoNaoEncontradoException se um produto do evento não existe nesta conta; o
     *         cancelamento falha junto
     * @throws IllegalStateException se a venda já foi estornada num produto ou se o evento é de
     *         outra conta; o cancelamento falha junto
     */
    @EventListener
    public void estornar(VendaCancelada evento) {
        if (!TenantContext.exigirAtual().equals(evento.contaId())) {
            throw new IllegalStateException("venda " + evento.vendaId()
                    + " cancelada em outra conta; o estoque nao devolve");
        }

        transacao.executeWithoutResult(status -> {
            if (!contas.estoqueHabilitado()) {
                // A conta não controla estoque: nada a devolver.
                log.debug("conta {} sem controle de estoque; cancelamento da venda {} nao gera"
                        + " movimento", evento.contaId(), evento.vendaId());
                return;
            }
            for (VendaCancelada.Item item : evento.itens()) {
                if (!produtos.jaDeuBaixaPorVenda(item.produtoId(), evento.vendaId())) {
                    // A venda não tirou estoque deste produto, porque a conta ligou o
                    // controle depois dela ou porque o item é serviço: nada a devolver.
                    log.info("venda {} nao deu baixa no produto {}; nada a estornar",
                            evento.vendaId(), item.produtoId());
                    continue;
                }
                produtos.estornarPorCancelamento(item.produtoId(), item.quantidade(),
                        evento.vendaId());
            }
        });
    }
}
