package br.com.caixasimples.vendas.internal;

import br.com.caixasimples.cadastro.application.ConsultaDeClienteParaVenda.ClienteNaoEncontradoParaVendaException;
import br.com.caixasimples.cadastro.application.ProdutoNaoEncontradoException;
import br.com.caixasimples.cadastro.application.ProdutoService;
import br.com.caixasimples.cadastro.application.ProdutoService.EstoqueDoProduto;
import br.com.caixasimples.pagamentos.FormaPagamento;
import br.com.caixasimples.pagamentos.application.FormaDePagamentoNaoSuportadaException;
import br.com.caixasimples.pagamentos.domain.SolicitacaoPagamento;
import br.com.caixasimples.shared.Money;
import br.com.caixasimples.sincronizacao.Aplicacao;
import br.com.caixasimples.sincronizacao.AplicadorDeOperacoes;
import br.com.caixasimples.sincronizacao.OperacaoRecebida;
import br.com.caixasimples.sincronizacao.OperacaoRecusadaException;
import br.com.caixasimples.vendas.application.VendaNaoEncontradaException;
import br.com.caixasimples.vendas.application.VendaService;
import br.com.caixasimples.vendas.domain.ItemVenda;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;
import tools.jackson.databind.ObjectMapper;

/**
 * Os gestos da Venda que o dispositivo registrou sem rede, aplicados pelos casos de uso de vendas.
 *
 * <p>Cada gesto é o caso de uso com rede de mesmo nome, com os ids que o dispositivo gerou para a
 * Venda, o item e a parcela, e com os instantes do balcão (RNF01, RNF03). As guardas são as de
 * sempre: a Venda de quem chama, a SessaoCaixa ABERTA para iniciar e concluir, desconto e FIADO só
 * do administrador, Cliente ativo para vincular. A Venda não tem revisão no servidor, então o gesto
 * volta sem versão e a versão que o dispositivo leu não é conferida; as regras da raiz recusam o
 * que não cabe.
 *
 * <h2>Revisão</h2>
 *
 * <p>O item fica com o preço que o operador viu no balcão, porque foi o que o cliente pagou; se ele
 * difere do preço vigente, o gesto é aplicado com revisão. A conclusão que deixou negativo o saldo
 * de algum produto da Venda também: é a situação de duas Vendas sem rede do último item, e o
 * administrador confere a contagem. A baixa acontece dentro da conclusão, então o saldo lido logo
 * depois já a conta. Pix não entra: a cobrança depende do provedor, com o cliente presente.
 */
@Component
class GestosDaVenda implements AplicadorDeOperacoes {

    private final VendaService vendas;
    private final VendaRepository linhas;
    private final ProdutoService produtos;
    private final ObjectMapper json;

    GestosDaVenda(VendaService vendas, VendaRepository linhas, ProdutoService produtos,
            ObjectMapper json) {
        this.vendas = vendas;
        this.linhas = linhas;
        this.produtos = produtos;
        this.json = json;
    }

    @Override
    public Set<String> tipos() {
        return Set.of("venda.iniciar", "venda.adicionarItem", "venda.removerItem",
                "venda.aplicarDesconto", "venda.vincularCliente", "venda.registrarPagamento",
                "venda.concluir");
    }

    @Override
    public Aplicacao aplicar(OperacaoRecebida operacao) {
        UUID vendaId = operacao.registroId();
        try {
            return switch (operacao.tipo()) {
                case "venda.iniciar" -> iniciar(operacao);
                case "venda.adicionarItem" -> adicionarItem(operacao);
                case "venda.removerItem" -> {
                    Remocao remocao = operacao.payloadComo(json, Remocao.class);
                    vendas.removerItem(vendaId, operacao.exigir(remocao.itemId(), "itemId"));
                    yield Aplicacao.aplicada(null);
                }
                case "venda.aplicarDesconto" -> {
                    Desconto desconto = operacao.payloadComo(json, Desconto.class);
                    vendas.aplicarDesconto(vendaId, operacao.dinheiro(desconto.valor(), "valor"));
                    yield Aplicacao.aplicada(null);
                }
                case "venda.vincularCliente" -> {
                    Vinculo vinculo = operacao.payloadComo(json, Vinculo.class);
                    vendas.vincularCliente(vendaId,
                            operacao.exigir(vinculo.clienteId(), "clienteId"));
                    yield Aplicacao.aplicada(null);
                }
                case "venda.registrarPagamento" -> registrarPagamento(operacao);
                case "venda.concluir" -> concluir(operacao);
                default -> throw new OperacaoRecusadaException(
                        "gesto de venda desconhecido: " + operacao.tipo());
            };
        } catch (VendaNaoEncontradaException | ProdutoNaoEncontradoException
                | ClienteNaoEncontradoParaVendaException
                | FormaDePagamentoNaoSuportadaException recusa) {
            throw new OperacaoRecusadaException(recusa.getMessage(), recusa);
        }
    }

