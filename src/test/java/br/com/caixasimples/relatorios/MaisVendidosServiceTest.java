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
import br.com.caixasimples.relatorios.application.MaisVendidosService;
import br.com.caixasimples.relatorios.application.MaisVendidosService.MaisVendidos;
import br.com.caixasimples.relatorios.application.MaisVendidosService.Posicao;
import br.com.caixasimples.shared.FusoDeReferencia;
import br.com.caixasimples.shared.Money;
import br.com.caixasimples.shared.TenantContext;
import br.com.caixasimples.vendas.CriadorDeVendaDeTeste;
import br.com.caixasimples.vendas.CriadorDeVendaDeTeste.ItemDeTeste;
import java.math.BigDecimal;
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
 * Os mais vendidos de um período (RF22), pelo caso de uso e contra o Postgres real.
 *
 * <p>As vendas são gravadas por {@code CriadorDeVendaDeTeste}, que fixa o instante da conclusão e
 * aceita mais de um item, com quantidade, preço e desconto escolhidos aqui. Cada cenário abre um
 * caixa e cadastra os produtos pelos casos de uso dos módulos donos, porque a venda e o item
 * apontam para eles por chave estrangeira.
 *
 * <p>Em nenhuma linha abaixo existe {@code WHERE conta_id}: é o tenant declarado no mapeamento de
 * leitura que filtra a consulta agregada, e o último teste é a prova disso (RNF05).
 */
class MaisVendidosServiceTest extends TesteDeIntegracao {

    private static final String SENHA_DE_TESTE = "uma senha longa de teste";

    /** Um dia qualquer; o que importa é o fuso em que ele começa e termina. */
    private static final LocalDate DIA = LocalDate.of(2026, 9, 15);

    private static final int DEZ = 10;

    @Autowired
    private MaisVendidosService maisVendidos;

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
    @DisplayName("ordena por quantidade decrescente, com valor, nome e unidade, e o limite corta")
    void rankingPorQuantidadeComValorAoLado() {
        ContaCriada conta = criador.criar("Cafeteria Aurora", SENHA_DE_TESTE);
        Cenario cenario = prepararCenario(conta);
        UUID cafe = cenario.produto("Cafe coado", "4.50", "un");
        UUID bolo = cenario.produto("Bolo de laranja", "12.00", "fatia");
        UUID queijo = cenario.produto("Queijo da serra", "39.90", "kg");

        // Três cafés e uma fatia de bolo de manhã; dois cafés, um quilo e meio de queijo à tarde.
        concluida(conta, cenario, noBalcao(DIA, LocalTime.of(9, 0)),
                new ItemDeTeste(cafe, new BigDecimal("3"), Money.de("4.50"), Money.ZERO),
                new ItemDeTeste(bolo, BigDecimal.ONE, Money.de("12.00"), Money.ZERO));
        concluida(conta, cenario, noBalcao(DIA, LocalTime.of(16, 0)),
                new ItemDeTeste(cafe, new BigDecimal("2"), Money.de("4.50"), Money.ZERO),
                new ItemDeTeste(queijo, new BigDecimal("1.500"), Money.de("39.90"), Money.ZERO));

        MaisVendidos todos = TenantContext.executarComo(conta.contaId(),
                () -> maisVendidos.doPeriodo(DIA, DIA, DEZ));
        MaisVendidos soUm = TenantContext.executarComo(conta.contaId(),
                () -> maisVendidos.doPeriodo(DIA, DIA, 1));

        assertThat(todos.inicio()).isEqualTo(DIA);
        assertThat(todos.fim()).isEqualTo(DIA);
        assertThat(todos.posicoes()).extracting(Posicao::produtoId)
                .containsExactly(cafe, queijo, bolo);

        Posicao primeiro = todos.posicoes().get(0);
        assertThat(primeiro.nome()).isEqualTo("Cafe coado");
        assertThat(primeiro.unidade()).isEqualTo("un");
        // A quantidade sai com as três casas da coluna, por ser soma dela e não dinheiro.
        assertThat(primeiro.quantidade()).isEqualByComparingTo("5");
        assertThat(primeiro.valor()).isEqualTo(Money.de("22.50"));

        Posicao segundo = todos.posicoes().get(1);
        assertThat(segundo.unidade()).isEqualTo("kg");
        assertThat(segundo.quantidade()).isEqualByComparingTo("1.500");
        assertThat(segundo.valor()).isEqualTo(Money.de("59.85"));

        // O limite corta no banco: o ranking de um é só o primeiro do ranking de dez.
        assertThat(soUm.posicoes()).containsExactly(primeiro);
    }

