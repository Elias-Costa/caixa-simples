package br.com.caixasimples.vendas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.Assertions.within;

import br.com.caixasimples.TesteDeIntegracao;
import br.com.caixasimples.cadastro.TipoProduto;
import br.com.caixasimples.cadastro.application.ProdutoService;
import br.com.caixasimples.cadastro.application.ProdutoService.DadosDoProduto;
import br.com.caixasimples.cadastro.internal.ClienteService;
import br.com.caixasimples.cadastro.internal.ClienteService.DadosDoCliente;
import br.com.caixasimples.caixa.application.SessaoCaixaService;
import br.com.caixasimples.contas.CriadorDeContaDeTeste;
import br.com.caixasimples.contas.CriadorDeContaDeTeste.ContaCriada;
import br.com.caixasimples.pagamentos.FormaPagamento;
import br.com.caixasimples.pagamentos.StatusPagamento;
import br.com.caixasimples.shared.Money;
import br.com.caixasimples.shared.TenantContext;
import br.com.caixasimples.vendas.domain.ItemVenda;
import br.com.caixasimples.vendas.domain.Pagamento;
import br.com.caixasimples.vendas.domain.Venda;
import br.com.caixasimples.vendas.internal.VendaEntity;
import br.com.caixasimples.vendas.internal.VendaRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Isolamento entre contas (RNF05) para {@code venda}, no mesmo molde de
 * {@code IsolamentoDeSessaoCaixaTest}: grava na conta A, consulta como conta B e espera vazio.
 *
 * <p>Em nenhuma linha abaixo existe {@code WHERE conta_id}. É o {@code @TenantId} do Hibernate que
 * filtra, e se ele sair de {@code VendaEntity} este teste quebra, que é exatamente o ponto dele.
 *
 * <p>Este arquivo fica no pacote {@code vendas} e só enxerga o que um controller enxergaria: a raiz
 * do agregado e o domínio. O {@code conta_id} dos <em>membros</em> não se vê daqui, e por isso
 * existe um segundo teste em {@code vendas.internal}.
 *
 * <p>A venda referencia sessão de caixa, operador e produto por chave estrangeira, então cada
 * cenário abre um caixa e cadastra um produto pelos casos de uso dos módulos donos, que é o
 * caminho que um controller tomaria. Como a raiz ainda não tem caminho de escrita, a venda em si
 * é montada por {@code Venda.reconstituir}, do mesmo modo que os testes do índice da V6 e da
 * delimitação do dia gravam a sessão de caixa.
 */
class IsolamentoDeVendaTest extends TesteDeIntegracao {

    private static final String SENHA_DE_TESTE = "uma senha longa de teste";

    @Autowired
    private VendaRepository vendas;

    @Autowired
    private SessaoCaixaService caixas;

    @Autowired
    private ProdutoService produtos;

    @Autowired
    private ClienteService clientes;

    @Autowired
    private CriadorDeContaDeTeste criador;

    @AfterEach
    void limparContexto() {
        TenantContext.limpar();
    }

    @Test
    @DisplayName("conta B não enxerga venda da conta A por nenhum caminho de consulta")
    void contaNaoEnxergaVendaDeOutraConta() {
        ContaCriada contaA = criador.criar("Cafeteria Aurora", SENHA_DE_TESTE);
        ContaCriada contaB = criador.criar("Salao Vizinho", SENHA_DE_TESTE);
        Cenario cenarioA = prepararCenario(contaA);

        UUID vendaDaContaA = TenantContext.executarComo(contaA.contaId(), () ->
                vendas.save(VendaEntity.de(vendaSimples(cenarioA, null))).getId());

        TenantContext.executarComo(contaB.contaId(), () -> {
            assertThat(vendas.findById(vendaDaContaA))
                    .as("findById atravessando tenant")
                    .isEmpty();
            assertThat(vendas.findAll())
                    .as("listagem da conta B")
                    .extracting(VendaEntity::getId)
                    .doesNotContain(vendaDaContaA);
        });

        // E a conta A continua vendo o próprio dado: o filtro não pode se esconder de todos.
        TenantContext.executarComo(contaA.contaId(), () -> {
            assertThat(vendas.findById(vendaDaContaA)).isPresent();
            assertThat(vendas.findAll())
                    .extracting(VendaEntity::getId)
                    .containsExactly(vendaDaContaA);
        });
    }

