package br.com.caixasimples.vendas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.tuple;
import static org.awaitility.Awaitility.await;

import br.com.caixasimples.TesteDeIntegracao;
import br.com.caixasimples.cadastro.TipoProduto;
import br.com.caixasimples.cadastro.application.ProdutoNaoEncontradoException;
import br.com.caixasimples.cadastro.application.ProdutoService;
import br.com.caixasimples.cadastro.application.ProdutoService.DadosDoProduto;
import br.com.caixasimples.cadastro.internal.ProdutoRepository;
import br.com.caixasimples.caixa.TipoMovimentoCaixa;
import br.com.caixasimples.caixa.application.SessaoCaixaService;
import br.com.caixasimples.caixa.domain.MovimentoCaixa;
import br.com.caixasimples.caixa.internal.SessaoCaixaRepository;
import br.com.caixasimples.contas.CriadorDeContaDeTeste;
import br.com.caixasimples.contas.CriadorDeContaDeTeste.ContaCriada;
import br.com.caixasimples.contas.CriadorDeContaDeTeste.UsuarioCriado;
import br.com.caixasimples.pagamentos.FormaPagamento;
import br.com.caixasimples.pagamentos.StatusPagamento;
import br.com.caixasimples.pagamentos.domain.SolicitacaoPagamento;
import br.com.caixasimples.shared.AcessoNegadoException;
import br.com.caixasimples.shared.Money;
import br.com.caixasimples.shared.TenantContext;
import br.com.caixasimples.vendas.VendaConcluida.Parcela;
import br.com.caixasimples.vendas.application.Comprovante;
import br.com.caixasimples.vendas.application.VendaNaoEncontradaException;
import br.com.caixasimples.vendas.application.VendaService;
import br.com.caixasimples.vendas.domain.ItemVenda;
import br.com.caixasimples.vendas.domain.Pagamento;
import br.com.caixasimples.vendas.domain.Venda;
import br.com.caixasimples.vendas.internal.VendaRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

/**
 * A venda contra o banco de verdade: iniciar a comanda, lançar e remover item (RF07), desconto
 * sobre o total (RF08), a cópia do preço do produto (RF06), o pagamento dividido entre formas com
 * o Strategy de verdade (RF09, RF10), a conclusão e o cancelamento (RF12).
 *
 * <p>Existe separado de {@code VendaTest} porque prova outra coisa: lá a conta do agregado está
 * certa <em>em memória</em>; aqui ela <strong>atravessa o banco</strong>, que é o único jeito de
 * exercitar o {@code atualizarCom} da entidade, inclusive a remoção de linha por
 * {@code orphanRemoval} e o acréscimo das parcelas, as três perguntas feitas a outros módulos, o
 * isolamento entre contas em cada uma delas e, na conclusão e no cancelamento, o evento
 * publicado e o dinheiro e o estoque indo e voltando pelo outbox.
 *
 * <p>Fica no pacote {@code vendas} e enxerga só o que um controller enxergaria: o serviço, o
 * domínio e a raiz do agregado. Os casos de uso de {@code caixa} e {@code cadastro} entram no
 * papel que um controller teria, para preparar o cenário; os repositórios do caixa e do cadastro
 * entram só para ler o que os ouvintes gravaram.
 */
@RecordApplicationEvents
class VendaServiceTest extends TesteDeIntegracao {

    private static final String SENHA_DE_TESTE = "uma senha longa de teste";

    @Autowired
    private VendaService vendaService;

    @Autowired
    private VendaRepository vendas;

    @Autowired
    private SessaoCaixaService caixas;

    @Autowired
    private SessaoCaixaRepository sessoes;

    @Autowired
    private ProdutoService produtos;

    @Autowired
    private ProdutoRepository linhasDeProduto;

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

        UUID vendaId = conta.comoUsuario(() ->
                vendaService.iniciar(sessaoId));

