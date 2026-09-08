package br.com.caixasimples.caixa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import br.com.caixasimples.TesteDeIntegracao;
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

/**
 * RNF05 para {@code sessao_caixa} — o passo 6 da skill {@code nova-entidade-multitenant}, no molde
 * de {@code IsolamentoEntreContasTest}: grava na conta A, consulta como conta B, espera vazio.
 *
 * <p>Em nenhuma linha abaixo existe {@code WHERE conta_id}. E o {@code @TenantId} do Hibernate que
 * filtra; se ele sair de {@code SessaoCaixaEntity}, este teste quebra — que e exatamente o ponto
 * dele.
 *
 * <p>Este arquivo fica no pacote {@code caixa} e so enxerga o que um controller enxergaria: a raiz
 * do agregado e o dominio. O {@code conta_id} do <em>membro</em> nao se ve daqui, e por isso existe
 * um segundo teste em {@code caixa.internal} — pelo mesmo motivo que separou os dois testes de
 * Cliente no R04.
 */
class IsolamentoDeSessaoCaixaTest extends TesteDeIntegracao {

    private static final String SENHA_DE_TESTE = "uma senha longa de teste";

    @Autowired
    private SessaoCaixaRepository sessoes;

    @Autowired
    private CriadorDeContaDeTeste criador;

    @AfterEach
    void limparContexto() {
        TenantContext.limpar();
    }

    @Test
    @DisplayName("conta B nao enxerga sessao de caixa da conta A por nenhum caminho de consulta")
    void contaNaoEnxergaSessaoDeOutraConta() {
        ContaCriada contaA = criador.criar("Cafeteria Piloto", SENHA_DE_TESTE);
        ContaCriada contaB = criador.criar("Salao Vizinho", SENHA_DE_TESTE);

        UUID sessaoDaContaA = TenantContext.executarComo(contaA.contaId(), () ->
                sessoes.save(SessaoCaixaEntity.de(
                        new SessaoCaixa(contaA.usuarioId(), Money.de("150.00")))).getId());

        TenantContext.executarComo(contaB.contaId(), () -> {
            assertThat(sessoes.findById(sessaoDaContaA))
                    .as("findById atravessando tenant")
                    .isEmpty();
            assertThat(sessoes.findAll())
                    .as("listagem da conta B")
                    .extracting(SessaoCaixaEntity::getId)
                    .doesNotContain(sessaoDaContaA);
        });

        // E a conta A continua vendo o proprio dado — filtro nao pode ser esconde de todos.
        TenantContext.executarComo(contaA.contaId(), () -> {
            assertThat(sessoes.findById(sessaoDaContaA)).isPresent();
            assertThat(sessoes.findAll())
                    .extracting(SessaoCaixaEntity::getId)
                    .containsExactly(sessaoDaContaA);
        });
    }

    @Test
    @DisplayName("contaId de uma sessao vem do contexto, nunca de parametro")
    void contaIdEPreenchidoPeloContextoDeTenant() {
        ContaCriada conta = criador.criar("Loja Teste", SENHA_DE_TESTE);

        // Repare que nem o construtor de SessaoCaixa nem o save recebem a conta: nao existe
        // assinatura por onde um chamador pudesse informa-la (RNF05).
        UUID sessaoId = TenantContext.executarComo(conta.contaId(), () ->
                sessoes.save(SessaoCaixaEntity.de(
                        new SessaoCaixa(conta.usuarioId(), Money.de("0.00")))).getId());

        TenantContext.executarComo(conta.contaId(), () ->
                assertThat(sessoes.findById(sessaoId))
                        .get()
                        .extracting(SessaoCaixaEntity::getContaId)
                        .isEqualTo(conta.contaId()));
    }

    @Test
    @DisplayName("o agregado inteiro volta do banco com o mesmo estado que entrou")
    void agregadoSobreviveAoIdaEVolta() {
        ContaCriada conta = criador.criar("Padaria Teste", SENHA_DE_TESTE);

        SessaoCaixa original = new SessaoCaixa(conta.usuarioId(), Money.de("100.00"));
        UUID vendaId = UUID.randomUUID();
        original.registrar(TipoMovimentoCaixa.VENDA, Money.de("25.00"), null, vendaId);
        original.registrar(TipoMovimentoCaixa.SUPRIMENTO, Money.de("50.00"), "Reforco de troco",
                null);
        original.registrar(TipoMovimentoCaixa.SANGRIA, Money.de("30.00"), "Pagamento do entregador",
                null);

        // Uma chamada de save grava a raiz e os tres movimentos: o agregado e a unidade
        // transacional, e e o cascade de SessaoCaixaEntity que faz isso valer.
        TenantContext.executarComo(conta.contaId(), () ->
                sessoes.save(SessaoCaixaEntity.de(original)));

        TenantContext.executarComo(conta.contaId(), () -> {
            SessaoCaixa lida = sessoes.findById(original.getId()).orElseThrow().paraDominio();

            assertThat(lida.getId()).isEqualTo(original.getId());
            assertThat(lida.getUsuarioId()).isEqualTo(conta.usuarioId());
            assertThat(lida.getValorAbertura()).isEqualTo(Money.de("100.00"));
            assertThat(lida.getStatus()).isEqualTo(StatusSessaoCaixa.ABERTA);
            assertThat(lida.getValorFechamentoContado()).isNull();
            assertThat(lida.getDiferenca()).isNull();
            assertThat(lida.getFechadaEm()).isNull();

            // A invariante do agregado atravessou o banco: 100 + 25 + 50 - 30.
            assertThat(lida.getValorFechamentoEsperado()).isEqualTo(Money.de("145.00"));

            assertThat(lida.getMovimentos())
                    .extracting(MovimentoCaixa::tipo, MovimentoCaixa::valor, MovimentoCaixa::motivo,
                            MovimentoCaixa::vendaId)
                    .containsExactlyInAnyOrder(
                            tuple(TipoMovimentoCaixa.VENDA, Money.de("25.00"), null, vendaId),
                            tuple(TipoMovimentoCaixa.SUPRIMENTO, Money.de("50.00"),
                                    "Reforco de troco", null),
                            tuple(TipoMovimentoCaixa.SANGRIA, Money.de("30.00"),
                                    "Pagamento do entregador", null));

            // O @OrderBy da entidade, afirmado sem depender de empate de relogio: tres chamadas
            // seguidas de Instant.now() podem cair no mesmo microssegundo, e um containsExactly
            // posicional viraria teste intermitente por causa disso.
            assertThat(lida.getMovimentos())
                    .extracting(MovimentoCaixa::criadoEm)
                    .isSorted();
        });
    }
}
