package br.com.caixasimples.relatorios;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import br.com.caixasimples.TesteDeIntegracao;
import br.com.caixasimples.cadastro.TipoProduto;
import br.com.caixasimples.cadastro.application.ProdutoService;
import br.com.caixasimples.cadastro.application.ProdutoService.DadosDoProduto;
import br.com.caixasimples.caixa.application.SessaoCaixaService;
import br.com.caixasimples.contas.CriadorDeContaDeTeste;
import br.com.caixasimples.contas.CriadorDeContaDeTeste.ContaCriada;
import br.com.caixasimples.relatorios.application.FaturamentoService;
import br.com.caixasimples.relatorios.application.FaturamentoService.Faturamento;
import br.com.caixasimples.shared.FusoDeReferencia;
import br.com.caixasimples.shared.Money;
import br.com.caixasimples.shared.TenantContext;
import br.com.caixasimples.vendas.CriadorDeVendaDeTeste;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Faturamento do dia e do período (RF21), pelo caso de uso e contra o Postgres real.
 *
 * <p>As vendas são gravadas por {@code CriadorDeVendaDeTeste}, que fixa o instante da conclusão:
 * é a única forma de testar a borda da meia-noite no fuso do balcão, já que o caso de uso de
 * concluir grava o instante corrente. Cada cenário abre um caixa e cadastra um produto pelos casos
 * de uso dos módulos donos, porque a venda aponta para os dois por chave estrangeira.
 *
 * <p>Em nenhuma linha abaixo existe {@code WHERE conta_id}: é o tenant declarado no mapeamento de
 * leitura que filtra a consulta agregada, e o último teste é a prova disso (RNF05).
 */
class FaturamentoServiceTest extends TesteDeIntegracao {

    private static final String SENHA_DE_TESTE = "uma senha longa de teste";

    /** Um dia qualquer; o que importa é o fuso em que ele começa e termina. */
    private static final LocalDate DIA = LocalDate.of(2026, 9, 15);

    @Autowired
    private FaturamentoService faturamento;

    @Autowired
    private SessaoCaixaService caixas;

    @Autowired
    private ProdutoService produtos;

    @Autowired
    private CriadorDeContaDeTeste criador;

    @Autowired
    private CriadorDeVendaDeTeste vendas;

    @AfterEach
    void limparContexto() {
        TenantContext.limpar();
    }

    @Test
    @DisplayName("o dia é delimitado no fuso do balcão e conta só as vendas CONCLUIDA")
    void faturamentoDoDiaNoFusoDoBalcao() {
        ContaCriada conta = criador.criar("Cafeteria Aurora", SENHA_DE_TESTE);
        Cenario cenario = prepararCenario(conta);

        // Dentro do dia: a primeira venda da manhã e a última antes da meia-noite.
        concluida(conta, cenario, "10.00", noBalcao(DIA, LocalTime.of(9, 0)));
        concluida(conta, cenario, "20.00", noBalcao(DIA, LocalTime.of(23, 59, 59)));
        // Fora do dia, por um segundo de cada lado.
        concluida(conta, cenario, "30.00", noBalcao(DIA.plusDays(1), LocalTime.MIDNIGHT));
        concluida(conta, cenario, "40.00", noBalcao(DIA.minusDays(1), LocalTime.of(23, 59, 59)));
        // No dia, mas fora do faturamento: a comanda aberta e a venda cancelada depois de concluir.
        vendas.criarAbertaEm(conta.contaId(), cenario.sessaoCaixaId(), cenario.usuarioId());
        vendas.criarCanceladaQueConcluiuEm(conta.contaId(), cenario.sessaoCaixaId(),
                cenario.usuarioId(), cenario.produtoId(), Money.de("50.00"),
                noBalcao(DIA, LocalTime.NOON));

        Faturamento doDia = TenantContext.executarComo(conta.contaId(),
                () -> faturamento.doDia(DIA));
        Faturamento doDiaSeguinte = TenantContext.executarComo(conta.contaId(),
                () -> faturamento.doDia(DIA.plusDays(1)));

        assertThat(doDia.total()).isEqualTo(Money.de("30.00"));
        assertThat(doDia.quantidadeDeVendas()).isEqualTo(2);
        assertThat(doDia.inicio()).isEqualTo(DIA);
        assertThat(doDia.fim()).isEqualTo(DIA);

        // A venda da meia-noite pertence ao dia que começa nela, e só a ele.
        assertThat(doDiaSeguinte.total()).isEqualTo(Money.de("30.00"));
        assertThat(doDiaSeguinte.quantidadeDeVendas()).isEqualTo(1);
    }