    @Test
    @DisplayName("conta só as vendas CONCLUIDA, pelo dia da conclusão no fuso do balcão")
    void contaSoAsConcluidasNoDia() {
        ContaCriada conta = criador.criar("Padaria Central", SENHA_DE_TESTE);
        Cenario cenario = prepararCenario(conta);
        UUID pao = cenario.produto("Pao frances", "0.80", "un");

        // Dentro do dia: a primeira venda da manhã e a última antes da meia-noite.
        concluida(conta, cenario, noBalcao(DIA, LocalTime.MIDNIGHT), paes(pao, "10"));
        concluida(conta, cenario, noBalcao(DIA, LocalTime.of(23, 59, 59)), paes(pao, "20"));
        // Fora do dia, por um segundo de cada lado.
        concluida(conta, cenario, noBalcao(DIA.plusDays(1), LocalTime.MIDNIGHT), paes(pao, "100"));
        concluida(conta, cenario, noBalcao(DIA.minusDays(1), LocalTime.of(23, 59, 59)),
                paes(pao, "100"));
        // No dia, mas fora do ranking: a comanda aberta e a venda cancelada depois de concluir.
        vendas.criarAbertaComItens(conta.contaId(), cenario.sessaoCaixaId(), cenario.usuarioId(),
                List.of(paes(pao, "100")));
        vendas.criarCanceladaComItensQueConcluiuEm(conta.contaId(), cenario.sessaoCaixaId(),
                cenario.usuarioId(), List.of(paes(pao, "100")), noBalcao(DIA, LocalTime.NOON));

        MaisVendidos doDia = TenantContext.executarComo(conta.contaId(),
                () -> maisVendidos.doPeriodo(DIA, DIA, DEZ));

        assertThat(doDia.posicoes()).hasSize(1);
        assertThat(doDia.posicoes().get(0).quantidade()).isEqualByComparingTo("30");
        assertThat(doDia.posicoes().get(0).valor()).isEqualTo(Money.de("24.00"));
    }

    @Test
    @DisplayName("o valor é arredondado por item, como o comprovante, e o desconto do item é subtraído")
    void valorArredondadoPorItemComDesconto() {
        ContaCriada conta = criador.criar("Mercearia da Rua", SENHA_DE_TESTE);
        Cenario cenario = prepararCenario(conta);
        UUID queijo = cenario.produto("Queijo da serra", "39.90", "kg");

        // 0,750 kg a 39,90 dá 29,925, que o domínio arredonda para 29,93 neste item. Duas vendas
        // assim somam 59,86; somar antes de arredondar daria 59,85, e o ranking deixaria de bater
        // com os dois comprovantes.
        ItemDeTeste tresQuartos = new ItemDeTeste(queijo, new BigDecimal("0.750"), Money.de("39.90"),
                Money.ZERO);
        concluida(conta, cenario, noBalcao(DIA, LocalTime.of(9, 0)), tresQuartos);
        concluida(conta, cenario, noBalcao(DIA, LocalTime.of(10, 0)), tresQuartos);
        // Um quilo com dois reais de desconto: 39,90 menos 2,00.
        concluida(conta, cenario, noBalcao(DIA, LocalTime.of(11, 0)),
                new ItemDeTeste(queijo, BigDecimal.ONE, Money.de("39.90"), Money.de("2.00")));

        MaisVendidos doDia = TenantContext.executarComo(conta.contaId(),
                () -> maisVendidos.doPeriodo(DIA, DIA, DEZ));

        assertThat(doDia.posicoes()).hasSize(1);
        assertThat(doDia.posicoes().get(0).quantidade()).isEqualByComparingTo("2.500");
        assertThat(doDia.posicoes().get(0).valor()).isEqualTo(Money.de("97.76"));
    }

    @Test
    @DisplayName("produto inativado continua no ranking do período em que foi vendido, com o nome atual")
    void produtoInativadoContinuaNoRanking() {
        ContaCriada conta = criador.criar("Loja da Esquina", SENHA_DE_TESTE);
        Cenario cenario = prepararCenario(conta);
        UUID caneca = cenario.produto("Caneca", "25.00", "un");
        concluida(conta, cenario, noBalcao(DIA, LocalTime.of(9, 0)),
                new ItemDeTeste(caneca, new BigDecimal("2"), Money.de("25.00"), Money.ZERO));

        TenantContext.executarComo(conta.contaId(), () -> {
            produtos.editar(caneca, new DadosDoProduto("Caneca de ceramica", Money.de("30.00"),
                    null, null, "un", null));
            produtos.inativar(caneca);
        });

        MaisVendidos doDia = TenantContext.executarComo(conta.contaId(),
                () -> maisVendidos.doPeriodo(DIA, DIA, DEZ));

        assertThat(doDia.posicoes()).hasSize(1);
        assertThat(doDia.posicoes().get(0).nome()).isEqualTo("Caneca de ceramica");
        // O preço copiado para o item é o da venda, não o reajustado depois.
        assertThat(doDia.posicoes().get(0).valor()).isEqualTo(Money.de("50.00"));
    }

