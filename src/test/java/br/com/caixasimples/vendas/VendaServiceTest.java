package br.com.caixasimples.vendas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.tuple;

import br.com.caixasimples.TesteDeIntegracao;
import br.com.caixasimples.cadastro.TipoProduto;
import br.com.caixasimples.cadastro.application.ProdutoNaoEncontradoException;
import br.com.caixasimples.cadastro.application.ProdutoService;
import br.com.caixasimples.cadastro.application.ProdutoService.DadosDoProduto;
import br.com.caixasimples.caixa.application.SessaoCaixaNaoEncontradaException;
import br.com.caixasimples.caixa.application.SessaoCaixaService;
import br.com.caixasimples.contas.CriadorDeContaDeTeste;
import br.com.caixasimples.contas.CriadorDeContaDeTeste.ContaCriada;
import br.com.caixasimples.pagamentos.FormaPagamento;
import br.com.caixasimples.pagamentos.StatusPagamento;
import br.com.caixasimples.pagamentos.domain.SolicitacaoPagamento;
import br.com.caixasimples.shared.Money;
import br.com.caixasimples.shared.TenantContext;
import br.com.caixasimples.vendas.application.VendaNaoEncontradaException;
import br.com.caixasimples.vendas.application.VendaService;
import br.com.caixasimples.vendas.domain.ItemVenda;
import br.com.caixasimples.vendas.domain.Pagamento;
import br.com.caixasimples.vendas.domain.Venda;
import br.com.caixasimples.vendas.internal.VendaRepository;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * A venda contra o banco de verdade: iniciar a comanda, lançar e remover item (RF07), desconto
 * sobre o total (RF08), a cópia do preço do produto (RF06), o pagamento dividido entre formas com
 * o Strategy de verdade (RF09, RF10) e a conclusão.
 *
 * <p>Existe separado de {@code VendaTest} porque prova outra coisa: lá a conta do agregado está
 * certa <em>em memória</em>; aqui ela <strong>atravessa o banco</strong>, que é o único jeito de
 * exercitar o {@code atualizarCom} da entidade, inclusive a remoção de linha por
 * {@code orphanRemoval} e o acréscimo das parcelas, as três perguntas feitas a outros módulos e o
 * isolamento entre contas em cada uma delas.
 *
 * <p>Fica no pacote {@code vendas} e enxerga só o que um controller enxergaria: o serviço, o
 * domínio e a raiz do agregado. Os casos de uso de {@code caixa} e {@code cadastro} entram no
 * papel que um controller teria, para preparar o cenário.
 */
class VendaServiceTest extends TesteDeIntegracao {

    private static final String SENHA_DE_TESTE = "uma senha longa de teste";

    @Autowired
    private VendaService vendaService;

    @Autowired
    private VendaRepository vendas;

    @Autowired
    private SessaoCaixaService caixas;

    @Autowired
    private ProdutoService produtos;

    @Autowired
    private CriadorDeContaDeTeste criador;

    @AfterEach
    void limparContexto() {
        TenantContext.limpar();
    }

    @Test
    @DisplayName("iniciar grava uma venda ABERTA, vazia e sem cliente, na sessão informada (RF07)")
    void iniciarGravaVendaAbertaEVazia() {
        ContaCriada conta = criador.criar("Cafeteria Aurora", SENHA_DE_TESTE);
        UUID sessaoId = abrirCaixa(conta);

        UUID vendaId = TenantContext.executarComo(conta.contaId(), () ->
                vendaService.iniciar(sessaoId, conta.usuarioId()));

        TenantContext.executarComo(conta.contaId(), () -> {
            Venda gravada = vendas.findById(vendaId).orElseThrow().paraDominio();

            assertThat(gravada.getSessaoCaixaId()).isEqualTo(sessaoId);
            assertThat(gravada.getUsuarioId()).isEqualTo(conta.usuarioId());
            assertThat(gravada.getClienteId()).isNull();
            assertThat(gravada.getStatus()).isEqualTo(StatusVenda.ABERTA);
            assertThat(gravada.getValorTotal()).isEqualTo(Money.ZERO);
            assertThat(gravada.getValorDesconto()).isEqualTo(Money.ZERO);
            assertThat(gravada.getItens()).isEmpty();
            assertThat(gravada.getPagamentos()).isEmpty();
        });
    }

