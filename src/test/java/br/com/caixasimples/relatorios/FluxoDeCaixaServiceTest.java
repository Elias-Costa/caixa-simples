package br.com.caixasimples.relatorios;

import static br.com.caixasimples.caixa.CriadorDeSessaoCaixaDeTeste.lancado;
import static br.com.caixasimples.caixa.CriadorDeSessaoCaixaDeTeste.lancadoDaVenda;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import br.com.caixasimples.TesteDeIntegracao;
import br.com.caixasimples.caixa.CriadorDeSessaoCaixaDeTeste;
import br.com.caixasimples.caixa.TipoMovimentoCaixa;
import br.com.caixasimples.caixa.application.SessaoCaixaService;
import br.com.caixasimples.caixa.domain.MovimentoCaixa;
import br.com.caixasimples.contas.CriadorDeContaDeTeste;
import br.com.caixasimples.contas.CriadorDeContaDeTeste.ContaCriada;
import br.com.caixasimples.contas.CriadorDeContaDeTeste.UsuarioCriado;
import br.com.caixasimples.relatorios.application.FluxoDeCaixaService;
import br.com.caixasimples.relatorios.application.FluxoDeCaixaService.FluxoDeCaixa;
import br.com.caixasimples.shared.AcessoNegadoException;
import br.com.caixasimples.shared.FusoDeReferencia;
import br.com.caixasimples.shared.Money;
import br.com.caixasimples.shared.TenantContext;
import br.com.caixasimples.vendas.CriadorDeVendaDeTeste;
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
 * O fluxo de caixa de um período (RF23), pelo caso de uso e contra o Postgres real.
 *
 * <p>Os movimentos são gravados por {@code CriadorDeSessaoCaixaDeTeste}, que fixa o instante de
 * cada lançamento: é a única forma de testar a borda da meia-noite no fuso do balcão, já que os
 * casos de uso do caixa gravam o instante corrente. Cada cenário abre um caixa de verdade só para
 * a venda a que os movimentos VENDA e ESTORNO apontam existir, porque {@code venda_id} é chave
 * estrangeira; esse caixa não tem movimento nenhum.
 *
 * <p>Em nenhuma linha abaixo existe {@code WHERE conta_id}: é o tenant declarado no mapeamento de
 * leitura que filtra a consulta agregada, e o último teste é a prova disso (RNF05).
 */
class FluxoDeCaixaServiceTest extends TesteDeIntegracao {

    private static final String SENHA_DE_TESTE = "uma senha longa de teste";

    /** Um dia qualquer; o que importa é o fuso em que ele começa e termina. */
    private static final LocalDate DIA = LocalDate.of(2026, 9, 15);

    @Autowired
    private FluxoDeCaixaService fluxo;

    @Autowired
    private SessaoCaixaService caixas;

    @Autowired
    private CriadorDeContaDeTeste criador;

    @Autowired
    private CriadorDeVendaDeTeste vendas;

    @Autowired
    private CriadorDeSessaoCaixaDeTeste sessoes;

    @AfterEach
    void limparContexto() {
        TenantContext.limpar();
    }