    @Test
    @DisplayName("contaId de uma venda vem do contexto, nunca de parâmetro")
    void contaIdEPreenchidoPeloContextoDeTenant() {
        ContaCriada conta = criador.criar("Loja Teste", SENHA_DE_TESTE);
        Cenario cenario = prepararCenario(conta);

        // Repare que nem reconstituir nem o save recebem a conta: não existe assinatura por onde
        // um chamador pudesse informá-la (RNF05).
        UUID vendaId = TenantContext.executarComo(conta.contaId(), () ->
                vendas.save(VendaEntity.de(vendaSimples(cenario, null))).getId());

        TenantContext.executarComo(conta.contaId(), () ->
                assertThat(vendas.findById(vendaId))
                        .get()
                        .extracting(VendaEntity::getContaId)
                        .isEqualTo(conta.contaId()));
    }

    @Test
    @DisplayName("o agregado inteiro volta do banco com o mesmo estado que entrou")
    void agregadoSobreviveAoIdaEVolta() {
        ContaCriada conta = criador.criar("Padaria Teste", SENHA_DE_TESTE);
        Cenario cenario = prepararCenario(conta);

        ItemVenda inteiro = new ItemVenda(UUID.randomUUID(), cenario.produtoId(),
                new BigDecimal("2"), Money.de("4.50"), Money.ZERO, Instant.now());
        ItemVenda fracionado = new ItemVenda(UUID.randomUUID(), cenario.produtoId(),
                new BigDecimal("0.750"), Money.de("39.90"), Money.de("1.00"), Instant.now());
        Pagamento emDinheiro = new Pagamento(UUID.randomUUID(), FormaPagamento.DINHEIRO,
                Money.de("20.00"), StatusPagamento.CONFIRMADO, Instant.now());
        Pagamento emPix = new Pagamento(UUID.randomUUID(), FormaPagamento.PIX, Money.de("16.93"),
                StatusPagamento.PENDENTE, Instant.now());

        Venda original = Venda.reconstituir(UUID.randomUUID(), cenario.sessaoCaixaId(),
                conta.usuarioId(), null, StatusVenda.ABERTA, Money.de("36.93"), Money.de("1.00"),
                Instant.now(), List.of(inteiro, fracionado), List.of(emDinheiro, emPix));

        // Uma chamada de save grava a raiz, os dois itens e os dois pagamentos: o agregado é a
        // unidade transacional, e é o cascade de VendaEntity que faz isso valer.
        TenantContext.executarComo(conta.contaId(), () ->
                vendas.save(VendaEntity.de(original)));

        TenantContext.executarComo(conta.contaId(), () -> {
            Venda lida = vendas.findById(original.getId()).orElseThrow().paraDominio();

            assertThat(lida.getId()).isEqualTo(original.getId());
            assertThat(lida.getSessaoCaixaId()).isEqualTo(cenario.sessaoCaixaId());
            assertThat(lida.getUsuarioId()).isEqualTo(conta.usuarioId());
            assertThat(lida.getClienteId()).isNull();
            assertThat(lida.getStatus()).isEqualTo(StatusVenda.ABERTA);
            assertThat(lida.getValorTotal()).isEqualTo(Money.de("36.93"));
            assertThat(lida.getValorDesconto()).isEqualTo(Money.de("1.00"));

            // O banco guarda microssegundos e arredonda; o relógio da JVM tem mais casas do que
            // isso. Igualdade exata falharia ao acaso, conforme os dígitos do instante.
            assertThat(lida.getCriadoEm())
                    .isCloseTo(original.getCriadoEm(), within(1, ChronoUnit.MICROS));

            // A quantidade volta do banco com três casas (numeric(12,3)), então 2 vira 2.000, e
            // BigDecimal.equals compara escala. A comparação numérica é a que interessa.
            assertThat(lida.getItens())
                    .extracting(ItemVenda::id, ItemVenda::produtoId, ItemVenda::precoUnitario,
                            ItemVenda::desconto)
                    .containsExactlyInAnyOrder(
                            tuple(inteiro.id(), cenario.produtoId(), Money.de("4.50"), Money.ZERO),
                            tuple(fracionado.id(), cenario.produtoId(), Money.de("39.90"),
                                    Money.de("1.00")));
            assertThat(lida.getItens())
                    .extracting(ItemVenda::quantidade)
                    .usingElementComparator(BigDecimal::compareTo)
                    .containsExactlyInAnyOrder(new BigDecimal("2"), new BigDecimal("0.750"));

            assertThat(lida.getPagamentos())
                    .extracting(Pagamento::id, Pagamento::forma, Pagamento::valor,
                            Pagamento::status)
                    .containsExactlyInAnyOrder(
                            tuple(emDinheiro.id(), FormaPagamento.DINHEIRO, Money.de("20.00"),
                                    StatusPagamento.CONFIRMADO),
                            tuple(emPix.id(), FormaPagamento.PIX, Money.de("16.93"),
                                    StatusPagamento.PENDENTE));

            // O @OrderBy das duas coleções, afirmado sem depender de empate de relógio: duas
            // chamadas seguidas de Instant.now() podem cair no mesmo microssegundo, e uma
            // comparação posicional viraria teste intermitente por causa disso.
            assertThat(lida.getItens()).extracting(ItemVenda::criadoEm).isSorted();
            assertThat(lida.getPagamentos()).extracting(Pagamento::criadoEm).isSorted();
        });
    }