    @Test
    @DisplayName("iniciar recusa sessão de caixa FECHADA")
    void iniciarRecusaSessaoFechada() {
        ContaCriada conta = criador.criar("Padaria Central", SENHA_DE_TESTE);
        UUID sessaoId = abrirCaixa(conta);
        TenantContext.executarComo(conta.contaId(), () -> caixas.fechar(sessaoId, Money.ZERO));

        assertThatIllegalStateException()
                .isThrownBy(() -> TenantContext.executarComo(conta.contaId(), () ->
                        vendaService.iniciar(sessaoId, conta.usuarioId())))
                .withMessageContaining("FECHADA");

        TenantContext.executarComo(conta.contaId(), () ->
                assertThat(vendas.findAll()).as("nada foi gravado").isEmpty());
    }

    @Test
    @DisplayName("iniciar não aceita sessão de caixa de outra conta (RNF05)")
    void iniciarNaoAceitaSessaoDeOutraConta() {
        ContaCriada contaA = criador.criar("Loja A", SENHA_DE_TESTE);
        ContaCriada contaB = criador.criar("Loja B", SENHA_DE_TESTE);
        UUID sessaoDaContaA = abrirCaixa(contaA);

        // A sessão existe e está ABERTA, mas é da conta A: para a conta B, a linha não volta do
        // banco, e o id é indistinguível de um que nunca existiu. Sem isso, uma venda da conta B
        // poderia ficar pendurada no caixa da conta A só por conhecer o id.
        assertThatExceptionOfType(SessaoCaixaNaoEncontradaException.class).isThrownBy(() ->
                TenantContext.executarComo(contaB.contaId(), () ->
                        vendaService.iniciar(sessaoDaContaA, contaB.usuarioId())));
    }

    @Test
    @DisplayName("o preço é copiado no ato: reajustar o produto depois não altera a venda (RF06)")
    void reajustarOProdutoNaoAlteraVendaPassada() {
        ContaCriada conta = criador.criar("Mercearia da Rua", SENHA_DE_TESTE);
        UUID sessaoId = abrirCaixa(conta);
        UUID cafeId = cadastrar(conta, "Cafe coado", Money.de("4.50"));

        UUID vendaId = TenantContext.executarComo(conta.contaId(), () ->
                vendaService.iniciar(sessaoId, conta.usuarioId()));
        UUID itemId = TenantContext.executarComo(conta.contaId(), () ->
                vendaService.adicionarItem(vendaId, cafeId, new BigDecimal("2"), Money.ZERO));

        // O reajuste acontece depois de o item estar gravado.
        TenantContext.executarComo(conta.contaId(), () ->
                produtos.editar(cafeId, new DadosDoProduto("Cafe coado", Money.de("6.00"), null,
                        null, "un", null)));

        TenantContext.executarComo(conta.contaId(), () -> {
            assertThat(produtos.consultarParaVenda(cafeId).preco())
                    .as("o cadastro mudou")
                    .isEqualTo(Money.de("6.00"));

            Venda gravada = vendas.findById(vendaId).orElseThrow().paraDominio();
            assertThat(gravada.getItens())
                    .as("a venda não")
                    .singleElement()
                    .satisfies(item -> {
                        assertThat(item.id()).isEqualTo(itemId);
                        assertThat(item.produtoId()).isEqualTo(cafeId);
                        assertThat(item.precoUnitario()).isEqualTo(Money.de("4.50"));
                        assertThat(item.quantidade()).isEqualByComparingTo("2");
                    });
            assertThat(gravada.getValorTotal()).isEqualTo(Money.de("9.00"));
        });
    }