    @Test
    @DisplayName("soma os quatro tipos de todas as sessões do dia, e o dia é o do lançamento no fuso do balcão")
    void somaOsQuatroTiposNoDiaDoLancamento() {
        ContaCriada conta = criador.criar("Cafeteria Aurora", SENHA_DE_TESTE);
        Cenario cenario = prepararCenario(conta);
        UUID vendaDaManha = cenario.venda();
        UUID vendaDaTarde = cenario.venda();
        UUID vendaDeOntem = cenario.venda();

        // O expediente da manhã: suprimento de troco, uma venda em dinheiro e uma sangria.
        sessao(conta, noBalcao(DIA, LocalTime.of(8, 0)), List.of(
                lancado(TipoMovimentoCaixa.SUPRIMENTO, Money.de("50.00"), "troco",
                        noBalcao(DIA, LocalTime.MIDNIGHT)),
                lancadoDaVenda(TipoMovimentoCaixa.VENDA, Money.de("30.00"), vendaDaManha,
                        noBalcao(DIA, LocalTime.of(9, 0))),
                lancado(TipoMovimentoCaixa.SANGRIA, Money.de("20.00"), "deposito",
                        noBalcao(DIA, LocalTime.of(12, 0)))));
        // O expediente da tarde, que vira a meia-noite: a venda entra e é estornada ainda no dia;
        // a sangria do fechamento cai no dia seguinte, e é dele.
        sessao(conta, noBalcao(DIA, LocalTime.of(16, 0)), List.of(
                lancadoDaVenda(TipoMovimentoCaixa.VENDA, Money.de("40.00"), vendaDaTarde,
                        noBalcao(DIA, LocalTime.of(17, 0))),
                lancadoDaVenda(TipoMovimentoCaixa.ESTORNO, Money.de("40.00"), vendaDaTarde,
                        noBalcao(DIA, LocalTime.of(23, 59, 59))),
                lancado(TipoMovimentoCaixa.SANGRIA, Money.de("10.00"), "fechamento",
                        noBalcao(DIA.plusDays(1), LocalTime.MIDNIGHT))));
        // O expediente de ontem, fechado um segundo antes de o dia começar.
        sessao(conta, noBalcao(DIA.minusDays(1), LocalTime.of(8, 0)), List.of(
                lancadoDaVenda(TipoMovimentoCaixa.VENDA, Money.de("100.00"), vendaDeOntem,
                        noBalcao(DIA.minusDays(1), LocalTime.of(23, 59, 59)))));

        FluxoDeCaixa doDia = conta.comoUsuario(
                () -> fluxo.doPeriodo(DIA, DIA));
        FluxoDeCaixa doDiaSeguinte = conta.comoUsuario(
                () -> fluxo.doPeriodo(DIA.plusDays(1), DIA.plusDays(1)));
        FluxoDeCaixa dosTresDias = conta.comoUsuario(
                () -> fluxo.doPeriodo(DIA.minusDays(1), DIA.plusDays(1)));

        assertThat(doDia.inicio()).isEqualTo(DIA);
        assertThat(doDia.fim()).isEqualTo(DIA);
        assertThat(doDia.vendas()).isEqualTo(Money.de("70.00"));
        assertThat(doDia.suprimentos()).isEqualTo(Money.de("50.00"));
        assertThat(doDia.sangrias()).isEqualTo(Money.de("20.00"));
        assertThat(doDia.estornos()).isEqualTo(Money.de("40.00"));
        assertThat(doDia.entradas()).isEqualTo(Money.de("120.00"));
        assertThat(doDia.saidas()).isEqualTo(Money.de("60.00"));
        assertThat(doDia.saldo()).isEqualTo(Money.de("60.00"));

        // A sangria da meia-noite pertence ao dia que começa nela, e só a ele.
        assertThat(doDiaSeguinte.sangrias()).isEqualTo(Money.de("10.00"));
        assertThat(doDiaSeguinte.entradas()).isEqualTo(Money.ZERO);
        assertThat(doDiaSeguinte.saldo()).isEqualTo(Money.de("-10.00"));

        // O período soma os dias, com os dois extremos incluídos.
        assertThat(dosTresDias.vendas()).isEqualTo(Money.de("170.00"));
        assertThat(dosTresDias.sangrias()).isEqualTo(Money.de("30.00"));
        assertThat(dosTresDias.saldo()).isEqualTo(Money.de("150.00"));
    }

    @Test
    @DisplayName("o operador não lê o fluxo de caixa: é relatório do administrador (RF30)")
    void operadorNaoLeOFluxo() {
        ContaCriada conta = criador.criar("Mercearia com Atendente", SENHA_DE_TESTE);
        UsuarioCriado operador = criador.criarOperadorEm(conta.contaId(), "Atendente");

        assertThatExceptionOfType(AcessoNegadoException.class)
                .isThrownBy(() -> operador.comoUsuario(() -> fluxo.doPeriodo(DIA, DIA)));
    }