        conta.comoUsuario(() -> {
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
        conta.comoUsuario(() -> caixas.fechar(sessaoId, Money.ZERO));

        assertThatIllegalStateException()
                .isThrownBy(() -> conta.comoUsuario(() ->
                        vendaService.iniciar(sessaoId)))
                .withMessageContaining("nao esta ABERTA");

        conta.comoUsuario(() ->
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
        assertThatExceptionOfType(SessaoCaixaNaoEncontradaParaVendaException.class).isThrownBy(() ->
                contaB.comoUsuario(() ->
                        vendaService.iniciar(sessaoDaContaA)));
    }

    @Test
    @DisplayName("o preço é copiado no ato: reajustar o produto depois não altera a venda (RF06)")
    void reajustarOProdutoNaoAlteraVendaPassada() {
        ContaCriada conta = criador.criar("Mercearia da Rua", SENHA_DE_TESTE);
        UUID sessaoId = abrirCaixa(conta);
        UUID cafeId = cadastrar(conta, "Cafe coado", Money.de("4.50"));

        UUID vendaId = conta.comoUsuario(() ->
                vendaService.iniciar(sessaoId));
        UUID itemId = conta.comoUsuario(() ->
                vendaService.adicionarItem(vendaId, cafeId, new BigDecimal("2"), Money.ZERO));

        // O reajuste acontece depois de o item estar gravado.
        conta.comoUsuario(() ->
                produtos.editar(cafeId, new DadosDoProduto("Cafe coado", Money.de("6.00"), null,
                        null, "un", null)));

        conta.comoUsuario(() -> {
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

        UUID vendaId = conta.comoUsuario(() ->
                vendaService.iniciar(sessaoId));

        conta.comoUsuario(() -> {
            vendaService.adicionarItem(vendaId, cafeId, new BigDecimal("2"), Money.de("1.00"));
            vendaService.adicionarItem(vendaId, queijoId, new BigDecimal("0.750"), Money.ZERO);
            // O mesmo produto numa segunda linha, sem mesclar com a primeira.
            vendaService.adicionarItem(vendaId, cafeId, BigDecimal.ONE, Money.ZERO);
        });

        conta.comoUsuario(() -> {
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

        UUID vendaId = conta.comoUsuario(() ->
                vendaService.iniciar(sessaoId));
        UUID itemDoCafe = conta.comoUsuario(() ->
                vendaService.adicionarItem(vendaId, cafeId, new BigDecimal("2"), Money.ZERO));
        conta.comoUsuario(() ->
                vendaService.adicionarItem(vendaId, queijoId, new BigDecimal("0.750"), Money.ZERO));

        conta.comoUsuario(() ->
                vendaService.removerItem(vendaId, itemDoCafe));

        conta.comoUsuario(() -> {
            // Relida do banco: se o orphanRemoval não apagasse a linha, o item voltaria aqui.
            Venda gravada = vendas.findById(vendaId).orElseThrow().paraDominio();
            assertThat(gravada.getItens())
                    .extracting(ItemVenda::produtoId)
                    .containsExactly(queijoId);
            assertThat(gravada.getValorTotal()).isEqualTo(Money.de("29.93"));
        });

        assertThatIllegalArgumentException()
                .isThrownBy(() -> conta.comoUsuario(() ->
                        vendaService.removerItem(vendaId, itemDoCafe)))
                .withMessageContaining("nao esta na venda");
    }

    @Test
    @DisplayName("desconto da venda é gravado e o total reflete os dois níveis de desconto (RF08)")
    void descontoDaVendaAtravessaOBanco() {
        ContaCriada conta = criador.criar("Barbearia da Praca", SENHA_DE_TESTE);
        UUID sessaoId = abrirCaixa(conta);
        UUID corteId = cadastrar(conta, "Corte", Money.de("40.00"));

        UUID vendaId = conta.comoUsuario(() ->
                vendaService.iniciar(sessaoId));
        conta.comoUsuario(() ->
                vendaService.adicionarItem(vendaId, corteId, BigDecimal.ONE, Money.de("5.00")));

        conta.comoUsuario(() ->
                vendaService.aplicarDesconto(vendaId, Money.de("10.00")));

        conta.comoUsuario(() -> {
            Venda gravada = vendas.findById(vendaId).orElseThrow().paraDominio();
            assertThat(gravada.getValorDesconto()).isEqualTo(Money.de("10.00"));
            assertThat(gravada.getValorTotal()).isEqualTo(Money.de("25.00"));
        });

        // A regra da raiz vale vinda do banco: 35,01 passa da soma de 35,00.
        assertThatIllegalArgumentException()
                .isThrownBy(() -> conta.comoUsuario(() ->
                        vendaService.aplicarDesconto(vendaId, Money.de("35.01"))))
                .withMessageContaining("maior que a soma dos itens");

        conta.comoUsuario(() ->
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
        conta.comoUsuario(() -> produtos.inativar(produtoId));

        UUID vendaId = conta.comoUsuario(() ->
                vendaService.iniciar(sessaoId));

        assertThatIllegalStateException()
                .isThrownBy(() -> conta.comoUsuario(() ->
                        vendaService.adicionarItem(vendaId, produtoId, BigDecimal.ONE,
                                Money.ZERO)))
                .withMessageContaining("inativo");

        conta.comoUsuario(() ->
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

        UUID vendaDaContaB = contaB.comoUsuario(() ->
                vendaService.iniciar(sessaoDaContaB));

        // A consulta do preço passa pelo filtro de tenant do cadastro, então o produto da conta A
        // não existe para a venda da conta B. Sem isso, a conta B copiaria o preço da conta A.
        assertThatExceptionOfType(ProdutoNaoEncontradoException.class).isThrownBy(() ->
                contaB.comoUsuario(() ->
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

        UUID vendaDaContaA = contaA.comoUsuario(() ->
                vendaService.iniciar(sessaoDaContaA));

        // O id de outra conta é indistinguível de um id que nunca existiu (RNF05).
        assertThatExceptionOfType(VendaNaoEncontradaException.class).isThrownBy(() ->
                contaB.comoUsuario(() ->
                        vendaService.adicionarItem(vendaDaContaA, produtoDaContaB,
                                BigDecimal.ONE, Money.ZERO)));
        assertThatExceptionOfType(VendaNaoEncontradaException.class).isThrownBy(() ->
                contaB.comoUsuario(() ->
                        vendaService.removerItem(vendaDaContaA, UUID.randomUUID())));
        assertThatExceptionOfType(VendaNaoEncontradaException.class).isThrownBy(() ->
                contaB.comoUsuario(() ->
                        vendaService.aplicarDesconto(vendaDaContaA, Money.ZERO)));

        contaA.comoUsuario(() ->
                assertThat(vendas.findById(vendaDaContaA).orElseThrow().paraDominio().getItens())
                        .as("a venda da conta A continua intacta")
                        .isEmpty());
    }

    @Test
    @DisplayName("conclui venda dividida entre dinheiro e Cartao, com o troco calculado e as parcelas gravadas (RF09, RF10)")
    void concluiVendaDivididaEntreDinheiroECartao(ApplicationEvents eventos) {
        ContaCriada conta = criador.criar("Cafeteria Aurora", SENHA_DE_TESTE);
        UUID sessaoId = abrirCaixa(conta);
        UUID cafeId = cadastrar(conta, "Cafe coado", Money.de("4.50"));
        UUID queijoId = cadastrar(conta, "Queijo minas", Money.de("39.90"));

        UUID vendaId = conta.comoUsuario(() ->
                vendaService.iniciar(sessaoId));
        conta.comoUsuario(() -> {
            vendaService.adicionarItem(vendaId, cafeId, new BigDecimal("2"), Money.ZERO);
            vendaService.adicionarItem(vendaId, queijoId, new BigDecimal("0.750"), Money.ZERO);
        });
        // 9,00 + 29,93 = 38,93.

        Money trocoDoCartao = conta.comoUsuario(() ->
                vendaService.registrarPagamento(vendaId,
                        SolicitacaoPagamento.de(FormaPagamento.CARTAO, Money.de("20.00"))));
        Money trocoDoDinheiro = conta.comoUsuario(() ->
                vendaService.registrarPagamento(vendaId,
                        SolicitacaoPagamento.emDinheiro(Money.de("18.93"), Money.de("50.00"))));

        // O troco vem do Strategy de verdade, o registrado pelo Spring: volta a quem chamou e
        // fica gravado na parcela, para o comprovante.
        assertThat(trocoDoCartao).isEqualTo(Money.ZERO);
        assertThat(trocoDoDinheiro).isEqualTo(Money.de("31.07"));

        conta.comoUsuario(() -> {
            Venda antesDeConcluir = vendas.findById(vendaId).orElseThrow().paraDominio();
            assertThat(antesDeConcluir.getStatus())
                    .as("a parcela que fecha a conta não conclui")
                    .isEqualTo(StatusVenda.ABERTA);
            assertThat(antesDeConcluir.getConcluidoEm()).isNull();
        });

        conta.comoUsuario(() -> vendaService.concluir(vendaId));

        conta.comoUsuario(() -> {
            Venda gravada = vendas.findById(vendaId).orElseThrow().paraDominio();
            assertThat(gravada.getStatus()).isEqualTo(StatusVenda.CONCLUIDA);
            assertThat(gravada.getConcluidoEm())
                    .as("a conclusao grava o seu instante")
                    .isNotNull()
                    .isAfterOrEqualTo(gravada.getCriadoEm());
            assertThat(gravada.getValorTotal()).isEqualTo(Money.de("38.93"));
            assertThat(gravada.getPagamentos())
                    .extracting(Pagamento::forma, Pagamento::valor, Pagamento::status,
                            Pagamento::troco)
                    .containsExactly(
                            tuple(FormaPagamento.CARTAO, Money.de("20.00"),
                                    StatusPagamento.CONFIRMADO, Money.ZERO),
                            tuple(FormaPagamento.DINHEIRO, Money.de("18.93"),
                                    StatusPagamento.CONFIRMADO, Money.de("31.07")));
        });

        // O status atravessou o banco: a venda concluída não aceita mais montagem nem pagamento.
        assertThatIllegalStateException()
                .isThrownBy(() -> conta.comoUsuario(() ->
                        vendaService.adicionarItem(vendaId, cafeId, BigDecimal.ONE, Money.ZERO)))
                .withMessageContaining("CONCLUIDA");
        assertThatIllegalStateException()
                .isThrownBy(() -> conta.comoUsuario(() ->
                        vendaService.registrarPagamento(vendaId,
                                SolicitacaoPagamento.de(FormaPagamento.CARTAO, Money.de("1.00")))))
                .withMessageContaining("CONCLUIDA");
        assertThatIllegalStateException()
                .isThrownBy(() -> conta.comoUsuario(() ->
                        vendaService.concluir(vendaId)))
                .withMessageContaining("CONCLUIDA");

        // Saiu um evento só, com o fato inteiro e a conta de quem concluiu, lida do contexto.
        assertThat(eventos.stream(VendaConcluida.class))
                .singleElement()
                .satisfies(evento -> {
                    assertThat(evento.contaId()).isEqualTo(conta.contaId());
                    assertThat(evento.vendaId()).isEqualTo(vendaId);
                    assertThat(evento.sessaoCaixaId()).isEqualTo(sessaoId);
                    assertThat(evento.usuarioId()).isEqualTo(conta.usuarioId());
                    assertThat(evento.itens()).satisfiesExactly(
                            item -> {
                                assertThat(item.produtoId()).isEqualTo(cafeId);
                                assertThat(item.quantidade()).isEqualByComparingTo("2");
                            },
                            item -> {
                                assertThat(item.produtoId()).isEqualTo(queijoId);
                                assertThat(item.quantidade()).isEqualByComparingTo("0.750");
                            });
                    assertThat(evento.parcelas())
                            .extracting(Parcela::forma, Parcela::valor, Parcela::status)
                            .containsExactly(
                                    tuple(FormaPagamento.CARTAO, Money.de("20.00"),
                                            StatusPagamento.CONFIRMADO),
                                    tuple(FormaPagamento.DINHEIRO, Money.de("18.93"),
                                            StatusPagamento.CONFIRMADO));
                });

        // E o caixa reagiu, em outra thread, pelo outbox: entrou na gaveta o dinheiro, 18,93, e
        // não o total da venda; o Cartao nunca esteve lá.
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                conta.comoUsuario(() ->
                        assertThat(sessoes.findById(sessaoId).orElseThrow().paraDominio()
                                .getMovimentos())
                                .extracting(MovimentoCaixa::tipo, MovimentoCaixa::valor,
                                        MovimentoCaixa::vendaId)
                                .containsExactly(tuple(TipoMovimentoCaixa.VENDA,
                                        Money.de("18.93"), vendaId))));
    }

    @Test
    @DisplayName("concluir exige o caixa em que a venda nasceu ainda ABERTO; fechado, a venda fica ABERTA")
    void concluirExigeSessaoAberta(ApplicationEvents eventos) {
        ContaCriada conta = criador.criar("Quitanda do Centro", SENHA_DE_TESTE);
        UUID sessaoId = abrirCaixa(conta);
        UUID cafeId = cadastrar(conta, "Cafe coado", Money.de("4.50"));

        UUID vendaId = conta.comoUsuario(() ->
                vendaService.iniciar(sessaoId));
        conta.comoUsuario(() -> {
            vendaService.adicionarItem(vendaId, cafeId, BigDecimal.ONE, Money.ZERO);
            vendaService.registrarPagamento(vendaId,
                    SolicitacaoPagamento.emDinheiro(Money.de("4.50"), Money.de("4.50")));
        });

        // O operador fecha o caixa com a comanda ainda aberta.
        conta.comoUsuario(() -> caixas.fechar(sessaoId, Money.ZERO));

        // A conta está paga, mas o caixa que receberia o dinheiro já conferiu a gaveta.
        assertThatIllegalStateException()
                .isThrownBy(() -> conta.comoUsuario(() ->
                        vendaService.concluir(vendaId)))
                .withMessageContaining("nao esta ABERTA");

        conta.comoUsuario(() ->
                assertThat(vendas.findById(vendaId).orElseThrow().paraDominio().getStatus())
                        .as("a venda fica ABERTA até o cancelamento")
                        .isEqualTo(StatusVenda.ABERTA));
        assertThat(eventos.stream(VendaConcluida.class))
                .as("nada foi publicado")
                .isEmpty();
    }

    @Test
    @DisplayName("parcela maior que o que falta pagar é recusada pelo serviço e nada é gravado")
    void parcelaAcimaDoSaldoNaoEGravada() {
        ContaCriada conta = criador.criar("Padaria Central", SENHA_DE_TESTE);
        UUID sessaoId = abrirCaixa(conta);
        UUID paoId = cadastrar(conta, "Pao de queijo", Money.de("30.00"));

        UUID vendaId = conta.comoUsuario(() ->
                vendaService.iniciar(sessaoId));
        conta.comoUsuario(() -> {
            vendaService.adicionarItem(vendaId, paoId, BigDecimal.ONE, Money.ZERO);
            vendaService.registrarPagamento(vendaId,
                    SolicitacaoPagamento.de(FormaPagamento.CARTAO, Money.de("20.00")));
        });

        assertThatIllegalArgumentException()
                .isThrownBy(() -> conta.comoUsuario(() ->
                        vendaService.registrarPagamento(vendaId,
                                SolicitacaoPagamento.de(FormaPagamento.CARTAO, Money.de("15.00")))))
                .withMessageContaining("maior que o que falta pagar, 10.00");

        conta.comoUsuario(() ->
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

        UUID vendaId = conta.comoUsuario(() ->
                vendaService.iniciar(sessaoId));
        conta.comoUsuario(() -> {
            vendaService.adicionarItem(vendaId, cafeId, new BigDecimal("2"), Money.ZERO);
            vendaService.registrarPagamento(vendaId,
                    SolicitacaoPagamento.de(FormaPagamento.CARTAO, Money.de("5.00")));
        });

        assertThatIllegalStateException()
                .isThrownBy(() -> conta.comoUsuario(() ->
                        vendaService.concluir(vendaId)))
                .withMessageContaining("faltam 4.00");

        conta.comoUsuario(() ->
                assertThat(vendas.findById(vendaId).orElseThrow().paraDominio().getStatus())
                        .isEqualTo(StatusVenda.ABERTA));
    }

    @Test
    @DisplayName("a regra da forma de pagamento atravessa o serviço: valor recebido em Cartao é recusado")
    void regraDaEstrategiaAtravessaOServico() {
        ContaCriada conta = criador.criar("Empório do Bairro", SENHA_DE_TESTE);
        UUID sessaoId = abrirCaixa(conta);
        UUID cafeId = cadastrar(conta, "Cafe coado", Money.de("4.50"));

        UUID vendaId = conta.comoUsuario(() ->
                vendaService.iniciar(sessaoId));
        conta.comoUsuario(() ->
                vendaService.adicionarItem(vendaId, cafeId, BigDecimal.ONE, Money.ZERO));

        // Quem recusa é a estratégia de Cartao, encontrada pelo serviço de pagamentos; a venda não é
        // tocada.
        assertThatIllegalArgumentException()
                .isThrownBy(() -> conta.comoUsuario(() ->
                        vendaService.registrarPagamento(vendaId, new SolicitacaoPagamento(
                                FormaPagamento.CARTAO, Money.de("4.50"), Money.de("10.00")))))
                .withMessageContaining("nao aceita valor recebido");

        conta.comoUsuario(() ->
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

        UUID vendaDaContaA = contaA.comoUsuario(() ->
                vendaService.iniciar(sessaoDaContaA));
        contaA.comoUsuario(() ->
                vendaService.adicionarItem(vendaDaContaA, produtoDaContaA, BigDecimal.ONE,
                        Money.ZERO));

        // O id de outra conta é indistinguível de um id que nunca existiu, e a recusa vem antes de
        // qualquer regra de pagamento rodar.
        assertThatExceptionOfType(VendaNaoEncontradaException.class).isThrownBy(() ->
                contaB.comoUsuario(() ->
                        vendaService.registrarPagamento(vendaDaContaA,
                                SolicitacaoPagamento.de(FormaPagamento.CARTAO, Money.de("50.00")))));
        assertThatExceptionOfType(VendaNaoEncontradaException.class).isThrownBy(() ->
                contaB.comoUsuario(() ->
                        vendaService.concluir(vendaDaContaA)));
        assertThatExceptionOfType(VendaNaoEncontradaException.class).isThrownBy(() ->
                contaB.comoUsuario(() ->
                        vendaService.cancelar(vendaDaContaA)));

        contaA.comoUsuario(() -> {
            Venda intacta = vendas.findById(vendaDaContaA).orElseThrow().paraDominio();
            assertThat(intacta.getPagamentos()).isEmpty();
            assertThat(intacta.getStatus()).isEqualTo(StatusVenda.ABERTA);
        });
    }

    @Test
    @DisplayName("cancelar a venda concluída devolve o dinheiro ao caixa e os produtos ao estoque, pelo outbox (RF12)")
    void cancelarDesfazOCaixaEOEstoque(ApplicationEvents eventos) {
        ContaCriada conta = criador.criar("Cafeteria Aurora", SENHA_DE_TESTE);
        criador.habilitarEstoque(conta.contaId());
        UUID sessaoId = abrirCaixa(conta);
        UUID cafeId = cadastrar(conta, "Cafe coado", Money.de("4.50"));
        UUID queijoId = cadastrar(conta, "Queijo minas", Money.de("39.90"));

        UUID vendaId = conta.comoUsuario(() ->
                vendaService.iniciar(sessaoId));
        conta.comoUsuario(() -> {
            vendaService.adicionarItem(vendaId, cafeId, new BigDecimal("2"), Money.ZERO);
            vendaService.adicionarItem(vendaId, queijoId, new BigDecimal("0.750"), Money.ZERO);
            // 9,00 + 29,93 = 38,93, pagos 20,00 em Cartao e 18,93 em dinheiro.
            vendaService.registrarPagamento(vendaId,
                    SolicitacaoPagamento.de(FormaPagamento.CARTAO, Money.de("20.00")));
            vendaService.registrarPagamento(vendaId,
                    SolicitacaoPagamento.emDinheiro(Money.de("18.93"), Money.de("50.00")));
            vendaService.concluir(vendaId);
        });

        // A conclusão chegou aos dois ouvintes: é o estado que o cancelamento vai desfazer.
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                conta.comoUsuario(() -> {
                    assertThat(esperadoDe(sessaoId)).isEqualTo(Money.de("18.93"));
                    assertThat(saldoDe(cafeId)).isEqualByComparingTo("-2");
                    assertThat(saldoDe(queijoId)).isEqualByComparingTo("-0.750");
                }));

        conta.comoUsuario(() -> vendaService.cancelar(vendaId));

        conta.comoUsuario(() -> {
            Venda gravada = vendas.findById(vendaId).orElseThrow().paraDominio();
            assertThat(gravada.getStatus()).isEqualTo(StatusVenda.CANCELADA);
            // Itens e parcelas ficam: a venda cancelada continua contando o que tinha sido
            // vendido e como tinha sido pago.
            assertThat(gravada.getItens()).hasSize(2);
            assertThat(gravada.getPagamentos())
                    .extracting(Pagamento::forma, Pagamento::valor, Pagamento::status)
                    .containsExactly(
                            tuple(FormaPagamento.CARTAO, Money.de("20.00"),
                                    StatusPagamento.CONFIRMADO),
                            tuple(FormaPagamento.DINHEIRO, Money.de("18.93"),
                                    StatusPagamento.CONFIRMADO));
        });

        // Saiu um evento de cancelamento só, com o mesmo fato da conclusão.
        assertThat(eventos.stream(VendaCancelada.class))
                .singleElement()
                .satisfies(evento -> {
                    assertThat(evento.contaId()).isEqualTo(conta.contaId());
                    assertThat(evento.vendaId()).isEqualTo(vendaId);
                    assertThat(evento.sessaoCaixaId()).isEqualTo(sessaoId);
                    assertThat(evento.usuarioId()).isEqualTo(conta.usuarioId());
                    assertThat(evento.itens())
                            .extracting(VendaCancelada.Item::produtoId)
                            .containsExactly(cafeId, queijoId);
                    assertThat(evento.parcelas())
                            .extracting(VendaCancelada.Parcela::forma,
                                    VendaCancelada.Parcela::valor)
                            .containsExactly(
                                    tuple(FormaPagamento.CARTAO, Money.de("20.00")),
                                    tuple(FormaPagamento.DINHEIRO, Money.de("18.93")));
                });

        // O caixa refletiu: a VENDA fica, o ESTORNO a espelha, e o esperado volta ao anterior.
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                conta.comoUsuario(() ->
                        assertThat(sessoes.findById(sessaoId).orElseThrow().paraDominio()
                                .getMovimentos())
                                .extracting(MovimentoCaixa::tipo, MovimentoCaixa::valor,
                                        MovimentoCaixa::vendaId)
                                .containsExactly(
                                        tuple(TipoMovimentoCaixa.VENDA, Money.de("18.93"),
                                                vendaId),
                                        tuple(TipoMovimentoCaixa.ESTORNO, Money.de("18.93"),
                                                vendaId))));
        // E o estoque voltou ao valor anterior à venda.
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                conta.comoUsuario(() -> {
                    assertThat(saldoDe(cafeId)).isEqualByComparingTo("0");
                    assertThat(saldoDe(queijoId)).isEqualByComparingTo("0");
                }));
        conta.comoUsuario(() ->
                assertThat(esperadoDe(sessaoId)).isEqualTo(Money.ZERO));

        // CANCELADA é final, e o status atravessou o banco.
        assertThatIllegalStateException()
                .isThrownBy(() -> conta.comoUsuario(() ->
                        vendaService.cancelar(vendaId)))
                .withMessageContaining("ja esta CANCELADA");
        assertThat(eventos.stream(VendaCancelada.class)).as("nada mais foi publicado").hasSize(1);
    }

    @Test
    @DisplayName("cancelar venda CONCLUIDA exige o caixa em que ela nasceu ainda ABERTO; fechado, a venda fica CONCLUIDA")
    void cancelarVendaConcluidaExigeSessaoAberta(ApplicationEvents eventos) {
        ContaCriada conta = criador.criar("Quitanda do Centro", SENHA_DE_TESTE);
        UUID sessaoId = abrirCaixa(conta);
        UUID cafeId = cadastrar(conta, "Cafe coado", Money.de("4.50"));

        UUID vendaId = conta.comoUsuario(() ->
                vendaService.iniciar(sessaoId));
        conta.comoUsuario(() -> {
            vendaService.adicionarItem(vendaId, cafeId, BigDecimal.ONE, Money.ZERO);
            vendaService.registrarPagamento(vendaId,
                    SolicitacaoPagamento.emDinheiro(Money.de("4.50"), Money.de("4.50")));
            vendaService.concluir(vendaId);
        });
        // Espera o dinheiro entrar antes de fechar, senão o fechamento disputaria com o ouvinte.
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                conta.comoUsuario(() ->
                        assertThat(esperadoDe(sessaoId)).isEqualTo(Money.de("4.50"))));

        conta.comoUsuario(() -> caixas.fechar(sessaoId, Money.de("4.50")));

        // A gaveta já foi conferida; um estorno nela reescreveria a diferença apurada.
        assertThatIllegalStateException()
                .isThrownBy(() -> conta.comoUsuario(() ->
                        vendaService.cancelar(vendaId)))
                .withMessageContaining("nao esta ABERTA");

        conta.comoUsuario(() ->
                assertThat(vendas.findById(vendaId).orElseThrow().paraDominio().getStatus())
                        .isEqualTo(StatusVenda.CONCLUIDA));
        assertThat(eventos.stream(VendaCancelada.class)).as("nada foi publicado").isEmpty();
    }

    @Test
    @DisplayName("cancelar venda ABERTA não pergunta pelo caixa nem publica: é a saída da comanda presa")
    void cancelarVendaAbertaNaoPerguntaNemPublica(ApplicationEvents eventos) {
        ContaCriada conta = criador.criar("Padaria Central", SENHA_DE_TESTE);
        UUID sessaoId = abrirCaixa(conta);
        UUID cafeId = cadastrar(conta, "Cafe coado", Money.de("4.50"));

        UUID vendaId = conta.comoUsuario(() ->
                vendaService.iniciar(sessaoId));
        conta.comoUsuario(() -> {
            vendaService.adicionarItem(vendaId, cafeId, BigDecimal.ONE, Money.ZERO);
            vendaService.registrarPagamento(vendaId,
                    SolicitacaoPagamento.emDinheiro(Money.de("4.50"), Money.de("4.50")));
        });
        // O operador fecha o caixa com a comanda paga e ainda aberta: ela não conclui mais.
        conta.comoUsuario(() -> caixas.fechar(sessaoId, Money.ZERO));

        conta.comoUsuario(() -> vendaService.cancelar(vendaId));

        conta.comoUsuario(() -> {
            Venda gravada = vendas.findById(vendaId).orElseThrow().paraDominio();
            assertThat(gravada.getStatus()).isEqualTo(StatusVenda.CANCELADA);
            assertThat(gravada.getPagamentos())
                    .as("a parcela fica registrada como foi")
                    .extracting(Pagamento::status)
                    .containsExactly(StatusPagamento.CONFIRMADO);
            assertThat(sessoes.findById(sessaoId).orElseThrow().paraDominio().getMovimentos())
                    .as("a gaveta nunca mexeu por esta venda")
                    .isEmpty();
        });
        assertThat(eventos.stream(VendaCancelada.class))
                .as("uma venda ABERTA nunca produziu efeito fora do módulo: nada a desfazer")
                .isEmpty();
    }

    @Test
    @DisplayName("comprovante de venda CONCLUIDA traz linhas com nome de hoje, descontos, parcelas confirmadas e troco (RF11)")
    void comprovanteDeVendaConcluida() {
        ContaCriada conta = criador.criar("Emporio da Serra", SENHA_DE_TESTE);
        UUID sessaoId = abrirCaixa(conta);
        UUID cafeId = cadastrar(conta, "Cafe coado", Money.de("4.50"));
        UUID queijoId = conta.comoUsuario(() ->
                produtos.cadastrar(TipoProduto.PRODUTO, new DadosDoProduto("Queijo minas",
                        Money.de("39.90"), null, null, "kg", null)));

        UUID vendaId = conta.comoUsuario(() ->
                vendaService.iniciar(sessaoId));
        conta.comoUsuario(() -> {
            // 2 x 4,50 = 9,00; 0,750 x 39,90 = 29,925, que vira 29,93 na linha, menos 1,93 =
            // 28,00. Soma dos itens 37,00; desconto da venda 2,00; total 35,00.
            vendaService.adicionarItem(vendaId, cafeId, new BigDecimal("2"), Money.ZERO);
            vendaService.adicionarItem(vendaId, queijoId, new BigDecimal("0.750"),
                    Money.de("1.93"));
            vendaService.aplicarDesconto(vendaId, Money.de("2.00"));
            vendaService.registrarPagamento(vendaId,
                    SolicitacaoPagamento.de(FormaPagamento.CARTAO, Money.de("15.00")));
            vendaService.registrarPagamento(vendaId,
                    SolicitacaoPagamento.emDinheiro(Money.de("20.00"), Money.de("50.00")));
            vendaService.concluir(vendaId);
        });

        Comprovante comprovante = conta.comoUsuario(() ->
                vendaService.comprovante(vendaId));
        Venda gravada = conta.comoUsuario(() ->
                vendas.findById(vendaId).orElseThrow().paraDominio());

        assertThat(comprovante.vendaId()).isEqualTo(vendaId);
        assertThat(comprovante.usuarioId()).isEqualTo(conta.usuarioId());
        assertThat(comprovante.concluidoEm()).isEqualTo(gravada.getConcluidoEm());

        // As linhas saem na ordem em que entraram, com bruto e subtotal já arredondados por item.
        // A quantidade volta do banco com três casas, então a comparação é numérica.
        assertThat(comprovante.linhas()).satisfiesExactly(
                cafe -> {
                    assertThat(cafe.produtoId()).isEqualTo(cafeId);
                    assertThat(cafe.nome()).isEqualTo("Cafe coado");
                    assertThat(cafe.unidade()).isEqualTo("un");
                    assertThat(cafe.quantidade()).isEqualByComparingTo("2");
                    assertThat(cafe.precoUnitario()).isEqualTo(Money.de("4.50"));
                    assertThat(cafe.valorBruto()).isEqualTo(Money.de("9.00"));
                    assertThat(cafe.desconto()).isEqualTo(Money.ZERO);
                    assertThat(cafe.subtotal()).isEqualTo(Money.de("9.00"));
                },
                queijo -> {
                    assertThat(queijo.produtoId()).isEqualTo(queijoId);
                    assertThat(queijo.nome()).isEqualTo("Queijo minas");
                    assertThat(queijo.unidade()).isEqualTo("kg");
                    assertThat(queijo.quantidade()).isEqualByComparingTo("0.750");
                    assertThat(queijo.precoUnitario()).isEqualTo(Money.de("39.90"));
                    assertThat(queijo.valorBruto()).isEqualTo(Money.de("29.93"));
                    assertThat(queijo.desconto()).isEqualTo(Money.de("1.93"));
                    assertThat(queijo.subtotal()).isEqualTo(Money.de("28.00"));
                });
        assertThat(comprovante.somaDosItens()).isEqualTo(Money.de("37.00"));
        assertThat(comprovante.descontoDaVenda()).isEqualTo(Money.de("2.00"));
        assertThat(comprovante.valorTotal()).isEqualTo(Money.de("35.00"));

        assertThat(comprovante.parcelas())
                .extracting(Comprovante.Parcela::forma, Comprovante.Parcela::valor,
                        Comprovante.Parcela::troco)
                .containsExactly(
                        tuple(FormaPagamento.CARTAO, Money.de("15.00"), Money.ZERO),
                        tuple(FormaPagamento.DINHEIRO, Money.de("20.00"), Money.de("30.00")));
        assertThat(comprovante.troco()).isEqualTo(Money.de("30.00"));
    }

    @Test
    @DisplayName("só venda CONCLUIDA tem comprovante: ABERTA e CANCELADA são recusadas")
    void comprovanteExigeVendaConcluida() {
        ContaCriada conta = criador.criar("Cafeteria Aurora", SENHA_DE_TESTE);
        UUID sessaoId = abrirCaixa(conta);
        UUID cafeId = cadastrar(conta, "Cafe coado", Money.de("4.50"));

        UUID aberta = conta.comoUsuario(() ->
                vendaService.iniciar(sessaoId));
        conta.comoUsuario(() ->
                vendaService.adicionarItem(aberta, cafeId, BigDecimal.ONE, Money.ZERO));

        // A comanda ainda muda: o que se imprimisse agora não seria prova de nada.
        assertThatIllegalStateException()
                .isThrownBy(() -> conta.comoUsuario(() ->
                        vendaService.comprovante(aberta)))
                .withMessageContaining("ABERTA")
                .withMessageContaining("nao tem comprovante");

        // A comanda abandonada vira CANCELADA sem evento nenhum, e também não tem comprovante.
        conta.comoUsuario(() -> vendaService.cancelar(aberta));
        assertThatIllegalStateException()
                .isThrownBy(() -> conta.comoUsuario(() ->
                        vendaService.comprovante(aberta)))
                .withMessageContaining("CANCELADA");
    }

    @Test
    @DisplayName("comprovante não enxerga venda de outra conta (RNF05)")
    void comprovanteNaoEnxergaVendaDeOutraConta() {
        ContaCriada contaA = criador.criar("Loja A", SENHA_DE_TESTE);
        ContaCriada contaB = criador.criar("Loja B", SENHA_DE_TESTE);
        UUID vendaDaContaA = vendaConcluidaSimples(contaA);

        // Para a conta B, a venda concluída da conta A é indistinguível de um id que nunca
        // existiu: a recusa vem antes de qualquer pergunta ao cadastro.
        assertThatExceptionOfType(VendaNaoEncontradaException.class)
                .isThrownBy(() -> contaB.comoUsuario(() ->
                        vendaService.comprovante(vendaDaContaA)));

        contaA.comoUsuario(() ->
                assertThat(vendaService.comprovante(vendaDaContaA).linhas()).hasSize(1));
    }

    @Test
    @DisplayName("comprovante mostra o nome de hoje do produto, e sai mesmo com o produto inativado")
    void comprovanteMostraONomeAtualDoProduto() {
        ContaCriada conta = criador.criar("Mercearia do Vale", SENHA_DE_TESTE);
        UUID vendaId = vendaConcluidaSimples(conta);
        UUID cafeId = conta.comoUsuario(() ->
                vendaService.comprovante(vendaId).linhas().get(0).produtoId());

        // O item guarda o preço copiado, mas não o nome: renomear e reajustar depois da venda
        // muda o nome impresso e não muda o preço. É o custo aceito de não copiar o nome.
        conta.comoUsuario(() -> {
            produtos.editar(cafeId, new DadosDoProduto("Cafe especial", Money.de("6.00"), null,
                    null, "xic", null));
            produtos.inativar(cafeId);
        });

        Comprovante reimpresso = conta.comoUsuario(() ->
                vendaService.comprovante(vendaId));
        assertThat(reimpresso.linhas()).singleElement().satisfies(linha -> {
            assertThat(linha.nome()).isEqualTo("Cafe especial");
            assertThat(linha.unidade()).isEqualTo("xic");
            assertThat(linha.precoUnitario()).isEqualTo(Money.de("4.50"));
            assertThat(linha.subtotal()).isEqualTo(Money.de("4.50"));
        });
        assertThat(reimpresso.valorTotal()).isEqualTo(Money.de("4.50"));
    }

    @Test
    @DisplayName("o operador não vende no caixa do colega: a pergunta ao caixa é recusada pelo caixa")
    void operadorNaoVendeNoCaixaDoColega() {
        ContaCriada conta = criador.criar("Mercado com Dois Caixas", SENHA_DE_TESTE);
        UsuarioCriado daManha = criador.criarOperadorEm(conta.contaId(), "Atendente da manha");
        UsuarioCriado daTarde = criador.criarOperadorEm(conta.contaId(), "Atendente da tarde");
        UUID caixaDaManha = daManha.comoUsuario(() -> caixas.abrir(Money.ZERO));

        assertThatExceptionOfType(AcessoNegadoException.class).isThrownBy(() ->
                daTarde.comoUsuario(() -> vendaService.iniciar(caixaDaManha)));

        UUID vendaDaManha = daManha.comoUsuario(() -> vendaService.iniciar(caixaDaManha));
        daManha.comoUsuario(() ->
                assertThat(vendas.findById(vendaDaManha).orElseThrow().paraDominio().getUsuarioId())
                        .as("o operador da venda é quem está no contexto")
                        .isEqualTo(daManha.usuarioId()));
    }

    @Test
    @DisplayName("o operador não toca a venda do colega por nenhum caso de uso; o administrador toca")
    void operadorNaoTocaAVendaDoColega() {
        ContaCriada conta = criador.criar("Cafeteria com Dois Turnos", SENHA_DE_TESTE);
        UsuarioCriado daManha = criador.criarOperadorEm(conta.contaId(), "Atendente da manha");
        UsuarioCriado daTarde = criador.criarOperadorEm(conta.contaId(), "Atendente da tarde");
        UUID caixaDaManha = daManha.comoUsuario(() -> caixas.abrir(Money.ZERO));
        UUID cafeId = cadastrar(conta, "Cafe coado", Money.de("4.50"));

        UUID vendaDaManha = daManha.comoUsuario(() -> {
            UUID vendaId = vendaService.iniciar(caixaDaManha);
            vendaService.adicionarItem(vendaId, cafeId, BigDecimal.ONE, Money.ZERO);
            return vendaId;
        });

        daTarde.comoUsuario(() -> {
            assertThatExceptionOfType(AcessoNegadoException.class).isThrownBy(() ->
                    vendaService.adicionarItem(vendaDaManha, cafeId, BigDecimal.ONE, Money.ZERO));
            assertThatExceptionOfType(AcessoNegadoException.class).isThrownBy(() ->
                    vendaService.removerItem(vendaDaManha, UUID.randomUUID()));
            assertThatExceptionOfType(AcessoNegadoException.class).isThrownBy(() ->
                    vendaService.aplicarDesconto(vendaDaManha, Money.ZERO));
            assertThatExceptionOfType(AcessoNegadoException.class).isThrownBy(() ->
                    vendaService.registrarPagamento(vendaDaManha,
                            SolicitacaoPagamento.emDinheiro(Money.de("4.50"), Money.de("4.50"))));
            assertThatExceptionOfType(AcessoNegadoException.class).isThrownBy(() ->
                    vendaService.concluir(vendaDaManha));
            assertThatExceptionOfType(AcessoNegadoException.class).isThrownBy(() ->
                    vendaService.cancelar(vendaDaManha));
            assertThatExceptionOfType(AcessoNegadoException.class).isThrownBy(() ->
                    vendaService.comprovante(vendaDaManha));
        });

        // O dono conclui, tira o comprovante e cancela a venda do atendente.
        conta.comoUsuario(() -> {
            vendaService.registrarPagamento(vendaDaManha,
                    SolicitacaoPagamento.emDinheiro(Money.de("4.50"), Money.de("4.50")));
            vendaService.concluir(vendaDaManha);
            assertThat(vendaService.comprovante(vendaDaManha).usuarioId())
                    .isEqualTo(daManha.usuarioId());
            vendaService.cancelar(vendaDaManha);
            assertThat(vendas.findById(vendaDaManha).orElseThrow().paraDominio().getStatus())
                    .isEqualTo(StatusVenda.CANCELADA);
        });
    }

    private UUID abrirCaixa(ContaCriada conta) {
        return conta.comoUsuario(() ->
                caixas.abrir(Money.ZERO));
    }

    /** Um café a 4,50, pago em dinheiro exato e concluído: o mínimo que tem comprovante. */
    private UUID vendaConcluidaSimples(ContaCriada conta) {
        UUID sessaoId = abrirCaixa(conta);
        UUID cafeId = cadastrar(conta, "Cafe coado", Money.de("4.50"));
        return conta.comoUsuario(() -> {
            UUID vendaId = vendaService.iniciar(sessaoId);
            vendaService.adicionarItem(vendaId, cafeId, BigDecimal.ONE, Money.ZERO);
            vendaService.registrarPagamento(vendaId,
                    SolicitacaoPagamento.emDinheiro(Money.de("4.50"), Money.de("4.50")));
            vendaService.concluir(vendaId);
            return vendaId;
        });
    }

    private UUID cadastrar(ContaCriada conta, String nome, Money preco) {
        return conta.comoUsuario(() ->
                produtos.cadastrar(TipoProduto.PRODUTO,
                        new DadosDoProduto(nome, preco, null, null, "un", null)));
    }

    /** O que o ouvinte do caixa gravou, lido pelo repositório dele. */
    private Money esperadoDe(UUID sessaoId) {
        return sessoes.findById(sessaoId).orElseThrow().paraDominio()
                .getValorFechamentoEsperado();
    }

    /** O que o ouvinte do estoque gravou, lido pelo repositório do cadastro. */
    private BigDecimal saldoDe(UUID produtoId) {
        return linhasDeProduto.findById(produtoId).orElseThrow().paraDominio().getEstoqueAtual();
    }
}