    @Test
    @DisplayName("itens entram um a um, com desconto próprio, e o total atravessa o banco (RF07)")
    void itensEntramUmAUmEOTotalAtravessaOBanco() {
        ContaCriada conta = criador.criar("Empório do Bairro", SENHA_DE_TESTE);
        UUID sessaoId = abrirCaixa(conta);
        UUID cafeId = cadastrar(conta, "Cafe coado", Money.de("4.50"));
        UUID queijoId = cadastrar(conta, "Queijo minas", Money.de("39.90"));

        UUID vendaId = TenantContext.executarComo(conta.contaId(), () ->
                vendaService.iniciar(sessaoId, conta.usuarioId()));

        TenantContext.executarComo(conta.contaId(), () -> {
            vendaService.adicionarItem(vendaId, cafeId, new BigDecimal("2"), Money.de("1.00"));
            vendaService.adicionarItem(vendaId, queijoId, new BigDecimal("0.750"), Money.ZERO);
            // O mesmo produto numa segunda linha, sem mesclar com a primeira.
            vendaService.adicionarItem(vendaId, cafeId, BigDecimal.ONE, Money.ZERO);
        });

        TenantContext.executarComo(conta.contaId(), () -> {
            Venda gravada = vendas.findById(vendaId).orElseThrow().paraDominio();

            // 8,00 + 29,93 + 4,50, cada linha arredondada antes de somar.
            assertThat(gravada.getValorTotal()).isEqualTo(Money.de("42.43"));
            assertThat(gravada.getItens())
                    .extracting(ItemVenda::produtoId, ItemVenda::desconto)
                    .containsExactlyInAnyOrder(
                            tuple(cafeId, Money.de("1.00")),
                            tuple(queijoId, Money.ZERO),
                            tuple(cafeId, Money.ZERO));
        });
    }

    @Test
    @DisplayName("remover item apaga a linha do banco e o total volta a bater")
    void removerItemApagaALinha() {
        ContaCriada conta = criador.criar("Quitanda do Centro", SENHA_DE_TESTE);
        UUID sessaoId = abrirCaixa(conta);
        UUID cafeId = cadastrar(conta, "Cafe coado", Money.de("4.50"));
        UUID queijoId = cadastrar(conta, "Queijo minas", Money.de("39.90"));

        UUID vendaId = TenantContext.executarComo(conta.contaId(), () ->
                vendaService.iniciar(sessaoId, conta.usuarioId()));
        UUID itemDoCafe = TenantContext.executarComo(conta.contaId(), () ->
                vendaService.adicionarItem(vendaId, cafeId, new BigDecimal("2"), Money.ZERO));
        TenantContext.executarComo(conta.contaId(), () ->
                vendaService.adicionarItem(vendaId, queijoId, new BigDecimal("0.750"), Money.ZERO));

        TenantContext.executarComo(conta.contaId(), () ->
                vendaService.removerItem(vendaId, itemDoCafe));

        TenantContext.executarComo(conta.contaId(), () -> {
            // Relida do banco: se o orphanRemoval não apagasse a linha, o item voltaria aqui.
            Venda gravada = vendas.findById(vendaId).orElseThrow().paraDominio();
            assertThat(gravada.getItens())
                    .extracting(ItemVenda::produtoId)
                    .containsExactly(queijoId);
            assertThat(gravada.getValorTotal()).isEqualTo(Money.de("29.93"));
        });

        assertThatIllegalArgumentException()
                .isThrownBy(() -> TenantContext.executarComo(conta.contaId(), () ->
                        vendaService.removerItem(vendaId, itemDoCafe)))
                .withMessageContaining("nao esta na venda");
    }