    @Test
    @DisplayName("o período soma todos os dias, com os dois extremos incluídos")
    void faturamentoDoPeriodoIncluiOsExtremos() {
        ContaCriada conta = criador.criar("Padaria Central", SENHA_DE_TESTE);
        Cenario cenario = prepararCenario(conta);

        concluida(conta, cenario, "10.00", noBalcao(DIA, LocalTime.MIDNIGHT));
        concluida(conta, cenario, "20.00", noBalcao(DIA.plusDays(1), LocalTime.NOON));
        concluida(conta, cenario, "30.00", noBalcao(DIA.plusDays(2), LocalTime.of(23, 59, 59)));
        // Um dia antes e um dia depois do período, para provar que os extremos cortam.
        concluida(conta, cenario, "40.00", noBalcao(DIA.minusDays(1), LocalTime.of(23, 59, 59)));
        concluida(conta, cenario, "50.00", noBalcao(DIA.plusDays(3), LocalTime.MIDNIGHT));

        Faturamento doPeriodo = TenantContext.executarComo(conta.contaId(),
                () -> faturamento.doPeriodo(DIA, DIA.plusDays(2)));
        Faturamento doDia = TenantContext.executarComo(conta.contaId(),
                () -> faturamento.doDia(DIA));
        Faturamento doPeriodoDeUmDia = TenantContext.executarComo(conta.contaId(),
                () -> faturamento.doPeriodo(DIA, DIA));

        assertThat(doPeriodo.total()).isEqualTo(Money.de("60.00"));
        assertThat(doPeriodo.quantidadeDeVendas()).isEqualTo(3);
        assertThat(doPeriodo.inicio()).isEqualTo(DIA);
        assertThat(doPeriodo.fim()).isEqualTo(DIA.plusDays(2));

        // O dia é o período de um dia só: as duas perguntas têm a mesma resposta.
        assertThat(doDia).isEqualTo(doPeriodoDeUmDia);
        assertThat(doDia.total()).isEqualTo(Money.de("10.00"));
    }

    @Test
    @DisplayName("dia sem venda devolve zero, nunca nulo; período invertido é recusado")
    void diaSemVendaEPeriodoInvertido() {
        ContaCriada conta = criador.criar("Mercearia da Rua", SENHA_DE_TESTE);

        Faturamento vazio = TenantContext.executarComo(conta.contaId(),
                () -> faturamento.doDia(DIA));

        assertThat(vazio.total()).isEqualTo(Money.ZERO);
        assertThat(vazio.quantidadeDeVendas()).isZero();

        TenantContext.executarComo(conta.contaId(), () ->
                assertThatIllegalArgumentException()
                        .isThrownBy(() -> faturamento.doPeriodo(DIA, DIA.minusDays(1)))
                        .withMessageContaining("nao pode vir antes do inicio"));
    }

    @Test
    @DisplayName("duas contas com vendas no mesmo dia recebem cada uma só o seu faturamento (RNF05)")
    void contasComDadosEquivalentesRecebemResultadosDistintos() {
        ContaCriada contaA = criador.criar("Loja A", SENHA_DE_TESTE);
        ContaCriada contaB = criador.criar("Loja B", SENHA_DE_TESTE);
        ContaCriada contaC = criador.criar("Loja C", SENHA_DE_TESTE);
        Cenario cenarioA = prepararCenario(contaA);
        Cenario cenarioB = prepararCenario(contaB);

        concluida(contaA, cenarioA, "10.00", noBalcao(DIA, LocalTime.of(10, 0)));
        concluida(contaA, cenarioA, "20.00", noBalcao(DIA, LocalTime.of(11, 0)));
        concluida(contaB, cenarioB, "15.00", noBalcao(DIA, LocalTime.of(10, 0)));

        Faturamento deA = TenantContext.executarComo(contaA.contaId(),
                () -> faturamento.doDia(DIA));
        Faturamento deB = TenantContext.executarComo(contaB.contaId(),
                () -> faturamento.doDia(DIA));
        Faturamento deC = TenantContext.executarComo(contaC.contaId(),
                () -> faturamento.doDia(DIA));

        assertThat(deA.total()).isEqualTo(Money.de("30.00"));
        assertThat(deA.quantidadeDeVendas()).isEqualTo(2);
        assertThat(deB.total()).isEqualTo(Money.de("15.00"));
        assertThat(deB.quantidadeDeVendas()).isEqualTo(1);
        assertThat(deC.total()).isEqualTo(Money.ZERO);
        assertThat(deC.quantidadeDeVendas()).isZero();
    }

    /** Um horário local do balcão como o instante gravado no banco, em UTC. */
    private static Instant noBalcao(LocalDate dia, LocalTime hora) {
        return dia.atTime(hora).atZone(FusoDeReferencia.DO_BALCAO).toInstant();
    }

    private void concluida(ContaCriada conta, Cenario cenario, String valor, Instant concluidoEm) {
        vendas.criarConcluidaEm(conta.contaId(), cenario.sessaoCaixaId(), cenario.usuarioId(),
                cenario.produtoId(), Money.de(valor), concluidoEm);
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

    private record Cenario(UUID usuarioId, UUID sessaoCaixaId, UUID produtoId) {
    }
}
