package br.com.caixasimples.relatorios;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import br.com.caixasimples.TesteDeIntegracao;
import br.com.caixasimples.cadastro.TipoProduto;
import br.com.caixasimples.cadastro.application.ProdutoService;
import br.com.caixasimples.cadastro.application.ProdutoService.DadosDoProduto;
import br.com.caixasimples.caixa.application.SessaoCaixaService;
import br.com.caixasimples.contas.CriadorDeContaDeTeste;
import br.com.caixasimples.contas.CriadorDeContaDeTeste.ContaCriada;
import br.com.caixasimples.contas.CriadorDeContaDeTeste.UsuarioCriado;
import br.com.caixasimples.pagamentos.FormaPagamento;
import br.com.caixasimples.relatorios.application.FaturamentoService;
import br.com.caixasimples.relatorios.application.FaturamentoService.Faturamento;
import br.com.caixasimples.relatorios.application.FaturamentoService.Filtros;
import br.com.caixasimples.shared.AcessoNegadoException;
import br.com.caixasimples.shared.FusoDeReferencia;
import br.com.caixasimples.shared.Money;
import br.com.caixasimples.shared.TenantContext;
import br.com.caixasimples.vendas.CriadorDeVendaDeTeste;
import br.com.caixasimples.vendas.CriadorDeVendaDeTeste.ItemDeTeste;
import br.com.caixasimples.vendas.CriadorDeVendaDeTeste.ParcelaDeTeste;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
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
 * <p>Os filtros por forma de pagamento e por operador (RF24) têm os seus cenários aqui, com venda
 * dividida entre formas, parcela recusada e dois operadores da mesma conta, cada um no seu caixa.
 *
 * <p>Em nenhuma linha abaixo existe {@code WHERE conta_id}: é o tenant declarado no mapeamento de
 * leitura que filtra a consulta agregada, e os testes de isolamento são a prova disso (RNF05),
 * inclusive com os filtros.
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

        Faturamento doDia = conta.comoUsuario(
                () -> faturamento.doDia(DIA));
        Faturamento doDiaSeguinte = conta.comoUsuario(
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

        Faturamento doPeriodo = conta.comoUsuario(
                () -> faturamento.doPeriodo(DIA, DIA.plusDays(2)));
        Faturamento doDia = conta.comoUsuario(
                () -> faturamento.doDia(DIA));
        Faturamento doPeriodoDeUmDia = conta.comoUsuario(
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
    @DisplayName("o operador não lê o faturamento, nem o próprio: é relatório do administrador (RF30)")
    void operadorNaoLeOFaturamento() {
        ContaCriada conta = criador.criar("Mercearia com Atendente", SENHA_DE_TESTE);
        UsuarioCriado operador = criador.criarOperadorEm(conta.contaId(), "Atendente");

        assertThatExceptionOfType(AcessoNegadoException.class)
                .isThrownBy(() -> operador.comoUsuario(() -> faturamento.doDia(DIA)));
        assertThatExceptionOfType(AcessoNegadoException.class)
                .isThrownBy(() -> operador.comoUsuario(() -> faturamento.doPeriodo(DIA, DIA)));
        assertThatExceptionOfType(AcessoNegadoException.class)
                .as("o filtro pelo próprio operador não abre a porta")
                .isThrownBy(() -> operador.comoUsuario(() ->
                        faturamento.doDia(DIA, Filtros.porOperador(operador.usuarioId()))));
    }

    @Test
    @DisplayName("dia sem venda devolve zero, nunca nulo; período invertido é recusado")
    void diaSemVendaEPeriodoInvertido() {
        ContaCriada conta = criador.criar("Mercearia da Rua", SENHA_DE_TESTE);

        Faturamento vazio = conta.comoUsuario(
                () -> faturamento.doDia(DIA));

        assertThat(vazio.total()).isEqualTo(Money.ZERO);
        assertThat(vazio.quantidadeDeVendas()).isZero();

        conta.comoUsuario(() ->
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

        Faturamento deA = contaA.comoUsuario(
                () -> faturamento.doDia(DIA));
        Faturamento deB = contaB.comoUsuario(
                () -> faturamento.doDia(DIA));
        Faturamento deC = contaC.comoUsuario(
                () -> faturamento.doDia(DIA));

        assertThat(deA.total()).isEqualTo(Money.de("30.00"));
        assertThat(deA.quantidadeDeVendas()).isEqualTo(2);
        assertThat(deB.total()).isEqualTo(Money.de("15.00"));
        assertThat(deB.quantidadeDeVendas()).isEqualTo(1);
        assertThat(deC.total()).isEqualTo(Money.ZERO);
        assertThat(deC.quantidadeDeVendas()).isZero();
    }

    @Test
    @DisplayName("por forma de pagamento, a venda dividida se reparte pelo valor de cada parcela (RF24)")
    void filtroPorFormaReparteAVendaDividida() {
        ContaCriada conta = criador.criar("Cafeteria Aurora", SENHA_DE_TESTE);
        Cenario cenario = prepararCenario(conta);

        // Dividida: dez em dinheiro e vinte em Pix, uma venda só de trinta.
        concluidaComParcelas(conta, cenario, "30.00", noBalcao(DIA, LocalTime.of(10, 0)),
                ParcelaDeTeste.confirmada(FormaPagamento.DINHEIRO, Money.de("10.00")),
                ParcelaDeTeste.confirmada(FormaPagamento.PIX, Money.de("20.00")));
        // Cartão recusado e depois dinheiro: o cartão fica gravado, mas nunca entrou.
        concluidaComParcelas(conta, cenario, "15.00", noBalcao(DIA, LocalTime.of(11, 0)),
                ParcelaDeTeste.recusada(FormaPagamento.CARTAO, Money.de("15.00")),
                ParcelaDeTeste.confirmada(FormaPagamento.DINHEIRO, Money.de("15.00")));
        concluidaComParcelas(conta, cenario, "5.00", noBalcao(DIA, LocalTime.NOON),
                ParcelaDeTeste.confirmada(FormaPagamento.PIX, Money.de("5.00")));
        // Duas parcelas na mesma forma: uma venda só, contada uma vez.
        concluidaComParcelas(conta, cenario, "7.00", noBalcao(DIA, LocalTime.of(13, 0)),
                ParcelaDeTeste.confirmada(FormaPagamento.PIX, Money.de("3.00")),
                ParcelaDeTeste.confirmada(FormaPagamento.PIX, Money.de("4.00")));
        // Fora: Pix no dia seguinte, a cancelada paga em dinheiro e a comanda aberta.
        concluidaComParcelas(conta, cenario, "40.00", noBalcao(DIA.plusDays(1), LocalTime.MIDNIGHT),
                ParcelaDeTeste.confirmada(FormaPagamento.PIX, Money.de("40.00")));
        vendas.criarCanceladaQueConcluiuEm(conta.contaId(), cenario.sessaoCaixaId(),
                cenario.usuarioId(), cenario.produtoId(), Money.de("50.00"),
                noBalcao(DIA, LocalTime.NOON));
        vendas.criarAbertaEm(conta.contaId(), cenario.sessaoCaixaId(), cenario.usuarioId());

        Faturamento emDinheiro = doDia(conta, Filtros.porForma(FormaPagamento.DINHEIRO));
        Faturamento emPix = doDia(conta, Filtros.porForma(FormaPagamento.PIX));
        Faturamento emCartao = doDia(conta, Filtros.porForma(FormaPagamento.CARTAO));
        Faturamento semFiltro = doDia(conta, Filtros.nenhum());
        Faturamento emPixPeloPeriodo = conta.comoUsuario(
                () -> faturamento.doPeriodo(DIA, DIA, Filtros.porForma(FormaPagamento.PIX)));

        assertThat(emDinheiro.total()).isEqualTo(Money.de("25.00"));
        assertThat(emDinheiro.quantidadeDeVendas()).isEqualTo(2);
        assertThat(emPix.total()).isEqualTo(Money.de("32.00"));
        assertThat(emPix.quantidadeDeVendas()).isEqualTo(3);
        assertThat(emCartao.total()).isEqualTo(Money.ZERO);
        assertThat(emCartao.quantidadeDeVendas()).isZero();

        // As três formas somadas são o faturamento sem filtro, nem mais nem menos.
        assertThat(semFiltro.total()).isEqualTo(Money.de("57.00"));
        assertThat(semFiltro.quantidadeDeVendas()).isEqualTo(4);
        assertThat(emDinheiro.total().somar(emPix.total()).somar(emCartao.total()))
                .isEqualTo(semFiltro.total());

        assertThat(emPixPeloPeriodo).isEqualTo(emPix);
    }

    @Test
    @DisplayName("por operador, contam só as vendas dele, e o filtro combina com a forma (RF24)")
    void filtroPorOperadorSozinhoECombinado() {
        ContaCriada conta = criador.criar("Padaria Central", SENHA_DE_TESTE);
        Cenario doTitular = prepararCenario(conta);
        Cenario daColega = prepararCenarioDeOutroOperador(conta, doTitular, "Beatriz");

        concluida(conta, doTitular, "10.00", noBalcao(DIA, LocalTime.of(9, 0)));
        concluidaComParcelas(conta, doTitular, "20.00", noBalcao(DIA, LocalTime.of(10, 0)),
                ParcelaDeTeste.confirmada(FormaPagamento.PIX, Money.de("20.00")));
        concluida(conta, daColega, "30.00", noBalcao(DIA, LocalTime.of(9, 0)));
        concluidaComParcelas(conta, daColega, "40.00", noBalcao(DIA, LocalTime.of(10, 0)),
                ParcelaDeTeste.confirmada(FormaPagamento.DINHEIRO, Money.de("15.00")),
                ParcelaDeTeste.confirmada(FormaPagamento.PIX, Money.de("25.00")));

        Faturamento doTitularSo = doDia(conta, Filtros.porOperador(doTitular.usuarioId()));
        Faturamento daColegaSo = doDia(conta, Filtros.porOperador(daColega.usuarioId()));
        Faturamento deTodos = doDia(conta, Filtros.nenhum());
        Faturamento pixDaColega = doDia(conta,
                new Filtros(FormaPagamento.PIX, daColega.usuarioId()));
        Faturamento dinheiroDoTitular = doDia(conta,
                new Filtros(FormaPagamento.DINHEIRO, doTitular.usuarioId()));
        Faturamento dinheiroDeTodos = doDia(conta, Filtros.porForma(FormaPagamento.DINHEIRO));
        Faturamento deNinguem = doDia(conta, Filtros.porOperador(UUID.randomUUID()));

        assertThat(doTitularSo.total()).isEqualTo(Money.de("30.00"));
        assertThat(doTitularSo.quantidadeDeVendas()).isEqualTo(2);
        assertThat(daColegaSo.total()).isEqualTo(Money.de("70.00"));
        assertThat(daColegaSo.quantidadeDeVendas()).isEqualTo(2);
        assertThat(deTodos.total()).isEqualTo(Money.de("100.00"));
        assertThat(deTodos.quantidadeDeVendas()).isEqualTo(4);

        assertThat(pixDaColega.total()).isEqualTo(Money.de("25.00"));
        assertThat(pixDaColega.quantidadeDeVendas()).isEqualTo(1);
        assertThat(dinheiroDoTitular.total()).isEqualTo(Money.de("10.00"));
        assertThat(dinheiroDoTitular.quantidadeDeVendas()).isEqualTo(1);
        assertThat(dinheiroDeTodos.total()).isEqualTo(Money.de("55.00"));
        assertThat(dinheiroDeTodos.quantidadeDeVendas()).isEqualTo(3);

        // Operador que não existe é zero, não erro: o relatório não pergunta a contas quem existe.
        assertThat(deNinguem.total()).isEqualTo(Money.ZERO);
        assertThat(deNinguem.quantidadeDeVendas()).isZero();
    }

    @Test
    @DisplayName("filtros nulos são recusados na entrada, com a saída indicada")
    void filtrosNulosSaoRecusados() {
        ContaCriada conta = criador.criar("Mercearia da Rua", SENHA_DE_TESTE);

        conta.comoUsuario(() -> {
            assertThatNullPointerException()
                    .isThrownBy(() -> faturamento.doPeriodo(DIA, DIA, null))
                    .withMessageContaining("Filtros.nenhum()");
            assertThatNullPointerException()
                    .isThrownBy(() -> faturamento.doDia(DIA, null))
                    .withMessageContaining("Filtros.nenhum()");
        });
        assertThatNullPointerException().isThrownBy(() -> Filtros.porForma(null));
        assertThatNullPointerException().isThrownBy(() -> Filtros.porOperador(null));
    }

    @Test
    @DisplayName("os filtros não atravessam contas: forma e operador de uma conta não alcançam a outra (RNF05)")
    void filtrosNaoAtravessamContas() {
        ContaCriada contaA = criador.criar("Loja A", SENHA_DE_TESTE);
        ContaCriada contaB = criador.criar("Loja B", SENHA_DE_TESTE);
        ContaCriada contaC = criador.criar("Loja C", SENHA_DE_TESTE);
        Cenario cenarioA = prepararCenario(contaA);
        Cenario cenarioB = prepararCenario(contaB);

        concluidaComParcelas(contaA, cenarioA, "10.00", noBalcao(DIA, LocalTime.of(10, 0)),
                ParcelaDeTeste.confirmada(FormaPagamento.PIX, Money.de("10.00")));
        concluidaComParcelas(contaB, cenarioB, "15.00", noBalcao(DIA, LocalTime.of(10, 0)),
                ParcelaDeTeste.confirmada(FormaPagamento.PIX, Money.de("15.00")));

        Faturamento pixDeA = doDia(contaA, Filtros.porForma(FormaPagamento.PIX));
        Faturamento pixDeB = doDia(contaB, Filtros.porForma(FormaPagamento.PIX));
        Faturamento pixDeC = doDia(contaC, Filtros.porForma(FormaPagamento.PIX));
        // B pede pelo operador de A: o id existe, mas não nesta conta.
        Faturamento bPeloOperadorDeA = doDia(contaB, Filtros.porOperador(cenarioA.usuarioId()));
        Faturamento bPeloPixDeA = doDia(contaB,
                new Filtros(FormaPagamento.PIX, cenarioA.usuarioId()));

        assertThat(pixDeA.total()).isEqualTo(Money.de("10.00"));
        assertThat(pixDeA.quantidadeDeVendas()).isEqualTo(1);
        assertThat(pixDeB.total()).isEqualTo(Money.de("15.00"));
        assertThat(pixDeB.quantidadeDeVendas()).isEqualTo(1);
        assertThat(pixDeC.total()).isEqualTo(Money.ZERO);
        assertThat(pixDeC.quantidadeDeVendas()).isZero();
        assertThat(bPeloOperadorDeA.total()).isEqualTo(Money.ZERO);
        assertThat(bPeloOperadorDeA.quantidadeDeVendas()).isZero();
        assertThat(bPeloPixDeA.total()).isEqualTo(Money.ZERO);
        assertThat(bPeloPixDeA.quantidadeDeVendas()).isZero();
    }

    /** Um horário local do balcão como o instante gravado no banco, em UTC. */
    private static Instant noBalcao(LocalDate dia, LocalTime hora) {
        return dia.atTime(hora).atZone(FusoDeReferencia.DO_BALCAO).toInstant();
    }

    private Faturamento doDia(ContaCriada conta, Filtros filtros) {
        return conta.comoUsuario(() -> faturamento.doDia(DIA, filtros));
    }

    private void concluida(ContaCriada conta, Cenario cenario, String valor, Instant concluidoEm) {
        vendas.criarConcluidaEm(conta.contaId(), cenario.sessaoCaixaId(), cenario.usuarioId(),
                cenario.produtoId(), Money.de(valor), concluidoEm);
    }

    /** Uma venda de um item, no valor dado, paga pelas parcelas dadas, que têm de somar o valor. */
    private void concluidaComParcelas(ContaCriada conta, Cenario cenario, String valor,
            Instant concluidoEm, ParcelaDeTeste... parcelas) {
        vendas.criarConcluidaComParcelasEm(conta.contaId(), cenario.sessaoCaixaId(),
                cenario.usuarioId(), List.of(ItemDeTeste.unitario(cenario.produtoId(),
                        Money.de(valor))), List.of(parcelas), concluidoEm);
    }

    /**
     * Abre um caixa para o operador da conta e cadastra um produto, porque a venda referencia os
     * dois por chave estrangeira e o banco recusaria uma venda apontando para o nada.
     */
    private Cenario prepararCenario(ContaCriada conta) {
        return conta.comoUsuario(() -> {
            UUID sessaoCaixaId = caixas.abrir(Money.ZERO);
            UUID produtoId = produtos.cadastrar(TipoProduto.PRODUTO, new DadosDoProduto(
                    "Cafe coado", Money.de("4.50"), null, null, "un", null));
            return new Cenario(conta.usuarioId(), sessaoCaixaId, produtoId);
        });
    }

    /**
     * Um segundo operador da mesma conta, com o seu próprio caixa aberto, vendendo o mesmo produto:
     * o banco admite uma só sessão ABERTA por operador, e cada operador vende no seu caixa.
     */
    private Cenario prepararCenarioDeOutroOperador(ContaCriada conta, Cenario doTitular,
            String nome) {
        UsuarioCriado operador = criador.criarOperadorEm(conta.contaId(), nome);
        UUID sessaoCaixaId = operador.comoUsuario(() -> caixas.abrir(Money.ZERO));
        return new Cenario(operador.usuarioId(), sessaoCaixaId, doTitular.produtoId());
    }

    private record Cenario(UUID usuarioId, UUID sessaoCaixaId, UUID produtoId) {
    }
}