    @Test
    @DisplayName("desconto da venda é gravado e o total reflete os dois níveis de desconto (RF08)")
    void descontoDaVendaAtravessaOBanco() {
        ContaCriada conta = criador.criar("Barbearia da Praca", SENHA_DE_TESTE);
        UUID sessaoId = abrirCaixa(conta);
        UUID corteId = cadastrar(conta, "Corte", Money.de("40.00"));

        UUID vendaId = TenantContext.executarComo(conta.contaId(), () ->
                vendaService.iniciar(sessaoId, conta.usuarioId()));
        TenantContext.executarComo(conta.contaId(), () ->
                vendaService.adicionarItem(vendaId, corteId, BigDecimal.ONE, Money.de("5.00")));

        TenantContext.executarComo(conta.contaId(), () ->
                vendaService.aplicarDesconto(vendaId, Money.de("10.00")));

        TenantContext.executarComo(conta.contaId(), () -> {
            Venda gravada = vendas.findById(vendaId).orElseThrow().paraDominio();
            assertThat(gravada.getValorDesconto()).isEqualTo(Money.de("10.00"));
            assertThat(gravada.getValorTotal()).isEqualTo(Money.de("25.00"));
        });

        // A regra da raiz vale vinda do banco: 35,01 passa da soma de 35,00.
        assertThatIllegalArgumentException()
                .isThrownBy(() -> TenantContext.executarComo(conta.contaId(), () ->
                        vendaService.aplicarDesconto(vendaId, Money.de("35.01"))))
                .withMessageContaining("maior que a soma dos itens");

        TenantContext.executarComo(conta.contaId(), () ->
                assertThat(vendas.findById(vendaId).orElseThrow().paraDominio().getValorDesconto())
                        .as("a recusa não mexeu no que estava gravado")
                        .isEqualTo(Money.de("10.00")));
    }

    @Test
    @DisplayName("produto inativado não entra em venda nova (RF05)")
    void produtoInativoNaoEntraNaVenda() {
        ContaCriada conta = criador.criar("Oficina da Avenida", SENHA_DE_TESTE);
        UUID sessaoId = abrirCaixa(conta);
        UUID produtoId = cadastrar(conta, "Troca de oleo", Money.de("80.00"));
        TenantContext.executarComo(conta.contaId(), () -> produtos.inativar(produtoId));

        UUID vendaId = TenantContext.executarComo(conta.contaId(), () ->
                vendaService.iniciar(sessaoId, conta.usuarioId()));

        assertThatIllegalStateException()
                .isThrownBy(() -> TenantContext.executarComo(conta.contaId(), () ->
                        vendaService.adicionarItem(vendaId, produtoId, BigDecimal.ONE,
                                Money.ZERO)))
                .withMessageContaining("inativo");

        TenantContext.executarComo(conta.contaId(), () ->
                assertThat(vendas.findById(vendaId).orElseThrow().paraDominio().getItens())
                        .isEmpty());
    }

    @Test
    @DisplayName("produto de outra conta não entra na venda, mesmo com o id em mãos (RNF05)")
    void produtoDeOutraContaNaoEntraNaVenda() {
        ContaCriada contaA = criador.criar("Mercado A", SENHA_DE_TESTE);
        ContaCriada contaB = criador.criar("Mercado B", SENHA_DE_TESTE);
        UUID produtoDaContaA = cadastrar(contaA, "Cafe coado", Money.de("4.50"));
        UUID sessaoDaContaB = abrirCaixa(contaB);

        UUID vendaDaContaB = TenantContext.executarComo(contaB.contaId(), () ->
                vendaService.iniciar(sessaoDaContaB, contaB.usuarioId()));

        // A consulta do preço passa pelo filtro de tenant do cadastro, então o produto da conta A
        // não existe para a venda da conta B. Sem isso, a conta B copiaria o preço da conta A.
        assertThatExceptionOfType(ProdutoNaoEncontradoException.class).isThrownBy(() ->
                TenantContext.executarComo(contaB.contaId(), () ->
                        vendaService.adicionarItem(vendaDaContaB, produtoDaContaA,
                                BigDecimal.ONE, Money.ZERO)));
    }