    @Test
    @DisplayName("venda com cliente identificado guarda a referência e a devolve (RF03)")
    void vendaComClienteAtravessaOBanco() {
        ContaCriada conta = criador.criar("Barbearia Teste", SENHA_DE_TESTE);
        Cenario cenario = prepararCenario(conta);
        UUID clienteId = TenantContext.executarComo(conta.contaId(), () ->
                clientes.cadastrar(new DadosDoCliente("Cliente Habitual", null)));

        UUID vendaId = TenantContext.executarComo(conta.contaId(), () ->
                vendas.save(VendaEntity.de(vendaSimples(cenario, clienteId))).getId());

        TenantContext.executarComo(conta.contaId(), () ->
                assertThat(vendas.findById(vendaId).orElseThrow().paraDominio().getClienteId())
                        .isEqualTo(clienteId));
    }

    /**
     * Abre um caixa para o operador da conta e cadastra um produto, porque a venda referencia os
     * dois por chave estrangeira e o banco recusaria uma venda apontando para o nada.
     */
    private Cenario prepararCenario(ContaCriada conta) {
        return TenantContext.executarComo(conta.contaId(), () -> {
            UUID sessaoCaixaId = caixas.abrir(conta.usuarioId(), Money.ZERO);
            UUID produtoId = produtos.cadastrar(TipoProduto.PRODUTO, new DadosDoProduto(
                    "Cafe coado", Money.de("4.50"), null, null, "un", null));
            return new Cenario(conta.usuarioId(), sessaoCaixaId, produtoId);
        });
    }

    /** Uma venda ABERTA de um item só, para os cenários em que o conteúdo não importa. */
    private static Venda vendaSimples(Cenario cenario, UUID clienteId) {
        ItemVenda item = new ItemVenda(UUID.randomUUID(), cenario.produtoId(), BigDecimal.ONE,
                Money.de("4.50"), Money.ZERO, Instant.now());
        return Venda.reconstituir(UUID.randomUUID(), cenario.sessaoCaixaId(), cenario.usuarioId(),
                clienteId, StatusVenda.ABERTA, Money.de("4.50"), Money.ZERO, Instant.now(),
                List.of(item), List.of());
    }

    private record Cenario(UUID usuarioId, UUID sessaoCaixaId, UUID produtoId) {
    }
}
