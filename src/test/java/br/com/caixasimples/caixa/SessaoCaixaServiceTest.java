package br.com.caixasimples.caixa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.tuple;

import br.com.caixasimples.TesteDeIntegracao;
import br.com.caixasimples.caixa.application.OperadorJaTemCaixaAbertoException;
import br.com.caixasimples.caixa.application.SessaoCaixaNaoEncontradaException;
import br.com.caixasimples.caixa.application.SessaoCaixaService;
import br.com.caixasimples.caixa.domain.MovimentoCaixa;
import br.com.caixasimples.caixa.domain.SessaoCaixa;
import br.com.caixasimples.caixa.internal.SessaoCaixaEntity;
import br.com.caixasimples.caixa.internal.SessaoCaixaRepository;
import br.com.caixasimples.caixa.application.SessaoCaixaService.ResumoDeSessao;
import br.com.caixasimples.contas.CriadorDeContaDeTeste;
import br.com.caixasimples.contas.CriadorDeContaDeTeste.ContaCriada;
import br.com.caixasimples.shared.FusoDeReferencia;
import br.com.caixasimples.shared.Money;
import br.com.caixasimples.shared.TenantContext;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Os casos de uso do R07 — abertura (RF13), sangria e suprimento (RF14) — e os do R08 — fechamento
 * com conferencia (RF15) e historico por operador e por dia (RF16) — contra o banco de verdade.
 *
 * <p>Existe separado de {@code SessaoCaixaTest} porque prova outra coisa: la a conta do agregado
 * esta certa <em>em memoria</em>; aqui ela <strong>atravessa o banco</strong>, que e o unico jeito
 * de exercitar o {@code atualizarCom} da entidade, a regra da D22a (que depende de consulta), a
 * delimitacao do dia no fuso do balcao (P6/D23a) e o isolamento entre contas.
 *
 * <p>Fica no pacote {@code caixa} e enxerga so o que um controller enxergaria — o servico, o
 * dominio e a raiz do agregado.
 */
class SessaoCaixaServiceTest extends TesteDeIntegracao {

    private static final String SENHA_DE_TESTE = "uma senha longa de teste";

    @Autowired
    private SessaoCaixaService sessoesDeCaixa;

    @Autowired
    private SessaoCaixaRepository sessoes;

    @Autowired
    private CriadorDeContaDeTeste criador;

    @AfterEach
    void limparContexto() {
        TenantContext.limpar();
    }

    @Test
    @DisplayName("abrir grava uma sessao ABERTA com o esperado igual ao valor de abertura (RF13)")
    void abreSessaoComValorInicial() {
        ContaCriada conta = criador.criar("Cafeteria do R07", SENHA_DE_TESTE);

        UUID sessaoId = TenantContext.executarComo(conta.contaId(), () ->
                sessoesDeCaixa.abrir(conta.usuarioId(), Money.de("150.00")));

        TenantContext.executarComo(conta.contaId(), () -> {
            SessaoCaixa gravada = sessoes.findById(sessaoId).orElseThrow().paraDominio();

            assertThat(gravada.getUsuarioId()).isEqualTo(conta.usuarioId());
            assertThat(gravada.getStatus()).isEqualTo(StatusSessaoCaixa.ABERTA);
            assertThat(gravada.getValorAbertura()).isEqualTo(Money.de("150.00"));
            assertThat(gravada.getValorFechamentoEsperado()).isEqualTo(Money.de("150.00"));
            assertThat(gravada.getMovimentos()).isEmpty();
        });
    }

    @Test
    @DisplayName("sangria e suprimento atravessam o banco mantendo o esperado e o historico (RF14)")
    void sangriaESuprimentoAtualizamASessaoGravada() {
        ContaCriada conta = criador.criar("Padaria do R07", SENHA_DE_TESTE);

        UUID sessaoId = TenantContext.executarComo(conta.contaId(), () ->
                sessoesDeCaixa.abrir(conta.usuarioId(), Money.de("100.00")));

        // Dois lancamentos em transacoes separadas: e o que prova que o atualizarCom acrescenta o
        // movimento novo em vez de trocar a colecao inteira — se trocasse, o primeiro sumiria.
        TenantContext.executarComo(conta.contaId(), () ->
                sessoesDeCaixa.registrarSuprimento(sessaoId, Money.de("50.00"),
                        "Reforco de troco"));
        TenantContext.executarComo(conta.contaId(), () ->
                sessoesDeCaixa.registrarSangria(sessaoId, Money.de("30.00"),
                        "Pagamento do entregador"));

        TenantContext.executarComo(conta.contaId(), () -> {
            SessaoCaixa gravada = sessoes.findById(sessaoId).orElseThrow().paraDominio();

            // A invariante do agregado atravessou o banco duas vezes: 100 + 50 - 30.
            assertThat(gravada.getValorFechamentoEsperado()).isEqualTo(Money.de("120.00"));

            assertThat(gravada.getMovimentos())
                    .extracting(MovimentoCaixa::tipo, MovimentoCaixa::valor,
                            MovimentoCaixa::motivo)
                    .containsExactlyInAnyOrder(
                            tuple(TipoMovimentoCaixa.SUPRIMENTO, Money.de("50.00"),
                                    "Reforco de troco"),
                            tuple(TipoMovimentoCaixa.SANGRIA, Money.de("30.00"),
                                    "Pagamento do entregador"));
        });
    }