    @Test
    @DisplayName("conta B não monta a venda da conta A por nenhum caso de uso (RNF05)")
    void vendaDeOutraContaNaoEAlcancavel() {
        ContaCriada contaA = criador.criar("Salao A", SENHA_DE_TESTE);
        ContaCriada contaB = criador.criar("Salao B", SENHA_DE_TESTE);
        UUID sessaoDaContaA = abrirCaixa(contaA);
        UUID produtoDaContaB = cadastrar(contaB, "Escova", Money.de("50.00"));

        UUID vendaDaContaA = TenantContext.executarComo(contaA.contaId(), () ->
                vendaService.iniciar(sessaoDaContaA, contaA.usuarioId()));

        // O id de outra conta é indistinguível de um id que nunca existiu (RNF05).
        assertThatExceptionOfType(VendaNaoEncontradaException.class).isThrownBy(() ->
                TenantContext.executarComo(contaB.contaId(), () ->
                        vendaService.adicionarItem(vendaDaContaA, produtoDaContaB,
                                BigDecimal.ONE, Money.ZERO)));
        assertThatExceptionOfType(VendaNaoEncontradaException.class).isThrownBy(() ->
                TenantContext.executarComo(contaB.contaId(), () ->
                        vendaService.removerItem(vendaDaContaA, UUID.randomUUID())));
        assertThatExceptionOfType(VendaNaoEncontradaException.class).isThrownBy(() ->
                TenantContext.executarComo(contaB.contaId(), () ->
                        vendaService.aplicarDesconto(vendaDaContaA, Money.ZERO)));

        TenantContext.executarComo(contaA.contaId(), () ->
                assertThat(vendas.findById(vendaDaContaA).orElseThrow().paraDominio().getItens())
                        .as("a venda da conta A continua intacta")
                        .isEmpty());
    }

    @Test
    @DisplayName("conclui venda dividida entre dinheiro e Pix, com o troco calculado e as parcelas gravadas (RF09, RF10)")
    void concluiVendaDivididaEntreDinheiroEPix() {
        ContaCriada conta = criador.criar("Cafeteria Aurora", SENHA_DE_TESTE);
        UUID sessaoId = abrirCaixa(conta);
        UUID cafeId = cadastrar(conta, "Cafe coado", Money.de("4.50"));
        UUID queijoId = cadastrar(conta, "Queijo minas", Money.de("39.90"));

        UUID vendaId = TenantContext.executarComo(conta.contaId(), () ->
                vendaService.iniciar(sessaoId, conta.usuarioId()));
        TenantContext.executarComo(conta.contaId(), () -> {
            vendaService.adicionarItem(vendaId, cafeId, new BigDecimal("2"), Money.ZERO);
            vendaService.adicionarItem(vendaId, queijoId, new BigDecimal("0.750"), Money.ZERO);
        });
        // 9,00 + 29,93 = 38,93.

        Money trocoDoPix = TenantContext.executarComo(conta.contaId(), () ->
                vendaService.registrarPagamento(vendaId,
                        SolicitacaoPagamento.de(FormaPagamento.PIX, Money.de("20.00"))));
        Money trocoDoDinheiro = TenantContext.executarComo(conta.contaId(), () ->
                vendaService.registrarPagamento(vendaId,
                        SolicitacaoPagamento.emDinheiro(Money.de("18.93"), Money.de("50.00"))));

        // O troco vem do Strategy de verdade, o registrado pelo Spring, e não é persistido.
        assertThat(trocoDoPix).isEqualTo(Money.ZERO);
        assertThat(trocoDoDinheiro).isEqualTo(Money.de("31.07"));

        TenantContext.executarComo(conta.contaId(), () -> {
            Venda antesDeConcluir = vendas.findById(vendaId).orElseThrow().paraDominio();
            assertThat(antesDeConcluir.getStatus())
                    .as("a parcela que fecha a conta não conclui")
                    .isEqualTo(StatusVenda.ABERTA);
        });

        TenantContext.executarComo(conta.contaId(), () -> vendaService.concluir(vendaId));

        TenantContext.executarComo(conta.contaId(), () -> {
            Venda gravada = vendas.findById(vendaId).orElseThrow().paraDominio();
            assertThat(gravada.getStatus()).isEqualTo(StatusVenda.CONCLUIDA);
            assertThat(gravada.getValorTotal()).isEqualTo(Money.de("38.93"));
            assertThat(gravada.getPagamentos())
                    .extracting(Pagamento::forma, Pagamento::valor, Pagamento::status)
                    .containsExactly(
                            tuple(FormaPagamento.PIX, Money.de("20.00"),
                                    StatusPagamento.CONFIRMADO),
                            tuple(FormaPagamento.DINHEIRO, Money.de("18.93"),
                                    StatusPagamento.CONFIRMADO));
        });

        // O status atravessou o banco: a venda concluída não aceita mais montagem nem pagamento.
        assertThatIllegalStateException()
                .isThrownBy(() -> TenantContext.executarComo(conta.contaId(), () ->
                        vendaService.adicionarItem(vendaId, cafeId, BigDecimal.ONE, Money.ZERO)))
                .withMessageContaining("CONCLUIDA");
        assertThatIllegalStateException()
                .isThrownBy(() -> TenantContext.executarComo(conta.contaId(), () ->
                        vendaService.registrarPagamento(vendaId,
                                SolicitacaoPagamento.de(FormaPagamento.PIX, Money.de("1.00")))))
                .withMessageContaining("CONCLUIDA");
        assertThatIllegalStateException()
                .isThrownBy(() -> TenantContext.executarComo(conta.contaId(), () ->
                        vendaService.concluir(vendaId)))
                .withMessageContaining("CONCLUIDA");
    }