    @Test
    @DisplayName("período sem venda devolve lista vazia; período invertido e limite zero são recusados")
    void periodoVazioEArgumentosInvalidos() {
        ContaCriada conta = criador.criar("Barbearia do Centro", SENHA_DE_TESTE);

        MaisVendidos vazio = TenantContext.executarComo(conta.contaId(),
                () -> maisVendidos.doPeriodo(DIA, DIA, DEZ));

        assertThat(vazio.posicoes()).isEmpty();

        TenantContext.executarComo(conta.contaId(), () -> {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> maisVendidos.doPeriodo(DIA, DIA.minusDays(1), DEZ))
                    .withMessageContaining("nao pode vir antes do inicio");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> maisVendidos.doPeriodo(DIA, DIA, 0))
                    .withMessageContaining("pelo menos 1");
        });
    }

    @Test
    @DisplayName("duas contas com vendas no mesmo dia recebem cada uma só o seu ranking (RNF05)")
    void contasComDadosEquivalentesRecebemResultadosDistintos() {
        ContaCriada contaA = criador.criar("Loja A", SENHA_DE_TESTE);
        ContaCriada contaB = criador.criar("Loja B", SENHA_DE_TESTE);
        ContaCriada contaC = criador.criar("Loja C", SENHA_DE_TESTE);
        Cenario cenarioA = prepararCenario(contaA);
        Cenario cenarioB = prepararCenario(contaB);
        UUID cafeDeA = cenarioA.produto("Cafe coado", "4.50", "un");
        UUID cafeDeB = cenarioB.produto("Cafe coado", "4.50", "un");

        concluida(contaA, cenarioA, noBalcao(DIA, LocalTime.of(10, 0)),
                new ItemDeTeste(cafeDeA, new BigDecimal("3"), Money.de("4.50"), Money.ZERO));
        concluida(contaB, cenarioB, noBalcao(DIA, LocalTime.of(10, 0)),
                new ItemDeTeste(cafeDeB, new BigDecimal("7"), Money.de("4.50"), Money.ZERO));

        MaisVendidos deA = TenantContext.executarComo(contaA.contaId(),
                () -> maisVendidos.doPeriodo(DIA, DIA, DEZ));
        MaisVendidos deB = TenantContext.executarComo(contaB.contaId(),
                () -> maisVendidos.doPeriodo(DIA, DIA, DEZ));
        MaisVendidos deC = TenantContext.executarComo(contaC.contaId(),
                () -> maisVendidos.doPeriodo(DIA, DIA, DEZ));

        assertThat(deA.posicoes()).extracting(Posicao::produtoId).containsExactly(cafeDeA);
        assertThat(deA.posicoes().get(0).quantidade()).isEqualByComparingTo("3");
        assertThat(deB.posicoes()).extracting(Posicao::produtoId).containsExactly(cafeDeB);
        assertThat(deB.posicoes().get(0).quantidade()).isEqualByComparingTo("7");
        assertThat(deC.posicoes()).isEmpty();
    }

    /** Um horário local do balcão como o instante gravado no banco, em UTC. */
    private static Instant noBalcao(LocalDate dia, LocalTime hora) {
        return dia.atTime(hora).atZone(FusoDeReferencia.DO_BALCAO).toInstant();
    }

    private static ItemDeTeste paes(UUID pao, String quantidade) {
        return new ItemDeTeste(pao, new BigDecimal(quantidade), Money.de("0.80"), Money.ZERO);
    }

    private void concluida(ContaCriada conta, Cenario cenario, Instant concluidoEm,
            ItemDeTeste... itens) {
        vendas.criarConcluidaComItensEm(conta.contaId(), cenario.sessaoCaixaId(),
                cenario.usuarioId(), List.of(itens), concluidoEm);
    }

    /**
     * Abre um caixa para o operador da conta, porque a venda referencia a sessão por chave
     * estrangeira; os produtos são cadastrados um a um pelo cenário, pelo caso de uso do cadastro.
     */
    private Cenario prepararCenario(ContaCriada conta) {
        UUID sessaoCaixaId = TenantContext.executarComo(conta.contaId(),
                () -> caixas.abrir(conta.usuarioId(), Money.ZERO));
        return new Cenario(conta, sessaoCaixaId);
    }

    private final class Cenario {

        private final ContaCriada conta;
        private final UUID sessaoCaixaId;

        private Cenario(ContaCriada conta, UUID sessaoCaixaId) {
            this.conta = conta;
            this.sessaoCaixaId = sessaoCaixaId;
        }

        UUID usuarioId() {
            return conta.usuarioId();
        }

        UUID sessaoCaixaId() {
            return sessaoCaixaId;
        }

        UUID produto(String nome, String preco, String unidade) {
            return TenantContext.executarComo(conta.contaId(), () -> produtos.cadastrar(
                    TipoProduto.PRODUTO, new DadosDoProduto(nome, Money.de(preco), null, null,
                            unidade, null)));
        }
    }
}