    @Test
    @DisplayName("o mesmo operador nao abre um segundo caixa enquanto o primeiro esta aberto")
    void operadorNaoAbreDoisCaixas() {
        ContaCriada conta = criador.criar("Loja do R07", SENHA_DE_TESTE);

        TenantContext.executarComo(conta.contaId(), () ->
                sessoesDeCaixa.abrir(conta.usuarioId(), Money.de("100.00")));

        // D22a — o caso comum e o caixa de ontem que ficou sem fechar, entao a recusa tem nome
        // proprio em vez de sair como violacao de integridade do indice.
        assertThatExceptionOfType(OperadorJaTemCaixaAbertoException.class).isThrownBy(() ->
                TenantContext.executarComo(conta.contaId(), () ->
                        sessoesDeCaixa.abrir(conta.usuarioId(), Money.de("80.00"))));
    }

    @Test
    @DisplayName("o indice da V6 recusa o segundo caixa mesmo sem passar pelo servico")
    void indiceParcialEhARedeDaRegraDeAbertura() {
        ContaCriada conta = criador.criar("Mercearia com Indice", SENHA_DE_TESTE);

        TenantContext.executarComo(conta.contaId(), () ->
                sessoesDeCaixa.abrir(conta.usuarioId(), Money.de("100.00")));

        // A checagem previa do servico mascara o indice no caminho normal, entao aqui a gravacao e
        // direta: e o que aconteceria com duas requisicoes passando juntas pela checagem. Sem esta
        // linha, a D22a valeria so enquanto ninguem escrevesse na tabela por outro caminho.
        assertThatExceptionOfType(DataIntegrityViolationException.class).isThrownBy(() ->
                TenantContext.executarComo(conta.contaId(), () ->
                        sessoes.save(SessaoCaixaEntity.de(
                                new SessaoCaixa(conta.usuarioId(), Money.de("80.00"))))));
    }

    @Test
    @DisplayName("dois operadores da mesma conta podem ter caixas simultaneos")
    void operadoresDiferentesAbremAoMesmoTempo() {
        ContaCriada conta = criador.criar("Mercado com Dois Caixas", SENHA_DE_TESTE);
        UUID segundoOperador = criador.criarOperadorEm(conta.contaId(), "Atendente da tarde");

        TenantContext.executarComo(conta.contaId(), () ->
                sessoesDeCaixa.abrir(conta.usuarioId(), Money.de("100.00")));

        // A regra da D22a e por operador, nao por conta — o negocio com dois pontos de atendimento
        // continua funcionando.
        assertThatNoException().isThrownBy(() ->
                TenantContext.executarComo(conta.contaId(), () ->
                        sessoesDeCaixa.abrir(segundoOperador, Money.de("60.00"))));
    }

    @Test
    @DisplayName("lancar em sessao que nao existe estoura com nome, e nao com NullPointer")
    void sessaoInexistenteERecusada() {
        ContaCriada conta = criador.criar("Salao do R07", SENHA_DE_TESTE);
        UUID inexistente = UUID.randomUUID();

        assertThatExceptionOfType(SessaoCaixaNaoEncontradaException.class).isThrownBy(() ->
                TenantContext.executarComo(conta.contaId(), () ->
                        sessoesDeCaixa.registrarSangria(inexistente, Money.de("10.00"), "Almoco")));
    }

    @Test
    @DisplayName("conta B nao lanca sangria na sessao da conta A")
    void naoSeLancaMovimentoNaSessaoDeOutraConta() {
        ContaCriada contaA = criador.criar("Oficina A", SENHA_DE_TESTE);
        ContaCriada contaB = criador.criar("Oficina B", SENHA_DE_TESTE);

        UUID sessaoDaContaA = TenantContext.executarComo(contaA.contaId(), () ->
                sessoesDeCaixa.abrir(contaA.usuarioId(), Money.de("200.00")));

        // RNF05 — o id de outra conta e indistinguivel de um id que nunca existiu, porque a linha
        // nao volta do banco. Nao ha caminho por onde a conta B mexa no dinheiro da conta A.
        assertThatExceptionOfType(SessaoCaixaNaoEncontradaException.class).isThrownBy(() ->
                TenantContext.executarComo(contaB.contaId(), () ->
                        sessoesDeCaixa.registrarSangria(sessaoDaContaA, Money.de("200.00"),
                                "Tentativa de outra conta")));

        TenantContext.executarComo(contaA.contaId(), () -> {
            SessaoCaixaEntity intacta = sessoes.findById(sessaoDaContaA).orElseThrow();
            assertThat(intacta.paraDominio().getValorFechamentoEsperado())
                    .as("a gaveta da conta A nao pode ter sido tocada")
                    .isEqualTo(Money.de("200.00"));
        });
    }