    @Test
    @DisplayName("parcela maior que o que falta pagar é recusada pelo serviço e nada é gravado")
    void parcelaAcimaDoSaldoNaoEGravada() {
        ContaCriada conta = criador.criar("Padaria Central", SENHA_DE_TESTE);
        UUID sessaoId = abrirCaixa(conta);
        UUID paoId = cadastrar(conta, "Pao de queijo", Money.de("30.00"));

        UUID vendaId = TenantContext.executarComo(conta.contaId(), () ->
                vendaService.iniciar(sessaoId, conta.usuarioId()));
        TenantContext.executarComo(conta.contaId(), () -> {
            vendaService.adicionarItem(vendaId, paoId, BigDecimal.ONE, Money.ZERO);
            vendaService.registrarPagamento(vendaId,
                    SolicitacaoPagamento.de(FormaPagamento.PIX, Money.de("20.00")));
        });

        assertThatIllegalArgumentException()
                .isThrownBy(() -> TenantContext.executarComo(conta.contaId(), () ->
                        vendaService.registrarPagamento(vendaId,
                                SolicitacaoPagamento.de(FormaPagamento.CARTAO, Money.de("15.00")))))
                .withMessageContaining("maior que o que falta pagar, 10.00");

        TenantContext.executarComo(conta.contaId(), () ->
                assertThat(vendas.findById(vendaId).orElseThrow().paraDominio().getPagamentos())
                        .as("a parcela recusada não deixou rastro")
                        .extracting(Pagamento::valor)
                        .containsExactly(Money.de("20.00")));
    }

    @Test
    @DisplayName("concluir com pagamento a menos é recusado e a venda continua ABERTA no banco")
    void concluirComPagamentoAMenosERecusado() {
        ContaCriada conta = criador.criar("Mercearia da Rua", SENHA_DE_TESTE);
        UUID sessaoId = abrirCaixa(conta);
        UUID cafeId = cadastrar(conta, "Cafe coado", Money.de("4.50"));

        UUID vendaId = TenantContext.executarComo(conta.contaId(), () ->
                vendaService.iniciar(sessaoId, conta.usuarioId()));
        TenantContext.executarComo(conta.contaId(), () -> {
            vendaService.adicionarItem(vendaId, cafeId, new BigDecimal("2"), Money.ZERO);
            vendaService.registrarPagamento(vendaId,
                    SolicitacaoPagamento.de(FormaPagamento.CARTAO, Money.de("5.00")));
        });

        assertThatIllegalStateException()
                .isThrownBy(() -> TenantContext.executarComo(conta.contaId(), () ->
                        vendaService.concluir(vendaId)))
                .withMessageContaining("faltam 4.00");

        TenantContext.executarComo(conta.contaId(), () ->
                assertThat(vendas.findById(vendaId).orElseThrow().paraDominio().getStatus())
                        .isEqualTo(StatusVenda.ABERTA));
    }