    @Test
    @DisplayName("período sem movimento devolve tudo zero, nunca nulo; período invertido é recusado")
    void periodoSemMovimentoEPeriodoInvertido() {
        ContaCriada conta = criador.criar("Mercearia da Rua", SENHA_DE_TESTE);

        FluxoDeCaixa vazio = conta.comoUsuario(
                () -> fluxo.doPeriodo(DIA, DIA));

        assertThat(vazio).isEqualTo(new FluxoDeCaixa(DIA, DIA, Money.ZERO, Money.ZERO, Money.ZERO,
                Money.ZERO, Money.ZERO, Money.ZERO, Money.ZERO, Money.ZERO));

        conta.comoUsuario(() ->
                assertThatIllegalArgumentException()
                        .isThrownBy(() -> fluxo.doPeriodo(DIA, DIA.minusDays(1)))
                        .withMessageContaining("nao pode vir antes do inicio"));
    }

    @Test
    @DisplayName("duas contas com movimentos no mesmo dia recebem cada uma só o seu fluxo (RNF05)")
    void contasComDadosEquivalentesRecebemResultadosDistintos() {
        ContaCriada contaA = criador.criar("Loja A", SENHA_DE_TESTE);
        ContaCriada contaB = criador.criar("Loja B", SENHA_DE_TESTE);
        ContaCriada contaC = criador.criar("Loja C", SENHA_DE_TESTE);

        sessao(contaA, noBalcao(DIA, LocalTime.of(8, 0)), List.of(
                lancado(TipoMovimentoCaixa.SUPRIMENTO, Money.de("10.00"), "troco",
                        noBalcao(DIA, LocalTime.of(8, 0))),
                lancado(TipoMovimentoCaixa.SANGRIA, Money.de("3.00"), "deposito",
                        noBalcao(DIA, LocalTime.of(9, 0)))));
        sessao(contaB, noBalcao(DIA, LocalTime.of(8, 0)), List.of(
                lancado(TipoMovimentoCaixa.SUPRIMENTO, Money.de("15.00"), "troco",
                        noBalcao(DIA, LocalTime.of(8, 0)))));

        FluxoDeCaixa deA = contaA.comoUsuario(
                () -> fluxo.doPeriodo(DIA, DIA));
        FluxoDeCaixa deB = contaB.comoUsuario(
                () -> fluxo.doPeriodo(DIA, DIA));
        FluxoDeCaixa deC = contaC.comoUsuario(
                () -> fluxo.doPeriodo(DIA, DIA));

        assertThat(deA.entradas()).isEqualTo(Money.de("10.00"));
        assertThat(deA.saidas()).isEqualTo(Money.de("3.00"));
        assertThat(deA.saldo()).isEqualTo(Money.de("7.00"));
        assertThat(deB.entradas()).isEqualTo(Money.de("15.00"));
        assertThat(deB.saidas()).isEqualTo(Money.ZERO);
        assertThat(deC.entradas()).isEqualTo(Money.ZERO);
        assertThat(deC.saidas()).isEqualTo(Money.ZERO);
        assertThat(deC.saldo()).isEqualTo(Money.ZERO);
    }

    /** Um horário local do balcão como o instante gravado no banco, em UTC. */
    private static Instant noBalcao(LocalDate dia, LocalTime hora) {
        return dia.atTime(hora).atZone(FusoDeReferencia.DO_BALCAO).toInstant();
    }

    private void sessao(ContaCriada conta, Instant abertaEm, List<MovimentoCaixa> movimentos) {
        sessoes.criarFechadaComMovimentos(conta.contaId(), conta.usuarioId(), abertaEm,
                Money.ZERO, movimentos);
    }

    /**
     * Abre um caixa de verdade para o operador, sem movimento nenhum, só para as vendas a que os
     * movimentos apontam terem uma sessão a que apontar.
     */
    private Cenario prepararCenario(ContaCriada conta) {
        UUID sessaoCaixaId = conta.comoUsuario(
                () -> caixas.abrir(Money.ZERO));
        return new Cenario(conta, sessaoCaixaId);
    }

    private final class Cenario {

        private final ContaCriada conta;
        private final UUID sessaoCaixaId;

        private Cenario(ContaCriada conta, UUID sessaoCaixaId) {
            this.conta = conta;
            this.sessaoCaixaId = sessaoCaixaId;
        }

        /** Uma venda vazia, só para ser alvo de um movimento VENDA ou ESTORNO. */
        UUID venda() {
            return vendas.criarAbertaEm(conta.contaId(), sessaoCaixaId, conta.usuarioId());
        }
    }
}