    @Test
    @DisplayName("o fechamento grava as quatro colunas de uma vez, e elas voltam do banco (RF15)")
    void fechamentoAtravessaOBanco() {
        ContaCriada conta = criador.criar("Mercearia do R08", SENHA_DE_TESTE);

        UUID sessaoId = TenantContext.executarComo(conta.contaId(), () ->
                sessoesDeCaixa.abrir(conta.usuarioId(), Money.de("100.00")));
        TenantContext.executarComo(conta.contaId(), () ->
                sessoesDeCaixa.registrarSuprimento(sessaoId, Money.de("50.00"), "Reforco de troco"));
        TenantContext.executarComo(conta.contaId(), () ->
                sessoesDeCaixa.registrarSangria(sessaoId, Money.de("30.00"), "Almoco"));

        // Esperado de 120,00 construido em tres transacoes; o operador conta 118,00.
        Money diferenca = TenantContext.executarComo(conta.contaId(), () ->
                sessoesDeCaixa.fechar(sessaoId, Money.de("118.00")));

        assertThat(diferenca).isEqualTo(Money.de("2.00"));

        TenantContext.executarComo(conta.contaId(), () -> {
            SessaoCaixa gravada = sessoes.findById(sessaoId).orElseThrow().paraDominio();

            // O atualizarCom do R07 nao escrevia estas quatro; o R08 e o passo que o fez crescer,
            // entao sem esta afirmacao o fechamento passaria em memoria e sumiria no commit.
            assertThat(gravada.getStatus()).isEqualTo(StatusSessaoCaixa.FECHADA);
            assertThat(gravada.getValorFechamentoContado()).isEqualTo(Money.de("118.00"));
            assertThat(gravada.getDiferenca()).isEqualTo(Money.de("2.00"));
            assertThat(gravada.getFechadaEm()).isNotNull();

            assertThat(gravada.getMovimentos())
                    .as("o extrato do expediente sobrevive ao fechamento")
                    .hasSize(2);
        });
    }

    @Test
    @DisplayName("sessao fechada nao aceita sangria nem segundo fechamento, mesmo vinda do banco")
    void sessaoFechadaNoBancoRecusaNovasOperacoes() {
        ContaCriada conta = criador.criar("Bar do R08", SENHA_DE_TESTE);

        UUID sessaoId = TenantContext.executarComo(conta.contaId(), () ->
                sessoesDeCaixa.abrir(conta.usuarioId(), Money.de("80.00")));
        TenantContext.executarComo(conta.contaId(), () ->
                sessoesDeCaixa.fechar(sessaoId, Money.de("75.00")));

        // D22d e D23e valem depois da ida e volta pelo banco, e nao so no objeto que foi fechado —
        // que e o caso real: quem tenta lancar amanha carrega a sessao de novo.
        assertThatIllegalStateException().isThrownBy(() ->
                TenantContext.executarComo(conta.contaId(), () ->
                        sessoesDeCaixa.registrarSangria(sessaoId, Money.de("10.00"), "Troco")));
        assertThatIllegalStateException().isThrownBy(() ->
                TenantContext.executarComo(conta.contaId(), () ->
                        sessoesDeCaixa.fechar(sessaoId, Money.de("80.00"))));

        TenantContext.executarComo(conta.contaId(), () ->
                assertThat(sessoes.findById(sessaoId).orElseThrow().paraDominio().getDiferenca())
                        .as("a conferencia original tem de estar intacta")
                        .isEqualTo(Money.de("5.00")));
    }

