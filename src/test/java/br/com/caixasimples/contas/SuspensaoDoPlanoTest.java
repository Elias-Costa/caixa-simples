package br.com.caixasimples.contas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import br.com.caixasimples.TesteDeIntegracao;
import br.com.caixasimples.cadastro.TipoProduto;
import br.com.caixasimples.cadastro.application.ProdutoService;
import br.com.caixasimples.cadastro.application.ProdutoService.DadosDoProduto;
import br.com.caixasimples.cadastro.application.ProdutoService.EstoqueDoProduto;
import br.com.caixasimples.caixa.application.SessaoCaixaService;
import br.com.caixasimples.contas.CriadorDeContaDeTeste.ContaCriada;
import br.com.caixasimples.contas.application.PlanoSuspensoException;
import br.com.caixasimples.contas.application.RecursoForaDoPlanoException;
import br.com.caixasimples.estoque.application.EstoqueService;
import br.com.caixasimples.pagamentos.FormaPagamento;
import br.com.caixasimples.pagamentos.domain.SolicitacaoPagamento;
import br.com.caixasimples.relatorios.application.FaturamentoService;
import br.com.caixasimples.shared.AcessoNegadoException;
import br.com.caixasimples.shared.FusoDeReferencia;
import br.com.caixasimples.shared.Money;
import br.com.caixasimples.shared.Perfil;
import br.com.caixasimples.shared.TenantContext;
import br.com.caixasimples.shared.UsuarioContext;
import br.com.caixasimples.vendas.application.VendaService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * O que o plano libera e o que a suspensão para, perguntado aos módulos donos dos recursos.
 *
 * <p>Relatórios e estoque perguntam o plano depois do perfil. A suspensão, oito dias depois do
 * vencimento sem renovação, para só o que é pago: o balcão segue vendendo, com caixa, cadastro e a
 * baixa de estoque pela Venda, que não pergunta o plano para o saldo não ficar errado.
 */
class SuspensaoDoPlanoTest extends TesteDeIntegracao {

    private static final String SENHA_DE_TESTE = "uma senha longa de teste";

    @Autowired
    private CriadorDeContaDeTeste criador;

    @Autowired
    private FaturamentoService faturamento;

    @Autowired
    private EstoqueService estoque;

    @Autowired
    private ProdutoService produtos;

    @Autowired
    private SessaoCaixaService caixa;

    @Autowired
    private VendaService vendas;

    @AfterEach
    void limparContexto() {
        TenantContext.limpar();
        UsuarioContext.limpar();
    }

    @Test
    @DisplayName("a Conta nova, no plano grátis, não tem relatório nem estoque; o operador recebe antes a recusa de perfil")
    void planoGratisSemRecursoPago() {
        ContaCriada conta = criador.criar("Cafeteria Aurora", SENHA_DE_TESTE);
        ContaCriada operador = criador.criar("Cafeteria do Operador", SENHA_DE_TESTE,
                Perfil.OPERADOR, true);

        assertThatExceptionOfType(RecursoForaDoPlanoException.class)
                .isThrownBy(() -> conta.comoUsuario(() -> faturamento.doDia(hoje())))
                .withMessageContaining("Caixa Simples");
        assertThatExceptionOfType(RecursoForaDoPlanoException.class)
                .isThrownBy(() -> conta.comoUsuario(estoque::produtos))
                .withMessageContaining("plano Completo");
        assertThatExceptionOfType(AcessoNegadoException.class)
                .isThrownBy(() -> operador.comoUsuario(() -> faturamento.doDia(hoje())));

        criador.contratar(conta.contaId(), Plano.CAIXA_SIMPLES);
        conta.comoUsuario(() -> faturamento.doDia(hoje()));
        assertThatExceptionOfType(RecursoForaDoPlanoException.class)
                .as("o intermediário não inclui estoque")
                .isThrownBy(() -> conta.comoUsuario(estoque::produtos));
    }

    @Test
    @DisplayName("na tolerância os recursos pagos seguem; a partir do oitavo dia, relatório e estoque param")
    void toleranciaESuspensao() {
        ContaCriada conta = criador.criar("Mercearia Aurora", SENHA_DE_TESTE);
        criador.contratar(conta.contaId(), Plano.COMPLETO);
        criador.habilitarEstoque(conta.contaId());
        UUID arroz = cadastrar(conta, "Arroz");

        criador.vencerPlano(conta.contaId(), 7);
        conta.comoUsuario(() -> faturamento.doDia(hoje()));
        conta.comoUsuario(() -> estoque.ajustar(arroz, new BigDecimal("5"), "Contagem"));

        criador.vencerPlano(conta.contaId(), 8);
        assertThatExceptionOfType(PlanoSuspensoException.class)
                .isThrownBy(() -> conta.comoUsuario(() -> faturamento.doDia(hoje())))
                .withMessageContaining("suspensos desde");
        assertThatExceptionOfType(PlanoSuspensoException.class)
                .isThrownBy(() -> conta.comoUsuario(estoque::produtos));
        assertThatExceptionOfType(PlanoSuspensoException.class)
                .isThrownBy(() -> conta.comoUsuario(() ->
                        estoque.ajustar(arroz, new BigDecimal("-1"), "Perda")));
    }

    @Test
    @DisplayName("suspenso, o balcão continua: caixa, cadastro e Venda, e a Venda ainda baixa o estoque")
    void balcaoContinuaNaSuspensao() {
        ContaCriada conta = criador.criar("Padaria Aurora", SENHA_DE_TESTE);
        criador.contratar(conta.contaId(), Plano.COMPLETO);
        criador.habilitarEstoque(conta.contaId());
        criador.vencerPlano(conta.contaId(), 30);

        UUID sessaoId = conta.comoUsuario(() -> caixa.abrir(Money.ZERO));
        UUID pao = cadastrar(conta, "Pão francês");
        UUID vendaId = conta.comoUsuario(() -> vendas.iniciar(sessaoId));
        conta.comoUsuario(() -> {
            vendas.adicionarItem(vendaId, pao, new BigDecimal("2"), Money.ZERO);
            vendas.registrarPagamento(vendaId,
                    SolicitacaoPagamento.de(FormaPagamento.CARTAO, Money.de("10.00")));
            vendas.concluir(vendaId);
        });
        conta.comoUsuario(() -> caixa.registrarSuprimento(sessaoId, Money.de("20.00"),
                "Troco da manhã"));

        List<EstoqueDoProduto> saldos = conta.comoUsuario(() -> produtos.saldosDe(List.of(pao)));
        assertThat(saldos).extracting(EstoqueDoProduto::estoqueAtual)
                .singleElement()
                .satisfies(saldo -> assertThat(saldo).isEqualByComparingTo("-2"));
    }

    private UUID cadastrar(ContaCriada conta, String nome) {
        return conta.comoUsuario(() -> produtos.cadastrar(TipoProduto.PRODUTO,
                new DadosDoProduto(nome, Money.de("5.00"), null, null, "un", null)));
    }

    private static LocalDate hoje() {
        return LocalDate.now(FusoDeReferencia.DO_BALCAO);
    }
}