    /**
     * A pergunta ao caixa atravessa a porta sem tradução, e a exceção de sessão inexistente é do
     * módulo do caixa, que este módulo não nomeia. Por isso qualquer exceção do início vira recusa
     * do gesto; falha de banco continua falha de banco, para o dispositivo repetir.
     */
    private Aplicacao iniciar(OperacaoRecebida operacao) {
        Inicio inicio = operacao.payloadComo(json, Inicio.class);
        UUID sessaoCaixaId = operacao.exigir(inicio.sessaoCaixaId(), "sessaoCaixaId");
        Instant criadoEm = operacao.exigir(inicio.criadoEm(), "criadoEm");
        try {
            vendas.iniciar(operacao.registroId(), sessaoCaixaId, criadoEm);
        } catch (DataAccessException | TransactionException falhaDeBanco) {
            throw falhaDeBanco;
        } catch (RuntimeException recusa) {
            throw new OperacaoRecusadaException(recusa.getMessage(), recusa);
        }
        return Aplicacao.aplicada(null);
    }

    private Aplicacao adicionarItem(OperacaoRecebida operacao) {
        Item item = operacao.payloadComo(json, Item.class);
        Money precoVisto = operacao.dinheiro(item.precoUnitario(), "precoUnitario");
        Money vigente = vendas.adicionarItemComPrecoVisto(operacao.registroId(),
                operacao.exigir(item.itemId(), "itemId"),
                operacao.exigir(item.produtoId(), "produtoId"),
                operacao.exigir(item.quantidade(), "quantidade"), precoVisto,
                operacao.dinheiro(item.desconto(), "desconto"), operacao.criadoEm());
        if (!vigente.equals(precoVisto)) {
            return Aplicacao.comRevisao(null, "o item foi cobrado a " + precoVisto
                    + ", e o preco vigente do produto " + item.produtoId() + " e " + vigente
                    + "; o valor cobrado foi mantido");
        }
        return Aplicacao.aplicada(null);
    }

    private Aplicacao registrarPagamento(OperacaoRecebida operacao) {
        Parcela parcela = operacao.payloadComo(json, Parcela.class);
        FormaPagamento forma = operacao.exigir(parcela.forma(), "forma");
        if (forma == FormaPagamento.PIX) {
            throw new OperacaoRecusadaException("Pix nao entra na venda registrada sem rede: a"
                    + " cobranca depende da confirmacao do provedor, com o cliente presente");
        }
        Money recebido = parcela.valorRecebido() == null
                ? null : operacao.dinheiro(parcela.valorRecebido(), "valorRecebido");
        vendas.registrarPagamento(operacao.registroId(),
                operacao.exigir(parcela.pagamentoId(), "pagamentoId"),
                new SolicitacaoPagamento(forma, operacao.dinheiro(parcela.valor(), "valor"),
                        recebido),
                operacao.criadoEm());
        return Aplicacao.aplicada(null);
    }

    private Aplicacao concluir(OperacaoRecebida operacao) {
        Conclusao conclusao = operacao.payloadComo(json, Conclusao.class);
        vendas.concluir(operacao.registroId(), operacao.exigir(conclusao.concluidoEm(),
                "concluidoEm"));

        List<String> negativos = saldosNegativos(operacao.registroId());
        if (negativos.isEmpty()) {
            return Aplicacao.aplicada(null);
        }
        return Aplicacao.comRevisao(null, "a baixa desta venda deixou o estoque negativo: "
                + String.join(", ", negativos) + "; confira a contagem e ajuste se preciso");
    }

    /**
     * Os produtos da Venda que ficaram com saldo abaixo de zero. Com o controle de estoque
     * desligado nada foi baixado, e o saldo não fica negativo; serviço nunca tem saldo.
     */
    private List<String> saldosNegativos(UUID vendaId) {
        Set<UUID> produtosDaVenda = linhas.findById(vendaId).orElseThrow().paraDominio()
                .getItens().stream()
                .map(ItemVenda::produtoId)
                .collect(Collectors.toSet());
        return produtos.saldosDe(produtosDaVenda).stream()
                .filter(saldo -> saldo.estoqueAtual().signum() < 0)
                .map(GestosDaVenda::descrever)
                .toList();
    }

    private static String descrever(EstoqueDoProduto saldo) {
        String unidade = saldo.unidade() == null ? "" : " " + saldo.unidade();
        return saldo.nome() + " com " + saldo.estoqueAtual().stripTrailingZeros().toPlainString()
                + unidade;
    }

    /** O conteúdo de {@code venda.iniciar}, como o dispositivo o grava. */
    record Inicio(UUID sessaoCaixaId, Instant criadoEm) {
    }

    /**
     * O conteúdo de {@code venda.adicionarItem}. O nome visto e a versão do produto vêm junto e
     * ficam só no registro da operação: o nome serve à tela do dispositivo, e a versão deixou de
     * decidir a revisão, porque toda baixa de estoque a muda.
     */
    record Item(UUID itemId, UUID produtoId, String nome, BigDecimal quantidade,
            BigDecimal precoUnitario, BigDecimal desconto, Long versaoProduto) {
    }

    /** O conteúdo de {@code venda.removerItem}. */
    record Remocao(UUID itemId) {
    }

    /** O conteúdo de {@code venda.aplicarDesconto}. */
    record Desconto(BigDecimal valor) {
    }

    /** O conteúdo de {@code venda.vincularCliente}. */
    record Vinculo(UUID clienteId) {
    }

    /** O conteúdo de {@code venda.registrarPagamento}; o troco é recalculado no servidor. */
    record Parcela(UUID pagamentoId, FormaPagamento forma, BigDecimal valor,
            BigDecimal valorRecebido) {
    }

    /** O conteúdo de {@code venda.concluir}. */
    record Conclusao(Instant concluidoEm) {
    }
}
