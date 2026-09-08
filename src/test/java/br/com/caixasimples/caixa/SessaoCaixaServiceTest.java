package br.com.caixasimples.caixa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
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
import br.com.caixasimples.contas.CriadorDeContaDeTeste;
import br.com.caixasimples.contas.CriadorDeContaDeTeste.ContaCriada;
import br.com.caixasimples.shared.Money;
import br.com.caixasimples.shared.TenantContext;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Os casos de uso do R07 — abertura (RF13), sangria e suprimento (RF14) — contra o banco de
 * verdade.
 *
 * <p>Existe separado de {@code SessaoCaixaTest} porque prova outra coisa: la a conta do agregado
 * esta certa <em>em memoria</em>; aqui ela <strong>atravessa o banco</strong>, que e o unico jeito
 * de exercitar o {@code atualizarCom} da entidade, a regra da D22a (que depende de consulta) e o
 * isolamento entre contas.
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
}