    @Test
    @DisplayName("a regra da forma de pagamento atravessa o serviço: valor recebido em Pix é recusado")
    void regraDaEstrategiaAtravessaOServico() {
        ContaCriada conta = criador.criar("Empório do Bairro", SENHA_DE_TESTE);
        UUID sessaoId = abrirCaixa(conta);
        UUID cafeId = cadastrar(conta, "Cafe coado", Money.de("4.50"));

        UUID vendaId = TenantContext.executarComo(conta.contaId(), () ->
                vendaService.iniciar(sessaoId, conta.usuarioId()));
        TenantContext.executarComo(conta.contaId(), () ->
                vendaService.adicionarItem(vendaId, cafeId, BigDecimal.ONE, Money.ZERO));

        // Quem recusa é a estratégia de Pix, encontrada pelo serviço de pagamentos; a venda não é
        // tocada.
        assertThatIllegalArgumentException()
                .isThrownBy(() -> TenantContext.executarComo(conta.contaId(), () ->
                        vendaService.registrarPagamento(vendaId, new SolicitacaoPagamento(
                                FormaPagamento.PIX, Money.de("4.50"), Money.de("10.00")))))
                .withMessageContaining("nao aceita valor recebido");

        TenantContext.executarComo(conta.contaId(), () ->
                assertThat(vendas.findById(vendaId).orElseThrow().paraDominio().getPagamentos())
                        .isEmpty());
    }

    @Test
    @DisplayName("conta B não registra pagamento nem conclui a venda da conta A (RNF05)")
    void contaBNaoPagaNemConcluiVendaDaContaA() {
        ContaCriada contaA = criador.criar("Loja A", SENHA_DE_TESTE);
        ContaCriada contaB = criador.criar("Loja B", SENHA_DE_TESTE);
        UUID sessaoDaContaA = abrirCaixa(contaA);
        UUID produtoDaContaA = cadastrar(contaA, "Escova", Money.de("50.00"));

        UUID vendaDaContaA = TenantContext.executarComo(contaA.contaId(), () ->
                vendaService.iniciar(sessaoDaContaA, contaA.usuarioId()));
        TenantContext.executarComo(contaA.contaId(), () ->
                vendaService.adicionarItem(vendaDaContaA, produtoDaContaA, BigDecimal.ONE,
                        Money.ZERO));

        // O id de outra conta é indistinguível de um id que nunca existiu, e a recusa vem antes de
        // qualquer regra de pagamento rodar.
        assertThatExceptionOfType(VendaNaoEncontradaException.class).isThrownBy(() ->
                TenantContext.executarComo(contaB.contaId(), () ->
                        vendaService.registrarPagamento(vendaDaContaA,
                                SolicitacaoPagamento.de(FormaPagamento.PIX, Money.de("50.00")))));
        assertThatExceptionOfType(VendaNaoEncontradaException.class).isThrownBy(() ->
                TenantContext.executarComo(contaB.contaId(), () ->
                        vendaService.concluir(vendaDaContaA)));

        TenantContext.executarComo(contaA.contaId(), () -> {
            Venda intacta = vendas.findById(vendaDaContaA).orElseThrow().paraDominio();
            assertThat(intacta.getPagamentos()).isEmpty();
            assertThat(intacta.getStatus()).isEqualTo(StatusVenda.ABERTA);
        });
    }

    private UUID abrirCaixa(ContaCriada conta) {
        return TenantContext.executarComo(conta.contaId(), () ->
                caixas.abrir(conta.usuarioId(), Money.ZERO));
    }

    private UUID cadastrar(ContaCriada conta, String nome, Money preco) {
        return TenantContext.executarComo(conta.contaId(), () ->
                produtos.cadastrar(TipoProduto.PRODUTO,
                        new DadosDoProduto(nome, preco, null, null, "un", null)));
    }
}
