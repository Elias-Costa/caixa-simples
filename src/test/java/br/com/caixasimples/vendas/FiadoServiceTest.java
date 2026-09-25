package br.com.caixasimples.vendas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import br.com.caixasimples.TesteDeIntegracao;
import br.com.caixasimples.cadastro.TipoProduto;
import br.com.caixasimples.cadastro.application.ProdutoService;
import br.com.caixasimples.cadastro.application.ProdutoService.DadosDoProduto;
import br.com.caixasimples.cadastro.internal.ClienteService;
import br.com.caixasimples.cadastro.internal.ClienteService.DadosDoCliente;
import br.com.caixasimples.cadastro.internal.ProdutoRepository;
import br.com.caixasimples.caixa.TipoMovimentoCaixa;
import br.com.caixasimples.caixa.application.SessaoCaixaService;
import br.com.caixasimples.contas.CriadorDeContaDeTeste;
import br.com.caixasimples.contas.CriadorDeContaDeTeste.ContaCriada;
import br.com.caixasimples.contas.CriadorDeContaDeTeste.UsuarioCriado;
import br.com.caixasimples.pagamentos.FormaPagamento;
import br.com.caixasimples.pagamentos.StatusPagamento;
import br.com.caixasimples.pagamentos.domain.SolicitacaoPagamento;
import br.com.caixasimples.relatorios.application.FaturamentoService;
import br.com.caixasimples.relatorios.application.FaturamentoService.Filtros;
import br.com.caixasimples.shared.AcessoNegadoException;
import br.com.caixasimples.shared.Money;
import br.com.caixasimples.shared.FusoDeReferencia;
import br.com.caixasimples.shared.TenantContext;
import br.com.caixasimples.vendas.application.VendaNaoEncontradaException;
import br.com.caixasimples.vendas.application.VendaService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class FiadoServiceTest extends TesteDeIntegracao {

    @Autowired private CriadorDeContaDeTeste criador;
    @Autowired private ProdutoService produtos;
    @Autowired private ProdutoRepository linhasDeProduto;
    @Autowired private ClienteService clientes;
    @Autowired private SessaoCaixaService caixas;
    @Autowired private VendaService vendas;
    @Autowired private FaturamentoService faturamento;

    @AfterEach void limparContexto() { TenantContext.limpar(); }

    private UUID produto(ContaCriada conta) {
        return conta.comoUsuario(() -> produtos.cadastrar(TipoProduto.PRODUTO,
                new DadosDoProduto("Café", Money.de("20.00"), null, null, "un", null)));
    }

    private UUID vendaFiada(ContaCriada conta, UUID sessaoId, UUID clienteId, UUID produtoId) {
        return conta.comoUsuario(() -> {
            UUID vendaId = vendas.iniciar(sessaoId);
            vendas.adicionarItem(vendaId, produtoId, BigDecimal.ONE, Money.ZERO);
            vendas.vincularCliente(vendaId, clienteId);
            vendas.registrarPagamento(vendaId,
                    SolicitacaoPagamento.de(FormaPagamento.FIADO, Money.de("20.00")));
            vendas.concluir(vendaId);
            return vendaId;
        });
    }

    @Test
    void operadorRecebeEmSuaSessaoSemVenderFiadoEIsolamentoVale() {
        ContaCriada conta = criador.criar("Fiado A", "senha longa de teste");
        criador.habilitarEstoque(conta.contaId());
        ContaCriada outra = criador.criar("Fiado B", "senha longa de teste");
        UsuarioCriado operador = criador.criarOperadorEm(conta.contaId(), "Atendente");
        UUID sessaoDaVenda = conta.comoUsuario(() -> caixas.abrir(Money.ZERO));
        UUID sessaoDoRecebimento = operador.comoUsuario(() -> caixas.abrir(Money.ZERO));
        outra.comoUsuario(() -> caixas.abrir(Money.ZERO));
        UUID clienteId = conta.comoUsuario(() -> clientes.cadastrar(new DadosDoCliente("Lia", null)));
        UUID produtoId = produto(conta);
        UUID vendaId = vendaFiada(conta, sessaoDaVenda, clienteId, produtoId);
        assertThat(conta.comoUsuario(() -> linhasDeProduto.findById(produtoId)
                .orElseThrow().paraDominio().getEstoqueAtual()))
                .isEqualByComparingTo("-1");
        LocalDate hoje = LocalDate.now(FusoDeReferencia.DO_BALCAO);

        assertThat(conta.comoUsuario(() -> faturamento.doDia(hoje).total()))
                .isEqualTo(Money.de("20.00"));
        assertThat(conta.comoUsuario(() -> faturamento.doDia(hoje,
                Filtros.porForma(FormaPagamento.FIADO)).total()))
                .isEqualTo(Money.de("20.00"));

        assertThat(conta.comoUsuario(() -> vendas.saldoDevedorDoCliente(clienteId)))
                .isEqualTo(Money.de("20.00"));
        assertThat(conta.comoUsuario(() -> caixas.consultar(sessaoDaVenda)
                .valorFechamentoEsperado())).isEqualTo(Money.ZERO);
        assertThatExceptionOfType(AcessoNegadoException.class).isThrownBy(() ->
                operador.comoUsuario(() -> vendas.registrarPagamento(vendaId,
                        SolicitacaoPagamento.de(FormaPagamento.FIADO, Money.de("1.00")))));

        var primeiro = operador.comoUsuario(() ->
                vendas.receber(vendaId, Money.de("7.00"), FormaPagamento.DINHEIRO));
        assertThat(primeiro.saldoDevedor()).isEqualTo(Money.de("13.00"));
        assertThat(operador.comoUsuario(() -> caixas.consultar(sessaoDoRecebimento)
                .valorFechamentoEsperado())).isEqualTo(Money.de("7.00"));
        assertThat(operador.comoUsuario(() -> caixas.consultarExtrato(sessaoDoRecebimento)
                .movimentos())).anySatisfy(movimento -> {
                    assertThat(movimento.tipo()).isEqualTo(TipoMovimentoCaixa.RECEBIMENTO);
                    assertThat(movimento.recebimentoId()).isEqualTo(primeiro.id());
                });
        assertThat(conta.comoUsuario(() -> vendas.comprovanteDeRecebimento(vendaId, primeiro.id())
                .saldoApos())).isEqualTo(Money.de("13.00"));

        var segundo = operador.comoUsuario(() ->
                vendas.receber(vendaId, Money.de("13.00"), FormaPagamento.PIX));
        assertThat(segundo.saldoDevedor()).isEqualTo(Money.ZERO);
        assertThat(conta.comoUsuario(() -> faturamento.doDia(hoje,
                Filtros.porForma(FormaPagamento.FIADO)).total()))
                .isEqualTo(Money.de("20.00"));
        assertThat(conta.comoUsuario(() -> vendas.consultar(vendaId).parcelas().getFirst().status()))
                .isEqualTo(StatusPagamento.CONFIRMADO);
        assertThat(operador.comoUsuario(() -> caixas.consultar(sessaoDoRecebimento)
                .valorFechamentoEsperado())).isEqualTo(Money.de("7.00"));

        assertThat(outra.comoUsuario(() -> vendas.dividasEmAberto())).isEmpty();
        assertThatExceptionOfType(VendaNaoEncontradaException.class).isThrownBy(() ->
                outra.comoUsuario(() -> vendas.receber(vendaId, Money.de("1.00"),
                        FormaPagamento.PIX)));
        conta.comoUsuario(() -> vendas.cancelar(vendaId));
        assertThat(conta.comoUsuario(() -> caixas.consultar(sessaoDaVenda)
                .valorFechamentoEsperado())).isEqualTo(Money.de("-7.00"));
        assertThat(operador.comoUsuario(() -> caixas.consultar(sessaoDoRecebimento)
                .valorFechamentoEsperado())).isEqualTo(Money.de("7.00"));
        assertThat(conta.comoUsuario(() -> faturamento.doDia(hoje).total()))
                .isEqualTo(Money.ZERO);
        assertThat(conta.comoUsuario(() -> linhasDeProduto.findById(produtoId)
                .orElseThrow().paraDominio().getEstoqueAtual()))
                .isEqualByComparingTo("0");
    }

    @Test
    void clienteInativoNaoEntraEmVendaNovaMasDividaAntigaPermiteRecebimentoECancelamento() {
        ContaCriada conta = criador.criar("Fiado antigo", "senha longa de teste");
        UUID sessaoId = conta.comoUsuario(() -> caixas.abrir(Money.ZERO));
        UUID clienteId = conta.comoUsuario(() -> clientes.cadastrar(new DadosDoCliente("Rui", null)));
        UUID produtoId = produto(conta);
        UUID vendaId = vendaFiada(conta, sessaoId, clienteId, produtoId);
        conta.comoUsuario(() -> clientes.inativar(clienteId));

        UUID novaVenda = conta.comoUsuario(() -> {
            UUID id = vendas.iniciar(sessaoId);
            vendas.adicionarItem(id, produtoId, BigDecimal.ONE, Money.ZERO);
            return id;
        });
        assertThatIllegalStateException().isThrownBy(() ->
                conta.comoUsuario(() -> vendas.vincularCliente(novaVenda, clienteId)));
        conta.comoUsuario(() -> vendas.receber(vendaId, Money.de("5.00"), FormaPagamento.CARTAO));
        assertThat(conta.comoUsuario(() -> vendas.saldoDevedorDoCliente(clienteId)))
                .isEqualTo(Money.de("15.00"));
        conta.comoUsuario(() -> vendas.cancelar(vendaId));
        assertThat(conta.comoUsuario(() -> vendas.saldoDevedorDoCliente(clienteId)))
                .isEqualTo(Money.ZERO);
    }
}
