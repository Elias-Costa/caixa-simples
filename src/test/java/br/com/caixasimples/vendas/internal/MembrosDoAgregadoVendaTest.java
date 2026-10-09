package br.com.caixasimples.vendas.internal;

import static org.assertj.core.api.Assertions.assertThat;

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
import br.com.caixasimples.vendas.StatusVenda;
import br.com.caixasimples.vendas.domain.ItemVenda;
import br.com.caixasimples.vendas.domain.Pagamento;
import br.com.caixasimples.vendas.domain.Recebimento;
import br.com.caixasimples.vendas.domain.Venda;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Isolamento entre contas (RNF05) para {@code item_venda}, {@code pagamento} e
 * {@code recebimento}, os <strong>membros</strong> do agregado Venda.
 *
 * <p>Este arquivo está em {@code vendas.internal} de propósito, pelo mesmo motivo do teste
 * equivalente do caixa: as entidades dos membros têm visibilidade de pacote, ninguém de fora
 * consegue nomear os tipos, e a única coisa que o caminho público <em>não</em> consegue mostrar é
 * justamente o {@code conta_id} de cada membro. Pelo domínio, o teste não distinguiria um item com
 * a conta certa de um com a coluna errada.
 *
 * <p>A entidade do recebimento não expõe a coluna, e a raiz não expõe a lista de entidades dele;
 * a conta do recebimento é lida por SQL, pela chave, o que responde à mesma pergunta sem um acessor
 * de produção que só o teste usaria.
 *
 * <p>O par disso é o {@code IsolamentoDeVendaTest}, no pacote {@code vendas}, que cobre a raiz
 * enxergando só o que um controller enxergaria.
 */
class MembrosDoAgregadoVendaTest extends TesteDeIntegracao {

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

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void limparContexto() {
        TenantContext.limpar();
    }

    @Test
    @DisplayName("item, pagamento e recebimento herdam a conta da raiz, também pelo contexto e nunca por parâmetro")
    void membrosRecebemOMesmoTenantDaRaiz() {
        ContaCriada conta = criador.criar("Mercearia Teste", SENHA_DE_TESTE);
        Venda venda = vendaFiadaComRecebimento(conta);
        UUID recebimentoId = venda.getRecebimentos().getFirst().id();

        conta.comoUsuario(() -> vendas.save(VendaEntity.de(venda)));

        conta.comoUsuario(() -> {
            VendaEntity gravada = vendas.findById(venda.getId()).orElseThrow();

            assertThat(gravada.getItens())
                    .singleElement()
                    .extracting(ItemVendaEntity::getContaId)
                    .as("item_venda tem conta_id próprio, vindo do @TenantId, e não de JOIN com a"
                            + " venda nem de parâmetro de chamada")
                    .isEqualTo(conta.contaId());

            assertThat(gravada.getPagamentos())
                    .singleElement()
                    .extracting(PagamentoEntity::getContaId)
                    .as("pagamento tem conta_id próprio, pelo mesmo caminho")
                    .isEqualTo(conta.contaId());

            assertThat(gravada.paraDominio().getRecebimentos())
                    .singleElement()
                    .extracting(Recebimento::id)
                    .as("o recebimento volta pela raiz")
                    .isEqualTo(recebimentoId);
        });

        // RecebimentoEntity não recebe a conta no construtor: só o @TenantId preenche a coluna.
        assertThat(jdbc.queryForObject("SELECT conta_id FROM recebimento WHERE id = ?",
                UUID.class, recebimentoId))
                .as("recebimento tem conta_id próprio, pelo mesmo caminho")
                .isEqualTo(conta.contaId().valor());
    }

    @Test
    @DisplayName("conta B não alcança item, pagamento nem recebimento da conta A nem pela raiz do agregado")
    void contaNaoEnxergaMembrosDeOutraConta() {
        ContaCriada contaA = criador.criar("Bar do Teste", SENHA_DE_TESTE);
        ContaCriada contaB = criador.criar("Oficina Teste", SENHA_DE_TESTE);
        Venda vendaDaContaA = vendaFiadaComRecebimento(contaA);

        contaA.comoUsuario(() ->
                vendas.save(VendaEntity.de(vendaDaContaA)));

        // A conta A lê os três membros pela raiz; sem isso, o vazio da conta B não provaria nada.
        contaA.comoUsuario(() -> {
            Venda gravada = vendas.findById(vendaDaContaA.getId()).orElseThrow().paraDominio();
            assertThat(gravada.getItens()).hasSize(1);
            assertThat(gravada.getPagamentos()).hasSize(1);
            assertThat(gravada.getRecebimentos()).hasSize(1);
        });

        // Como não existe repositório para os membros do agregado, a única porta para eles é a
        // raiz, e a raiz já está fechada para a conta B. Esse é o desenho: menos um caminho de
        // consulta é menos um lugar onde o filtro poderia faltar.
        contaB.comoUsuario(() ->
                assertThat(vendas.findById(vendaDaContaA.getId())).isEmpty());
    }

    /**
     * Uma venda fiada e concluída, com um item, a parcela FIADO e um recebimento parcial, apontando
     * para um caixa aberto, um produto e um cliente reais da conta, porque as chaves estrangeiras
     * exigem os três. É o único estado com os três membros: o recebimento só existe em venda
     * concluída com fiado, e a venda fiada concluída exige cliente.
     */
    private Venda vendaFiadaComRecebimento(ContaCriada conta) {
        return conta.comoUsuario(() -> {
            UUID sessaoCaixaId = caixas.abrir(Money.ZERO);
            UUID produtoId = produtos.cadastrar(TipoProduto.SERVICO, new DadosDoProduto(
                    "Corte simples", Money.de("30.00"), null, null, null, null));
            UUID clienteId = clientes.cadastrar(new DadosDoCliente("Lia", null));
            Instant agora = Instant.now();

            ItemVenda item = new ItemVenda(UUID.randomUUID(), produtoId, BigDecimal.ONE,
                    Money.de("30.00"), Money.ZERO, agora);
            Pagamento fiado = new Pagamento(UUID.randomUUID(), FormaPagamento.FIADO,
                    Money.de("30.00"), StatusPagamento.PENDENTE, Money.ZERO, agora);
            Recebimento recebimento = new Recebimento(UUID.randomUUID(), sessaoCaixaId,
                    Money.de("10.00"), FormaPagamento.DINHEIRO, agora);

            return Venda.reconstituir(UUID.randomUUID(), sessaoCaixaId, conta.usuarioId(),
                    clienteId, StatusVenda.CONCLUIDA, Money.de("30.00"), Money.ZERO, agora, agora,
                    List.of(item), List.of(fiado), List.of(recebimento));
        });
    }
}