    @Test
    @DisplayName("caixa aberto as 22h em Paulo Afonso e do dia local, nao do dia seguinte em UTC")
    void oDiaEDoBalcaoENaoDeUtc() {
        ContaCriada conta = criador.criar("Lanchonete da Noite", SENHA_DE_TESTE);
        LocalDate segunda = LocalDate.of(2026, 9, 7);

        // 22h em America/Bahia e 01h do dia SEGUINTE em UTC, que e como a coluna fica gravada. Sem
        // a conversao da P6, esta sessao apareceria no historico de terca e sumiria do de segunda.
        Instant vinteEDuasHoras = instanteLocal(segunda, LocalTime.of(22, 0));
        assertThat(vinteEDuasHoras.atZone(ZoneOffset.UTC).toLocalDate())
                .as("premissa do teste: em UTC este instante ja e o dia seguinte")
                .isEqualTo(segunda.plusDays(1));

        UUID sessaoId = gravarSessaoAbertaEm(conta, conta.usuarioId(), vinteEDuasHoras);

        TenantContext.executarComo(conta.contaId(), () -> {
            assertThat(sessoesDeCaixa.historicoDoDia(segunda, null))
                    .extracting(ResumoDeSessao::id)
                    .containsExactly(sessaoId);

            assertThat(sessoesDeCaixa.historicoDoDia(segunda.plusDays(1), null))
                    .as("o expediente de segunda nao pode vazar para terca")
                    .isEmpty();
        });
    }

    @Test
    @DisplayName("o historico do dia filtra por operador e devolve os totais sem os movimentos")
    void historicoFiltraPorOperador() {
        ContaCriada conta = criador.criar("Mercado com Dois Turnos", SENHA_DE_TESTE);
        UUID daTarde = criador.criarOperadorEm(conta.contaId(), "Atendente da tarde");
        LocalDate dia = LocalDate.of(2026, 9, 8);

        UUID caixaDaManha = gravarSessaoAbertaEm(conta, conta.usuarioId(),
                instanteLocal(dia, LocalTime.of(8, 0)));
        UUID caixaDaTarde = gravarSessaoAbertaEm(conta, daTarde,
                instanteLocal(dia, LocalTime.of(14, 0)));

        TenantContext.executarComo(conta.contaId(), () -> {
            assertThat(sessoesDeCaixa.historicoDoDia(dia, null))
                    .extracting(ResumoDeSessao::id)
                    .as("sem operador, o dia inteiro da conta, na ordem do expediente")
                    .containsExactly(caixaDaManha, caixaDaTarde);

            List<ResumoDeSessao> soDaTarde = sessoesDeCaixa.historicoDoDia(dia, daTarde);
            assertThat(soDaTarde)
                    .extracting(ResumoDeSessao::id)
                    .containsExactly(caixaDaTarde);
            assertThat(soDaTarde.getFirst().valorAbertura()).isEqualTo(Money.de("60.00"));
            assertThat(soDaTarde.getFirst().status()).isEqualTo(StatusSessaoCaixa.ABERTA);
            assertThat(soDaTarde.getFirst().diferenca())
                    .as("sessao ainda aberta nao tem diferenca apurada")
                    .isNull();
        });
    }

    @Test
    @DisplayName("o historico da conta B nao traz sessao da conta A")
    void historicoNaoAtravessaConta() {
        ContaCriada contaA = criador.criar("Quitanda A", SENHA_DE_TESTE);
        ContaCriada contaB = criador.criar("Quitanda B", SENHA_DE_TESTE);
        LocalDate dia = LocalDate.of(2026, 9, 8);

        gravarSessaoAbertaEm(contaA, contaA.usuarioId(), instanteLocal(dia, LocalTime.of(9, 0)));

        // RNF05 — a consulta do historico e nova, entao precisa da prova nova: o @TenantId filtra
        // consulta derivada tambem, e nao so o findById.
        TenantContext.executarComo(contaB.contaId(), () ->
                assertThat(sessoesDeCaixa.historicoDoDia(dia, null)).isEmpty());

        TenantContext.executarComo(contaA.contaId(), () ->
                assertThat(sessoesDeCaixa.historicoDoDia(dia, null)).hasSize(1));
    }

    private static Instant instanteLocal(LocalDate dia, LocalTime hora) {
        return dia.atTime(hora).atZone(FusoDeReferencia.DO_BALCAO).toInstant();
    }

    /**
     * Grava uma sessao com {@code abertaEm} escolhido, que o caso de uso de abertura nao permite —
     * la o instante e sempre o de agora.
     *
     * <p>Remontar e gravar direto e o mesmo caminho que o teste do indice da V6 ja usa, e e o unico
     * jeito de provar a delimitacao do dia sem esperar as 22h para rodar a suite.
     */
    private UUID gravarSessaoAbertaEm(ContaCriada conta, UUID operador, Instant abertaEm) {
        SessaoCaixa sessao = SessaoCaixa.reconstituir(UUID.randomUUID(), operador,
                Money.de("60.00"), Money.de("60.00"), null, null, abertaEm, null,
                StatusSessaoCaixa.ABERTA, List.of());

        return TenantContext.executarComo(conta.contaId(), () ->
                sessoes.save(SessaoCaixaEntity.de(sessao)).getId());
    }
}
