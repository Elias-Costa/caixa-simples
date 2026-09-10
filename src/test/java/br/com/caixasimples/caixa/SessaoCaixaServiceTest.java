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
 * Os casos de uso da sessão de caixa contra o banco de verdade: abertura (RF13), sangria e
 * suprimento (RF14), fechamento com conferência (RF15) e histórico por operador e por dia (RF16).
 *
 * <p>Existe separado de {@code SessaoCaixaTest} porque prova outra coisa: lá a conta do agregado
 * está certa <em>em memória</em>; aqui ela <strong>atravessa o banco</strong>, que é o único jeito
 * de exercitar o {@code atualizarCom} da entidade, a regra de um caixa aberto por operador, que
 * depende de consulta, a delimitação do dia no fuso do balcão e o isolamento entre contas.
 *
 * <p>Fica no pacote {@code caixa} e enxerga só o que um controller enxergaria: o serviço, o domínio
 * e a raiz do agregado.
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
    @DisplayName("abrir grava uma sessão ABERTA com o esperado igual ao valor de abertura (RF13)")
    void abreSessaoComValorInicial() {
        ContaCriada conta = criador.criar("Cafeteria do Centro", SENHA_DE_TESTE);

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
    @DisplayName("sangria e suprimento atravessam o banco mantendo o esperado e o histórico (RF14)")
    void sangriaESuprimentoAtualizamASessaoGravada() {
        ContaCriada conta = criador.criar("Padaria do Centro", SENHA_DE_TESTE);

        UUID sessaoId = TenantContext.executarComo(conta.contaId(), () ->
                sessoesDeCaixa.abrir(conta.usuarioId(), Money.de("100.00")));

        // Dois lançamentos em transações separadas: é o que prova que o atualizarCom acrescenta o
        // movimento novo em vez de trocar a coleção inteira. Se trocasse, o primeiro sumiria.
        TenantContext.executarComo(conta.contaId(), () ->
                sessoesDeCaixa.registrarSuprimento(sessaoId, Money.de("50.00"),
                        "Reforco de troco"));
        TenantContext.executarComo(conta.contaId(), () ->
                sessoesDeCaixa.registrarSangria(sessaoId, Money.de("30.00"),
                        "Pagamento do entregador"));

        TenantContext.executarComo(conta.contaId(), () -> {
            SessaoCaixa gravada = sessoes.findById(sessaoId).orElseThrow().paraDominio();

            // A invariante do agregado atravessou o banco duas vezes: 100 mais 50 menos 30.
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
    @DisplayName("o mesmo operador não abre um segundo caixa enquanto o primeiro está aberto")
    void operadorNaoAbreDoisCaixas() {
        ContaCriada conta = criador.criar("Loja da Esquina", SENHA_DE_TESTE);

        TenantContext.executarComo(conta.contaId(), () ->
                sessoesDeCaixa.abrir(conta.usuarioId(), Money.de("100.00")));

        // O caso comum é o caixa de ontem que ficou sem fechar, então a recusa tem nome próprio em
        // vez de sair como violação de integridade do índice.
        assertThatExceptionOfType(OperadorJaTemCaixaAbertoException.class).isThrownBy(() ->
                TenantContext.executarComo(conta.contaId(), () ->
                        sessoesDeCaixa.abrir(conta.usuarioId(), Money.de("80.00"))));
    }

    @Test
    @DisplayName("o índice único parcial recusa o segundo caixa mesmo sem passar pelo serviço")
    void indiceParcialEhARedeDaRegraDeAbertura() {
        ContaCriada conta = criador.criar("Mercearia com Indice", SENHA_DE_TESTE);

        TenantContext.executarComo(conta.contaId(), () ->
                sessoesDeCaixa.abrir(conta.usuarioId(), Money.de("100.00")));

        // A checagem prévia do serviço mascara o índice no caminho normal, então aqui a gravação é
        // direta: é o que aconteceria com duas requisições passando juntas pela checagem. Sem esta
        // linha, a regra valeria só enquanto ninguém escrevesse na tabela por outro caminho.
        assertThatExceptionOfType(DataIntegrityViolationException.class).isThrownBy(() ->
                TenantContext.executarComo(conta.contaId(), () ->
                        sessoes.save(SessaoCaixaEntity.de(
                                new SessaoCaixa(conta.usuarioId(), Money.de("80.00"))))));
    }

    @Test
    @DisplayName("dois operadores da mesma conta podem ter caixas simultâneos")
    void operadoresDiferentesAbremAoMesmoTempo() {
        ContaCriada conta = criador.criar("Mercado com Dois Caixas", SENHA_DE_TESTE);
        UUID segundoOperador = criador.criarOperadorEm(conta.contaId(), "Atendente da tarde");

        TenantContext.executarComo(conta.contaId(), () ->
                sessoesDeCaixa.abrir(conta.usuarioId(), Money.de("100.00")));

        // A regra é por operador, não por conta, então o negócio com dois pontos de atendimento
        // continua funcionando.
        assertThatNoException().isThrownBy(() ->
                TenantContext.executarComo(conta.contaId(), () ->
                        sessoesDeCaixa.abrir(segundoOperador, Money.de("60.00"))));
    }

    @Test
    @DisplayName("lançar em sessão que não existe estoura com nome, e não com NullPointer")
    void sessaoInexistenteERecusada() {
        ContaCriada conta = criador.criar("Salao da Praca", SENHA_DE_TESTE);
        UUID inexistente = UUID.randomUUID();

        assertThatExceptionOfType(SessaoCaixaNaoEncontradaException.class).isThrownBy(() ->
                TenantContext.executarComo(conta.contaId(), () ->
                        sessoesDeCaixa.registrarSangria(inexistente, Money.de("10.00"), "Almoco")));
    }

    @Test
    @DisplayName("conta B não lança sangria na sessão da conta A")
    void naoSeLancaMovimentoNaSessaoDeOutraConta() {
        ContaCriada contaA = criador.criar("Oficina A", SENHA_DE_TESTE);
        ContaCriada contaB = criador.criar("Oficina B", SENHA_DE_TESTE);

        UUID sessaoDaContaA = TenantContext.executarComo(contaA.contaId(), () ->
                sessoesDeCaixa.abrir(contaA.usuarioId(), Money.de("200.00")));

        // O id de outra conta é indistinguível de um id que nunca existiu, porque a linha não volta
        // do banco (RNF05). Não há caminho por onde a conta B mexa no dinheiro da conta A.
        assertThatExceptionOfType(SessaoCaixaNaoEncontradaException.class).isThrownBy(() ->
                TenantContext.executarComo(contaB.contaId(), () ->
                        sessoesDeCaixa.registrarSangria(sessaoDaContaA, Money.de("200.00"),
                                "Tentativa de outra conta")));

        TenantContext.executarComo(contaA.contaId(), () -> {
            SessaoCaixaEntity intacta = sessoes.findById(sessaoDaContaA).orElseThrow();
            assertThat(intacta.paraDominio().getValorFechamentoEsperado())
                    .as("a gaveta da conta A não pode ter sido tocada")
                    .isEqualTo(Money.de("200.00"));
        });
    }

    @Test
    @DisplayName("o fechamento grava as quatro colunas de uma vez, e elas voltam do banco (RF15)")
    void fechamentoAtravessaOBanco() {
        ContaCriada conta = criador.criar("Mercearia da Rua", SENHA_DE_TESTE);

        UUID sessaoId = TenantContext.executarComo(conta.contaId(), () ->
                sessoesDeCaixa.abrir(conta.usuarioId(), Money.de("100.00")));
        TenantContext.executarComo(conta.contaId(), () ->
                sessoesDeCaixa.registrarSuprimento(sessaoId, Money.de("50.00"), "Reforco de troco"));
        TenantContext.executarComo(conta.contaId(), () ->
                sessoesDeCaixa.registrarSangria(sessaoId, Money.de("30.00"), "Almoco"));

        // Esperado de 120,00 construído em três transações; o operador conta 118,00.
        Money diferenca = TenantContext.executarComo(conta.contaId(), () ->
                sessoesDeCaixa.fechar(sessaoId, Money.de("118.00")));

        assertThat(diferenca).isEqualTo(Money.de("2.00"));

        TenantContext.executarComo(conta.contaId(), () -> {
            SessaoCaixa gravada = sessoes.findById(sessaoId).orElseThrow().paraDominio();

            // Sem estas afirmações, um fechamento que só valesse em memória passaria no teste e
            // sumiria no commit, porque as quatro colunas dependem do atualizarCom da entidade.
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
    @DisplayName("sessão fechada não aceita sangria nem segundo fechamento, mesmo vinda do banco")
    void sessaoFechadaNoBancoRecusaNovasOperacoes() {
        ContaCriada conta = criador.criar("Bar da Esquina", SENHA_DE_TESTE);

        UUID sessaoId = TenantContext.executarComo(conta.contaId(), () ->
                sessoesDeCaixa.abrir(conta.usuarioId(), Money.de("80.00")));
        TenantContext.executarComo(conta.contaId(), () ->
                sessoesDeCaixa.fechar(sessaoId, Money.de("75.00")));

        // As duas guardas valem depois da ida e volta pelo banco, e não só no objeto que foi
        // fechado, que é o caso real: quem tenta lançar amanhã carrega a sessão de novo.
        assertThatIllegalStateException().isThrownBy(() ->
                TenantContext.executarComo(conta.contaId(), () ->
                        sessoesDeCaixa.registrarSangria(sessaoId, Money.de("10.00"), "Troco")));
        assertThatIllegalStateException().isThrownBy(() ->
                TenantContext.executarComo(conta.contaId(), () ->
                        sessoesDeCaixa.fechar(sessaoId, Money.de("80.00"))));

        TenantContext.executarComo(conta.contaId(), () ->
                assertThat(sessoes.findById(sessaoId).orElseThrow().paraDominio().getDiferenca())
                        .as("a conferência original tem de estar intacta")
                        .isEqualTo(Money.de("5.00")));
    }

    @Test
    @DisplayName("caixa aberto às 22h é do dia local do balcão, não do dia seguinte em UTC")
    void oDiaEDoBalcaoENaoDeUtc() {
        ContaCriada conta = criador.criar("Lanchonete da Noite", SENHA_DE_TESTE);
        LocalDate segunda = LocalDate.of(2026, 9, 7);

        // 22h no fuso do balcão é 01h do dia SEGUINTE em UTC, que é como a coluna fica gravada. Sem
        // a conversão, esta sessão apareceria no histórico de terça e sumiria do de segunda.
        Instant vinteEDuasHoras = instanteLocal(segunda, LocalTime.of(22, 0));
        assertThat(vinteEDuasHoras.atZone(ZoneOffset.UTC).toLocalDate())
                .as("premissa do teste: em UTC este instante já é o dia seguinte")
                .isEqualTo(segunda.plusDays(1));

        UUID sessaoId = gravarSessaoAbertaEm(conta, conta.usuarioId(), vinteEDuasHoras);

        TenantContext.executarComo(conta.contaId(), () -> {
            assertThat(sessoesDeCaixa.historicoDoDia(segunda, null))
                    .extracting(ResumoDeSessao::id)
                    .containsExactly(sessaoId);

            assertThat(sessoesDeCaixa.historicoDoDia(segunda.plusDays(1), null))
                    .as("o expediente de segunda não pode vazar para terça")
                    .isEmpty();
        });
    }

    @Test
    @DisplayName("o histórico do dia filtra por operador e devolve os totais sem os movimentos")
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
                    .as("sessão ainda aberta não tem diferença apurada")
                    .isNull();
        });
    }

    @Test
    @DisplayName("o histórico da conta B não traz sessão da conta A")
    void historicoNaoAtravessaConta() {
        ContaCriada contaA = criador.criar("Quitanda A", SENHA_DE_TESTE);
        ContaCriada contaB = criador.criar("Quitanda B", SENHA_DE_TESTE);
        LocalDate dia = LocalDate.of(2026, 9, 8);

        gravarSessaoAbertaEm(contaA, contaA.usuarioId(), instanteLocal(dia, LocalTime.of(9, 0)));

        // A consulta do histórico é um caminho novo, então precisa da prova nova: o @TenantId
        // filtra consulta derivada também, e não só o findById (RNF05).
        TenantContext.executarComo(contaB.contaId(), () ->
                assertThat(sessoesDeCaixa.historicoDoDia(dia, null)).isEmpty());

        TenantContext.executarComo(contaA.contaId(), () ->
                assertThat(sessoesDeCaixa.historicoDoDia(dia, null)).hasSize(1));
    }

    private static Instant instanteLocal(LocalDate dia, LocalTime hora) {
        return dia.atTime(hora).atZone(FusoDeReferencia.DO_BALCAO).toInstant();
    }

    /**
     * Grava uma sessão com {@code abertaEm} escolhido, que o caso de uso de abertura não permite,
     * porque lá o instante é sempre o de agora.
     *
     * <p>Remontar e gravar direto é o mesmo caminho que o teste do índice único já usa, e é o único
     * jeito de provar a delimitação do dia sem esperar as 22h para rodar a suíte.
     */
    private UUID gravarSessaoAbertaEm(ContaCriada conta, UUID operador, Instant abertaEm) {
        SessaoCaixa sessao = SessaoCaixa.reconstituir(UUID.randomUUID(), operador,
                Money.de("60.00"), Money.de("60.00"), null, null, abertaEm, null,
                StatusSessaoCaixa.ABERTA, List.of());

        return TenantContext.executarComo(conta.contaId(), () ->
                sessoes.save(SessaoCaixaEntity.de(sessao)).getId());
    }
}
