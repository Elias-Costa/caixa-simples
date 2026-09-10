package br.com.caixasimples.vendas.internal;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.caixasimples.TesteDeIntegracao;
import br.com.caixasimples.cadastro.TipoProduto;
import br.com.caixasimples.cadastro.application.ProdutoService;
import br.com.caixasimples.cadastro.application.ProdutoService.DadosDoProduto;
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
import br.com.caixasimples.vendas.domain.Venda;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Isolamento entre contas (RNF05) para {@code item_venda} e {@code pagamento}, os
 * <strong>membros</strong> do agregado Venda.
 *
 * <p>Este arquivo está em {@code vendas.internal} de propósito, pelo mesmo motivo do teste
 * equivalente do caixa: as entidades dos membros têm visibilidade de pacote, ninguém de fora
 * consegue nomear os tipos, e a única coisa que o caminho público <em>não</em> consegue mostrar é
 * justamente o {@code conta_id} de cada membro. Pelo domínio, o teste não distinguiria um item com
 * a conta certa de um com a coluna errada.
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
    private CriadorDeContaDeTeste criador;

    @AfterEach
    void limparContexto() {
        TenantContext.limpar();
    }

    @Test
    @DisplayName("item e pagamento herdam a conta da raiz, também pelo contexto e nunca por parâmetro")
    void membrosRecebemOMesmoTenantDaRaiz() {
        ContaCriada conta = criador.criar("Mercearia Teste", SENHA_DE_TESTE);
        Venda venda = vendaCompleta(conta);

        TenantContext.executarComo(conta.contaId(), () -> vendas.save(VendaEntity.de(venda)));

        TenantContext.executarComo(conta.contaId(), () -> {
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
        });
    }

    @Test
    @DisplayName("conta B não alcança item nem pagamento da conta A nem pela raiz do agregado")
    void contaNaoEnxergaMembrosDeOutraConta() {
        ContaCriada contaA = criador.criar("Bar do Teste", SENHA_DE_TESTE);
        ContaCriada contaB = criador.criar("Oficina Teste", SENHA_DE_TESTE);
        Venda vendaDaContaA = vendaCompleta(contaA);

        TenantContext.executarComo(contaA.contaId(), () ->
                vendas.save(VendaEntity.de(vendaDaContaA)));

        // Como não existe repositório para os membros do agregado, a única porta para eles é a
        // raiz, e a raiz já está fechada para a conta B. Esse é o desenho: menos um caminho de
        // consulta é menos um lugar onde o filtro poderia faltar.
        TenantContext.executarComo(contaB.contaId(), () ->
                assertThat(vendas.findById(vendaDaContaA.getId())).isEmpty());
    }

    /**
     * Uma venda com um item e um pagamento, apontando para um caixa aberto e um produto reais da
     * conta, porque as chaves estrangeiras exigem que os dois existam.
     */
    private Venda vendaCompleta(ContaCriada conta) {
        return TenantContext.executarComo(conta.contaId(), () -> {
            UUID sessaoCaixaId = caixas.abrir(conta.usuarioId(), Money.ZERO);
            UUID produtoId = produtos.cadastrar(TipoProduto.SERVICO, new DadosDoProduto(
                    "Corte simples", Money.de("30.00"), null, null, null, null));

            ItemVenda item = new ItemVenda(UUID.randomUUID(), produtoId, BigDecimal.ONE,
                    Money.de("30.00"), Money.ZERO, Instant.now());
            Pagamento pagamento = new Pagamento(UUID.randomUUID(), FormaPagamento.CARTAO,
                    Money.de("30.00"), StatusPagamento.CONFIRMADO, Instant.now());

            return Venda.reconstituir(UUID.randomUUID(), sessaoCaixaId, conta.usuarioId(), null,
                    StatusVenda.ABERTA, Money.de("30.00"), Money.ZERO, Instant.now(),
                    List.of(item), List.of(pagamento));
        });
    }
}
